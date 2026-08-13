package io.github.jutil.columnarprojection;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class ConcurrentBatchAppendRuntimeTest {

    @Test
    void executesOneCopyPerColumnConcurrentlyAndCommitsAfterAllFinish()
            throws Exception {
        CoordinatedExecutor copyExecutor = new CoordinatedExecutor(3);
        BatchProjectionStore store =
                BatchProjectionStore.create(2, copyExecutor);
        BatchProjectionStore.Batch batch = store.batch()
                .quantity(new int[] {11, 22})
                .symbol(new String[] {"first", "second"})
                .payload(new byte[][] {new byte[] {1}, new byte[] {2}});
        ExecutorService appendExecutor =
                Executors.newSingleThreadExecutor();

        try {
            Future<Void> append = appendExecutor.submit(new Callable<Void>() {
                @Override
                public Void call() {
                    batch.append();
                    return null;
                }
            });

            assertTrue(copyExecutor.awaitSubmissions(5, TimeUnit.SECONDS));
            assertEquals(3, copyExecutor.submissionCount());
            assertFalse(append.isDone());
            assertEquals(0, store.size());

            copyExecutor.startAll();
            append.get(5, TimeUnit.SECONDS);

            assertEquals(3, copyExecutor.maximumActiveCount());
            assertEquals(2, store.size());
            store.seal();
            assertRow(store.viewAt(0), 11, "first", (byte) 1);
            assertRow(store.viewAt(1), 22, "second", (byte) 2);
        } finally {
            copyExecutor.releaseWorkers();
            appendExecutor.shutdownNow();
        }
    }

    @Test
    void appendWaitsForEverySubmittedCopyBeforePublishingSize()
            throws Exception {
        ManualExecutor copyExecutor = new ManualExecutor(3);
        BatchProjectionStore store =
                BatchProjectionStore.create(2, copyExecutor);
        BatchProjectionStore.Batch batch = store.batch()
                .quantity(new int[] {31, 32})
                .symbol(new String[] {"thirty-one", "thirty-two"})
                .payload(new byte[][] {new byte[] {31}, new byte[] {32}});
        ExecutorService appendExecutor =
                Executors.newSingleThreadExecutor();

        try {
            Future<Void> append = appendExecutor.submit(new Callable<Void>() {
                @Override
                public Void call() {
                    batch.append();
                    return null;
                }
            });

            assertTrue(copyExecutor.awaitSubmissions(5, TimeUnit.SECONDS));
            copyExecutor.runNext();
            copyExecutor.runNext();

            assertFalse(append.isDone());
            assertEquals(0, store.size());

            copyExecutor.runNext();
            append.get(5, TimeUnit.SECONDS);
            assertEquals(2, store.size());
        } finally {
            appendExecutor.shutdownNow();
        }
    }

    @Test
    void emptyExplicitBatchDoesNotSubmitCopyTasks() {
        CountingExecutor copyExecutor = new CountingExecutor();
        BatchProjectionStore store =
                BatchProjectionStore.create(0, copyExecutor);

        store.batch(4, 4).append();

        assertEquals(0, copyExecutor.submissionCount());
        assertEquals(0, store.size());
    }

    @Test
    void rejectedSubmissionLeavesNoPartialColumnsAndBatchCanRetry()
            throws Exception {
        RejectedExecutionException rejection =
                new RejectedExecutionException("expected rejection");
        RejectThirdSubmissionOnce copyExecutor =
                new RejectThirdSubmissionOnce(rejection);
        BatchProjection__ColumnarProjectionStore store =
                (BatchProjection__ColumnarProjectionStore)
                        BatchProjectionStore.create(2, copyExecutor);
        byte[] firstPayload = new byte[] {41};
        byte[] secondPayload = new byte[] {42};
        BatchProjectionStore.Batch batch = store.batch()
                .quantity(new int[] {41, 42})
                .symbol(new String[] {"forty-one", "forty-two"})
                .payload(new byte[][] {firstPayload, secondPayload});

        assertSame(rejection,
                assertThrows(RejectedExecutionException.class,
                        batch::append));
        assertEquals(0, store.size());
        assertReferenceDestinationRangeIsCleared(store, 0, 2);
        assertEquals(3, sourceReferenceCount(batch, false));

        batch.append();

        assertEquals(2, store.size());
        assertEquals(3, sourceReferenceCount(batch, true));
        store.seal();
        assertRow(store.viewAt(0), 41, "forty-one", (byte) 41);
        assertRow(store.viewAt(1), 42, "forty-two", (byte) 42);
    }

    @Test
    void rejectionWaitsForPreviouslyAcceptedAsynchronousCopies()
            throws Exception {
        RejectedExecutionException rejection =
                new RejectedExecutionException("expected rejection");
        RejectAfterAcceptedAsyncExecutor copyExecutor =
                new RejectAfterAcceptedAsyncExecutor(2, rejection);
        BatchProjectionStore store =
                BatchProjectionStore.create(2, copyExecutor);
        final BatchProjectionStore.Batch batch = store.batch()
                .quantity(new int[] {51, 52})
                .symbol(new String[] {"fifty-one", "fifty-two"})
                .payload(new byte[][] {new byte[] {51}, new byte[] {52}});
        ExecutorService appendExecutor =
                Executors.newSingleThreadExecutor();

        try {
            Future<Void> append = appendExecutor.submit(new Callable<Void>() {
                @Override
                public Void call() {
                    batch.append();
                    return null;
                }
            });

            assertTrue(copyExecutor.awaitRejection(5, TimeUnit.SECONDS));
            assertTrue(copyExecutor.awaitAcceptedStarts(
                    5, TimeUnit.SECONDS));
            assertFalse(append.isDone());
            assertEquals(0, store.size());

            copyExecutor.releaseAcceptedCopies();
            ExecutionException failure = assertThrows(
                    ExecutionException.class,
                    () -> append.get(5, TimeUnit.SECONDS));
            assertSame(rejection, failure.getCause());
            assertEquals(0, store.size());
        } finally {
            copyExecutor.releaseAcceptedCopies();
            appendExecutor.shutdownNow();
        }
    }

    @Test
    void workerFailureClearsReferenceCopiesAndBatchCanRetry()
            throws Exception {
        ThreadPerTaskExecutor copyExecutor = new ThreadPerTaskExecutor();
        BatchProjection__ColumnarProjectionStore store =
                (BatchProjection__ColumnarProjectionStore)
                        BatchProjectionStore.create(3, copyExecutor);
        String[] symbols = new String[] {"one", "two", "three"};
        BatchProjectionStore.Batch batch = store.batch()
                .quantity(new int[] {1, 2, 3})
                .symbol(symbols)
                .payload(new byte[][] {
                    new byte[] {1}, new byte[] {2}, new byte[] {3}
                });
        Field symbolSource = sourceField(batch, String[].class);
        symbolSource.set(batch, new String[0]);

        assertThrows(IndexOutOfBoundsException.class, batch::append);

        assertEquals(0, store.size());
        assertReferenceDestinationRangeIsCleared(store, 0, 3);
        assertEquals(3, sourceReferenceCount(batch, false));

        symbolSource.set(batch, symbols);
        batch.append();

        assertEquals(3, store.size());
        assertEquals(3, sourceReferenceCount(batch, true));
        store.seal();
        assertRow(store.viewAt(0), 1, "one", (byte) 1);
        assertRow(store.viewAt(2), 3, "three", (byte) 3);
    }

    @Test
    void workerFailureWaitsForAnotherAcceptedAsynchronousCopy()
            throws Exception {
        String[] symbols = new String[] {"one", "two", "three"};
        BatchProjectionStore.Batch temporaryBatch =
                new BatchProjection__ColumnarProjectionStore(3).batch()
                        .quantity(new int[] {1, 2, 3})
                        .symbol(symbols)
                        .payload(new byte[][] {
                            new byte[] {1}, new byte[] {2}, new byte[] {3}
                        });
        Field symbolSource = sourceField(temporaryBatch, String[].class);
        int failingOrdinal = sourceOrdinal(symbolSource);
        int blockingOrdinal = failingOrdinal == 1 ? 2 : 1;
        OrdinalAsyncExecutor copyExecutor = new OrdinalAsyncExecutor(
                blockingOrdinal, failingOrdinal);
        BatchProjection__ColumnarProjectionStore store =
                (BatchProjection__ColumnarProjectionStore)
                        BatchProjectionStore.create(3, copyExecutor);
        final BatchProjectionStore.Batch batch = store.batch()
                .quantity(new int[] {1, 2, 3})
                .symbol(symbols)
                .payload(new byte[][] {
                    new byte[] {1}, new byte[] {2}, new byte[] {3}
                });
        sourceField(batch, String[].class).set(batch, new String[0]);
        ExecutorService appendExecutor =
                Executors.newSingleThreadExecutor();

        try {
            Future<Void> append = appendExecutor.submit(new Callable<Void>() {
                @Override
                public Void call() {
                    batch.append();
                    return null;
                }
            });

            assertTrue(copyExecutor.awaitBlockedStart(5, TimeUnit.SECONDS));
            assertTrue(copyExecutor.awaitFailingCompletion(
                    5, TimeUnit.SECONDS));
            assertFalse(append.isDone());
            assertEquals(0, store.size());

            copyExecutor.releaseBlockedCopy();
            ExecutionException failure = assertThrows(
                    ExecutionException.class,
                    () -> append.get(5, TimeUnit.SECONDS));
            assertTrue(failure.getCause() instanceof IndexOutOfBoundsException,
                    String.valueOf(failure.getCause()));
            assertEquals(0, store.size());
        } finally {
            copyExecutor.releaseBlockedCopy();
            appendExecutor.shutdownNow();
        }
    }

    @Test
    void directExecutorCopiesOneColumnWithOneTask() {
        CountingExecutor copyExecutor = new CountingExecutor();
        IntProjectionStore store =
                IntProjectionStore.create(3, copyExecutor);

        store.batch().value(new int[] {61, 62, 63}).append();

        assertEquals(1, copyExecutor.submissionCount());
        assertEquals(3, store.size());
        store.seal();
        assertEquals(61, store.viewAt(0).value());
        assertEquals(63, store.viewAt(2).value());
    }

    @Test
    void interruptWhileWaitingIsRestoredAfterAllCopiesComplete()
            throws Exception {
        BlockingAsyncExecutor copyExecutor = new BlockingAsyncExecutor();
        final IntProjectionStore store =
                IntProjectionStore.create(1, copyExecutor);
        final AtomicBoolean interruptedAfterAppend = new AtomicBoolean();
        final AtomicReference<Throwable> appendFailure =
                new AtomicReference<Throwable>();
        final CountDownLatch appendFinished = new CountDownLatch(1);
        Thread caller = new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    store.batch().value(new int[] {71}).append();
                    interruptedAfterAppend.set(
                            Thread.currentThread().isInterrupted());
                } catch (Throwable failure) {
                    appendFailure.set(failure);
                } finally {
                    appendFinished.countDown();
                }
            }
        }, "interrupted-batch-append-test-caller");
        caller.setDaemon(true);

        try {
            caller.start();
            assertTrue(copyExecutor.awaitTaskStart(5, TimeUnit.SECONDS));
            caller.interrupt();
            assertTrue(awaitInterruptConsumption(
                    caller, 5, TimeUnit.SECONDS));
            assertEquals(1L, appendFinished.getCount());
            assertEquals(0, store.size());

            copyExecutor.releaseTask();
            assertTrue(appendFinished.await(5, TimeUnit.SECONDS));

            assertNull(appendFailure.get());
            assertTrue(interruptedAfterAppend.get());
            assertEquals(1, store.size());
        } finally {
            copyExecutor.releaseTask();
            caller.join(TimeUnit.SECONDS.toMillis(5));
        }
    }

    @Test
    void sealingDoesNotCloseTheCallerOwnedExecutor() throws Exception {
        ExecutorService copyExecutor = Executors.newFixedThreadPool(3);
        try {
            BatchProjectionStore store =
                    BatchProjectionStore.create(1, copyExecutor);
            store.batch()
                    .quantity(new int[] {7})
                    .symbol(new String[] {"seven"})
                    .payload(new byte[][] {new byte[] {7}})
                    .append();

            store.seal();

            assertFalse(copyExecutor.isShutdown());
            Field executorField = store.getClass().getDeclaredField(
                    "batchCopyExecutor");
            executorField.setAccessible(true);
            assertNull(executorField.get(store));
            Future<Integer> stillUsable = copyExecutor.submit(
                    new Callable<Integer>() {
                        @Override
                        public Integer call() {
                            return Integer.valueOf(73);
                        }
                    });
            assertEquals(Integer.valueOf(73),
                    stillUsable.get(5, TimeUnit.SECONDS));
        } finally {
            copyExecutor.shutdownNow();
        }
    }

    @Test
    void concurrentFactoryRejectsNullExecutor() {
        assertThrows(NullPointerException.class,
                () -> BatchProjectionStore.create(0, null));
    }

    private static void assertRow(
            BatchProjection row,
            int quantity,
            String symbol,
            byte payload) {
        assertEquals(quantity, row.quantity());
        assertEquals(symbol, row.symbol());
        assertEquals(payload, row.payload()[0]);
    }

    private static int sourceReferenceCount(
            Object batch, boolean expectCleared) throws Exception {
        int count = 0;
        for (Field field : batch.getClass().getDeclaredFields()) {
            if (!field.getName().startsWith("source")
                    || !field.getType().isArray()) {
                continue;
            }
            field.setAccessible(true);
            if (expectCleared) {
                assertNull(field.get(batch), field.getName());
            } else {
                assertTrue(field.get(batch) != null, field.getName());
            }
            count++;
        }
        return count;
    }

    private static Field sourceField(Object batch, Class<?> fieldType) {
        for (Field field : batch.getClass().getDeclaredFields()) {
            if (field.getName().startsWith("source")
                    && field.getType().equals(fieldType)) {
                field.setAccessible(true);
                return field;
            }
        }
        throw new AssertionError("No generated source field of type "
                + fieldType.getName());
    }

    private static int sourceOrdinal(Field field) {
        return Integer.parseInt(
                field.getName().substring("source".length())) + 1;
    }

    private static boolean awaitInterruptConsumption(
            Thread thread, long timeout, TimeUnit unit) {
        long deadline = System.nanoTime() + unit.toNanos(timeout);
        while (thread.isInterrupted() && System.nanoTime() < deadline) {
            Thread.yield();
        }
        return thread.isAlive() && !thread.isInterrupted();
    }

    private static void assertReferenceDestinationRangeIsCleared(
            Object store, int fromIndex, int toIndex) throws Exception {
        int referenceColumnCount = 0;
        for (Field field : store.getClass().getDeclaredFields()) {
            if (!field.getName().startsWith("column")
                    || !field.getType().isArray()
                    || field.getType().getComponentType().isPrimitive()) {
                continue;
            }
            field.setAccessible(true);
            Object column = field.get(store);
            for (int index = fromIndex; index < toIndex; index++) {
                assertNull(java.lang.reflect.Array.get(column, index),
                        field.getName() + "[" + index + "]");
            }
            referenceColumnCount++;
        }
        assertEquals(2, referenceColumnCount);
    }

    private static final class CountingExecutor implements Executor {
        private final AtomicInteger submissions = new AtomicInteger();

        @Override
        public void execute(Runnable command) {
            submissions.incrementAndGet();
            command.run();
        }

        int submissionCount() {
            return submissions.get();
        }
    }

    private static final class ManualExecutor implements Executor {
        private final List<Runnable> commands =
                Collections.synchronizedList(new ArrayList<Runnable>());
        private final CountDownLatch submitted;

        ManualExecutor(int expectedSubmissions) {
            submitted = new CountDownLatch(expectedSubmissions);
        }

        @Override
        public void execute(Runnable command) {
            commands.add(command);
            submitted.countDown();
        }

        boolean awaitSubmissions(long timeout, TimeUnit unit)
                throws InterruptedException {
            return submitted.await(timeout, unit);
        }

        void runNext() {
            final Runnable command;
            synchronized (commands) {
                command = commands.remove(0);
            }
            command.run();
        }
    }

    private static final class CoordinatedExecutor implements Executor {
        private final List<Runnable> commands =
                Collections.synchronizedList(new ArrayList<Runnable>());
        private final List<Thread> workers =
                Collections.synchronizedList(new ArrayList<Thread>());
        private final CountDownLatch submitted;
        private final CountDownLatch allActive;
        private final AtomicInteger active = new AtomicInteger();
        private final AtomicInteger maximumActive = new AtomicInteger();

        CoordinatedExecutor(int expectedSubmissions) {
            submitted = new CountDownLatch(expectedSubmissions);
            allActive = new CountDownLatch(expectedSubmissions);
        }

        @Override
        public void execute(final Runnable command) {
            commands.add(new Runnable() {
                @Override
                public void run() {
                    int current = active.incrementAndGet();
                    updateMaximum(current);
                    allActive.countDown();
                    awaitUninterruptibly(allActive);
                    try {
                        command.run();
                    } finally {
                        active.decrementAndGet();
                    }
                }
            });
            submitted.countDown();
        }

        boolean awaitSubmissions(long timeout, TimeUnit unit)
                throws InterruptedException {
            return submitted.await(timeout, unit);
        }

        int submissionCount() {
            synchronized (commands) {
                return commands.size();
            }
        }

        int maximumActiveCount() {
            return maximumActive.get();
        }

        void startAll() {
            List<Runnable> submittedCommands;
            synchronized (commands) {
                submittedCommands = new ArrayList<Runnable>(commands);
            }
            for (Runnable command : submittedCommands) {
                Thread worker = new Thread(command,
                        "column-copy-test-worker");
                worker.setDaemon(true);
                workers.add(worker);
                worker.start();
            }
        }

        void releaseWorkers() {
            synchronized (workers) {
                for (Thread worker : workers) {
                    worker.interrupt();
                }
            }
        }

        private void updateMaximum(int candidate) {
            int observed = maximumActive.get();
            while (candidate > observed
                    && !maximumActive.compareAndSet(observed, candidate)) {
                observed = maximumActive.get();
            }
        }
    }

    private static final class RejectThirdSubmissionOnce
            implements Executor {
        private final RejectedExecutionException rejection;
        private int submissions;
        private boolean rejected;

        RejectThirdSubmissionOnce(RejectedExecutionException rejection) {
            this.rejection = rejection;
        }

        @Override
        public void execute(Runnable command) {
            submissions++;
            if (!rejected && submissions == 3) {
                rejected = true;
                throw rejection;
            }
            command.run();
        }
    }

    private static final class RejectAfterAcceptedAsyncExecutor
            implements Executor {
        private final int acceptedCount;
        private final RejectedExecutionException rejection;
        private final AtomicInteger submissions = new AtomicInteger();
        private final CountDownLatch acceptedStarts;
        private final CountDownLatch releaseAccepted = new CountDownLatch(1);
        private final CountDownLatch rejected = new CountDownLatch(1);

        RejectAfterAcceptedAsyncExecutor(
                int acceptedCount,
                RejectedExecutionException rejection) {
            this.acceptedCount = acceptedCount;
            this.rejection = rejection;
            this.acceptedStarts = new CountDownLatch(acceptedCount);
        }

        @Override
        public void execute(final Runnable command) {
            int ordinal = submissions.incrementAndGet();
            if (ordinal > acceptedCount) {
                rejected.countDown();
                throw rejection;
            }
            Thread worker = new Thread(new Runnable() {
                @Override
                public void run() {
                    acceptedStarts.countDown();
                    awaitUninterruptibly(releaseAccepted);
                    command.run();
                }
            }, "accepted-column-copy-test-worker");
            worker.setDaemon(true);
            worker.start();
        }

        boolean awaitAcceptedStarts(long timeout, TimeUnit unit)
                throws InterruptedException {
            return acceptedStarts.await(timeout, unit);
        }

        boolean awaitRejection(long timeout, TimeUnit unit)
                throws InterruptedException {
            return rejected.await(timeout, unit);
        }

        void releaseAcceptedCopies() {
            releaseAccepted.countDown();
        }
    }

    private static final class OrdinalAsyncExecutor implements Executor {
        private final int blockingOrdinal;
        private final int failingOrdinal;
        private final AtomicInteger submissions = new AtomicInteger();
        private final CountDownLatch blockedStart = new CountDownLatch(1);
        private final CountDownLatch releaseBlocked = new CountDownLatch(1);
        private final CountDownLatch failingCompletion =
                new CountDownLatch(1);

        OrdinalAsyncExecutor(int blockingOrdinal, int failingOrdinal) {
            this.blockingOrdinal = blockingOrdinal;
            this.failingOrdinal = failingOrdinal;
        }

        @Override
        public void execute(final Runnable command) {
            final int ordinal = submissions.incrementAndGet();
            if (ordinal == blockingOrdinal) {
                Thread worker = new Thread(new Runnable() {
                    @Override
                    public void run() {
                        blockedStart.countDown();
                        awaitUninterruptibly(releaseBlocked);
                        command.run();
                    }
                }, "blocked-column-copy-test-worker");
                worker.setDaemon(true);
                worker.start();
                return;
            }
            command.run();
            if (ordinal == failingOrdinal) {
                failingCompletion.countDown();
            }
        }

        boolean awaitBlockedStart(long timeout, TimeUnit unit)
                throws InterruptedException {
            return blockedStart.await(timeout, unit);
        }

        boolean awaitFailingCompletion(long timeout, TimeUnit unit)
                throws InterruptedException {
            return failingCompletion.await(timeout, unit);
        }

        void releaseBlockedCopy() {
            releaseBlocked.countDown();
        }
    }

    private static final class BlockingAsyncExecutor implements Executor {
        private final CountDownLatch taskStart = new CountDownLatch(1);
        private final CountDownLatch releaseTask = new CountDownLatch(1);

        @Override
        public void execute(final Runnable command) {
            Thread worker = new Thread(new Runnable() {
                @Override
                public void run() {
                    taskStart.countDown();
                    awaitUninterruptibly(releaseTask);
                    command.run();
                }
            }, "interrupt-column-copy-test-worker");
            worker.setDaemon(true);
            worker.start();
        }

        boolean awaitTaskStart(long timeout, TimeUnit unit)
                throws InterruptedException {
            return taskStart.await(timeout, unit);
        }

        void releaseTask() {
            releaseTask.countDown();
        }
    }

    private static final class ThreadPerTaskExecutor implements Executor {
        @Override
        public void execute(Runnable command) {
            Thread worker = new Thread(command,
                    "column-copy-failure-test-worker");
            worker.setDaemon(true);
            worker.start();
        }
    }

    private static void awaitUninterruptibly(CountDownLatch latch) {
        boolean interrupted = false;
        while (true) {
            try {
                latch.await();
                break;
            } catch (InterruptedException exception) {
                interrupted = true;
            }
        }
        if (interrupted) {
            Thread.currentThread().interrupt();
        }
    }
}
