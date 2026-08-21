package io.github.jutil.columnarprojection;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class PerColumnFillRuntimeTest {

    @Test
    void generatedContractHasWholeArrayAndRangeMethodsOnlyOnAppender()
            throws Exception {
        Class<?> contract = BatchProjectionStore.class;
        Class<?> appender = BatchProjectionStore.ColumnAppender.class;

        Method columnAppender = contract.getMethod("columnAppender");
        assertTrue(columnAppender.isDefault());
        assertSame(appender, columnAppender.getReturnType());
        assertEquals(Void.TYPE, appender.getMethod(
                "quantity", int[].class).getReturnType());
        assertEquals(Void.TYPE, appender.getMethod(
                "quantity", int[].class, Integer.TYPE, Integer.TYPE)
                .getReturnType());
        assertEquals(Void.TYPE, appender.getMethod(
                "symbol", String[].class).getReturnType());
        assertEquals(Void.TYPE, appender.getMethod(
                "symbol", String[].class, Integer.TYPE, Integer.TYPE)
                .getReturnType());
        assertEquals(Void.TYPE, appender.getMethod(
                "payload", byte[][].class).getReturnType());
        assertEquals(Void.TYPE, appender.getMethod(
                "payload", byte[][].class, Integer.TYPE, Integer.TYPE)
                .getReturnType());
        assertThrows(NoSuchMethodException.class,
                () -> appender.getMethod("quantity", int[].class,
                        Integer.TYPE));
        assertThrows(NoSuchMethodException.class,
                () -> contract.getMethod("quantity", int[].class));
        assertThrows(NoSuchMethodException.class,
                () -> BatchProjection__ColumnarProjectionStore.class.getMethod(
                        "quantity", int[].class));
        assertThrows(NoSuchMethodException.class,
                () -> ProjectionStore.class.getMethod("columnAppender"));
        assertFalse(appender.isAssignableFrom(
                BatchProjection__ColumnarProjectionStore.class));

        BatchProjectionStore store = BatchProjectionStore.create(0);
        assertSame(store.columnAppender(), store.columnAppender());

        Constructor<?>[] constructors =
                BatchProjection__ColumnarProjectionStore.class
                        .getDeclaredConstructors();
        assertEquals(1, constructors.length);
        assertEquals(1, constructors[0].getParameterTypes().length);
        assertSame(Integer.TYPE, constructors[0].getParameterTypes()[0]);
        assertGeneratedStoreContainsNoCoordinationApi(
                BatchProjection__ColumnarProjectionStore.class);
        assertGeneratedStoreContainsNoCoordinationApi(appender);
    }

    @Test
    void repeatedChunksWithDifferentBoundariesAlignAtSeal() {
        BatchProjectionStore store = BatchProjectionStore.create(1);
        BatchProjectionStore.ColumnAppender columns = store.columnAppender();
        byte[] payload0 = new byte[] {0};
        byte[] payload1 = new byte[] {1};
        byte[] payload2 = new byte[] {2};
        byte[] payload3 = new byte[] {3};

        int[] firstQuantities = new int[] {10, 11, 99};
        columns.quantity(firstQuantities, 0, 2);
        firstQuantities[0] = -1;
        columns.quantity(new int[] {0, 12, 13}, 1, 3);

        String[] firstSymbols = new String[] {"A"};
        columns.symbol(firstSymbols);
        firstSymbols[0] = "changed";
        columns.symbol(new String[] {"ignored", "B", "C", "D"}, 1, 4);

        byte[][] firstPayloads =
                new byte[][] {payload0, payload1, payload2};
        columns.payload(firstPayloads);
        firstPayloads[0] = new byte[] {99};
        columns.payload(new byte[][] {payload3});

        assertEquals(0, store.size());
        store.seal();

        assertEquals(4, store.size());
        assertRow(store.viewAt(0), 10, "A", payload0);
        assertRow(store.viewAt(1), 11, "B", payload1);
        assertRow(store.viewAt(2), 12, "C", payload2);
        assertRow(store.viewAt(3), 13, "D", payload3);
    }

    @Test
    void distinctColumnsFillConcurrentlyThroughRepeatedIndependentGrowth()
            throws Exception {
        final int rowCount = 200;
        BatchProjectionStore store = BatchProjectionStore.create(0);
        BatchProjectionStore.ColumnAppender columns = store.columnAppender();
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService workers = Executors.newFixedThreadPool(3);
        try {
            Future<?> quantities = workers.submit(() -> {
                await(start);
                for (int index = 0; index < rowCount; index++) {
                    columns.quantity(new int[] {index});
                }
            });
            Future<?> symbols = workers.submit(() -> {
                await(start);
                for (int from = 0; from < rowCount; from += 3) {
                    int length = Math.min(3, rowCount - from);
                    String[] chunk = new String[length];
                    for (int offset = 0; offset < length; offset++) {
                        chunk[offset] = "S" + (from + offset);
                    }
                    columns.symbol(chunk);
                }
            });
            Future<?> payloads = workers.submit(() -> {
                await(start);
                for (int from = 0; from < rowCount; from += 7) {
                    int length = Math.min(7, rowCount - from);
                    byte[][] chunk = new byte[length][];
                    for (int offset = 0; offset < length; offset++) {
                        chunk[offset] = new byte[] {(byte) (from + offset)};
                    }
                    columns.payload(chunk);
                }
            });

            start.countDown();
            quantities.get(10, TimeUnit.SECONDS);
            symbols.get(10, TimeUnit.SECONDS);
            payloads.get(10, TimeUnit.SECONDS);
        } finally {
            workers.shutdownNow();
        }

        assertEquals(0, store.size());
        store.seal();
        assertEquals(rowCount, store.size());
        for (int index = 0; index < rowCount; index++) {
            BatchProjection row = store.viewAt(index);
            assertEquals(index, row.quantity());
            assertEquals("S" + index, row.symbol());
            assertEquals((byte) index, row.payload()[0]);
        }
    }

    @Test
    void oneColumnReturnsWithoutWaitingForColumnsThatHaveNotStarted()
            throws Exception {
        BatchProjectionStore store = BatchProjectionStore.create(0);
        BatchProjectionStore.ColumnAppender columns = store.columnAppender();
        ExecutorService worker = Executors.newSingleThreadExecutor();
        try {
            Future<?> completed = worker.submit(
                    () -> columns.quantity(new int[] {1, 2, 3}));
            completed.get(2, TimeUnit.SECONDS);
        } finally {
            worker.shutdownNow();
        }

        assertEquals(0, store.size());
        IllegalStateException mismatch = assertThrows(
                IllegalStateException.class, store::seal);
        assertTrue(mismatch.getMessage().contains("count"),
                mismatch.getMessage());
        columns.symbol(new String[] {"A", "B", "C"});
        columns.payload(new byte[][] {
            new byte[] {1}, new byte[] {2}, new byte[] {3}
        });
        store.seal();
        assertEquals(3, store.size());
    }

    @Test
    void unequalSealPreservesDataAndAllowsCorrectiveFilling() {
        BatchProjectionStore store = BatchProjectionStore.create(0);
        BatchProjectionStore.ColumnAppender columns = store.columnAppender();
        byte[] first = new byte[] {1};
        byte[] second = new byte[] {2};
        byte[] third = new byte[] {3};
        columns.quantity(new int[] {1, 2, 3});
        columns.symbol(new String[] {"A", "B"});
        columns.payload(new byte[][] {first});

        IllegalStateException mismatch = assertThrows(
                IllegalStateException.class, store::seal);
        assertTrue(mismatch.getMessage().contains("payload"),
                mismatch.getMessage());
        assertTrue(mismatch.getMessage().contains("quantity"),
                mismatch.getMessage());
        assertTrue(mismatch.getMessage().contains("1"),
                mismatch.getMessage());
        assertTrue(mismatch.getMessage().contains("3"),
                mismatch.getMessage());
        assertEquals(0, store.size());
        assertThrows(IllegalStateException.class, store::cursor);

        columns.symbol(new String[] {"C"});
        columns.payload(new byte[][] {second, third});
        store.seal();

        assertEquals(3, store.size());
        assertRow(store.viewAt(0), 1, "A", first);
        assertRow(store.viewAt(2), 3, "C", third);
    }

    @Test
    void untouchedAndOneColumnStoresSealNormally() {
        BatchProjectionStore untouched = BatchProjectionStore.create(0);
        untouched.seal();
        assertEquals(0, untouched.size());

        IntProjectionStore singleColumn = IntProjectionStore.create(0);
        IntProjectionStore.ColumnAppender singleColumnAppender =
                singleColumn.columnAppender();
        singleColumnAppender.value(new int[] {1, 2});
        singleColumnAppender.value(new int[] {0, 3, 4}, 1, 3);
        assertEquals(0, singleColumn.size());
        singleColumn.seal();
        assertEquals(4, singleColumn.size());
        assertEquals(1, singleColumn.viewAt(0).value());
        assertEquals(4, singleColumn.viewAt(3).value());
    }

    @Test
    void nullReferenceElementsAndAllNullColumnsPreserveTheirLengths() {
        BatchProjectionStore store = BatchProjectionStore.create(0);
        BatchProjectionStore.ColumnAppender columns = store.columnAppender();
        columns.quantity(new int[] {7, 8, 9});
        columns.symbol(new String[] {null, null, null});
        columns.payload(new byte[][] {null, null, null});
        store.seal();

        assertEquals(3, store.size());
        for (int index = 0; index < store.size(); index++) {
            assertNull(store.viewAt(index).symbol());
            assertNull(store.viewAt(index).payload());
        }
    }

    @Test
    void validationEmptyOverflowAndPostSealCallsAreAtomic()
            throws Exception {
        BatchProjectionStore store = BatchProjectionStore.create(0);
        BatchProjectionStore.ColumnAppender columns = store.columnAppender();
        assertThrows(NullPointerException.class,
                () -> columns.quantity(null));
        assertThrows(NullPointerException.class,
                () -> columns.quantity(null, 0, 0));
        assertThrows(IndexOutOfBoundsException.class,
                () -> columns.quantity(new int[] {1}, -1, 0));
        assertThrows(IndexOutOfBoundsException.class,
                () -> columns.quantity(new int[] {1}, 1, 0));
        assertThrows(IndexOutOfBoundsException.class,
                () -> columns.quantity(new int[] {1}, 0, 2));

        columns.quantity(new int[0]);
        columns.symbol(new String[0], 0, 0);
        columns.payload(new byte[0][]);
        assertEquals(0, store.size());

        Field quantityCount =
                BatchProjection__ColumnarProjectionStore.class
                        .getDeclaredField("column1Count");
        quantityCount.setAccessible(true);
        quantityCount.setInt(store, Integer.MAX_VALUE);
        assertThrows(IllegalStateException.class,
                () -> columns.quantity(new int[] {1}));
        quantityCount.setInt(store, 0);

        columns.quantity(new int[] {1});
        columns.symbol(new String[] {"A"});
        columns.payload(new byte[][] {new byte[] {1}});
        store.seal();

        assertThrows(IllegalStateException.class,
                () -> columns.quantity(new int[] {2}));
        assertThrows(IllegalStateException.class,
                () -> columns.quantity(null));
        assertThrows(IllegalStateException.class,
                () -> columns.quantity(new int[0]));
        assertThrows(IllegalStateException.class,
                () -> columns.quantity(new int[] {2}, -1, 0));
        assertThrows(IllegalStateException.class,
                () -> columns.symbol(new String[] {"B"}, 0, 1));
        assertThrows(IllegalStateException.class,
                () -> columns.payload(new byte[0][], 0, 0));
    }

    @Test
    void perColumnGrowthNeverGrowsAnotherColumn() throws Exception {
        BatchProjectionStore store = BatchProjectionStore.create(0);
        BatchProjectionStore.ColumnAppender columns = store.columnAppender();
        columns.quantity(new int[32]);

        assertTrue(arrayCapacity(store, int[].class) >= 32);
        assertEquals(0, arrayCapacity(store, String[].class));
        assertEquals(0, arrayCapacity(store, byte[][].class));

        int quantityCapacity = arrayCapacity(store, int[].class);
        columns.symbol(new String[] {"A"});
        assertEquals(quantityCapacity, arrayCapacity(store, int[].class));
        assertEquals(0, arrayCapacity(store, byte[][].class));
    }

    @Test
    void positiveRowAndColumnModesCannotMixButNoOpsDoNotSelectMode() {
        BatchProjectionStore rowMode = BatchProjectionStore.create(0);
        BatchProjectionStore.ColumnAppender rowColumns =
                rowMode.columnAppender();
        assertThrows(IndexOutOfBoundsException.class,
                () -> rowColumns.quantity(new int[] {1}, 0, 2));
        rowColumns.quantity(new int[0]);
        rowColumns.symbol(new String[0], 0, 0);
        rowColumns.payload(new byte[0][]);
        rowMode.add(row(1, "A", new byte[] {1}));
        rowColumns.quantity(new int[0]);
        assertThrows(IllegalStateException.class,
                () -> rowColumns.quantity(new int[] {2}));
        rowMode.batch()
                .quantity(new int[] {2})
                .symbol(new String[] {"B"})
                .payload(new byte[][] {new byte[] {2}})
                .append();
        rowMode.seal();
        assertEquals(2, rowMode.size());

        BatchProjectionStore columnMode = BatchProjectionStore.create(0);
        BatchProjectionStore.ColumnAppender columnColumns =
                columnMode.columnAppender();
        assertThrows(IndexOutOfBoundsException.class,
                () -> columnColumns.quantity(new int[] {1}, 0, 2));
        columnMode.batch(0, 0).append();
        columnColumns.quantity(new int[] {3});
        assertThrows(IllegalStateException.class,
                () -> columnMode.add(row(4, "D", new byte[] {4})));
        BatchProjectionStore.Batch positiveBatch = columnMode.batch()
                .quantity(new int[] {4})
                .symbol(new String[] {"D"})
                .payload(new byte[][] {new byte[] {4}});
        assertThrows(IllegalStateException.class, positiveBatch::append);
        columnMode.batch(0, 0).append();
        columnColumns.symbol(new String[] {"C"});
        columnColumns.payload(new byte[][] {new byte[] {3}});
        columnMode.seal();
        assertEquals(1, columnMode.size());
    }

    @Test
    void failedRowEvaluationAndBatchPreflightLeaveModeUnset() {
        BatchProjectionStore failedRow = BatchProjectionStore.create(0);
        assertThrows(IllegalArgumentException.class,
                () -> failedRow.add(new BatchProjection() {
                    @Override
                    public int quantity() {
                        throw new IllegalArgumentException("quantity");
                    }

                    @Override
                    public String symbol() {
                        return "unused";
                    }

                    @Override
                    public byte[] payload() {
                        return new byte[] {0};
                    }
                }));
        BatchProjectionStore.ColumnAppender afterFailedRow =
                failedRow.columnAppender();
        afterFailedRow.quantity(new int[] {1});
        afterFailedRow.symbol(new String[] {"A"});
        afterFailedRow.payload(new byte[][] {new byte[] {1}});
        failedRow.seal();
        assertEquals(1, failedRow.size());

        BatchProjectionStore failedBatch = BatchProjectionStore.create(0);
        BatchProjectionStore.Batch incomplete = failedBatch.batch()
                .quantity(new int[] {2});
        assertThrows(IllegalStateException.class, incomplete::append);
        BatchProjectionStore.ColumnAppender afterFailedBatch =
                failedBatch.columnAppender();
        afterFailedBatch.quantity(new int[] {2});
        afterFailedBatch.symbol(new String[] {"B"});
        afterFailedBatch.payload(new byte[][] {new byte[] {2}});
        failedBatch.seal();
        assertEquals(1, failedBatch.size());
    }

    @Test
    void accessorNamesDoNotChangeStoreOrObjectMethodResolution() {
        StoreMethodNameProjectionStore store =
                StoreMethodNameProjectionStore.create(0);

        assertThrows(NullPointerException.class, () -> store.add(null));
        assertTrue(store.equals(store));
        assertFalse(store.equals(null));

        StoreMethodNameProjectionStore.ColumnAppender columns =
                store.columnAppender();
        columns.add(new String[] {"value"});
        columns.equals(new int[] {7});
        store.seal();

        assertEquals("value", store.viewAt(0).add());
        assertEquals(7, store.viewAt(0).equals());
    }

    private static void assertGeneratedStoreContainsNoCoordinationApi(
            Class<?> storeType) {
        List<Class<?>> exposedTypes = new ArrayList<Class<?>>();
        for (Constructor<?> constructor : storeType.getDeclaredConstructors()) {
            addAll(exposedTypes, constructor.getParameterTypes());
        }
        for (Field field : storeType.getDeclaredFields()) {
            exposedTypes.add(field.getType());
        }
        for (Method method : storeType.getDeclaredMethods()) {
            exposedTypes.add(method.getReturnType());
            addAll(exposedTypes, method.getParameterTypes());
        }
        String[] forbidden = new String[] {
            "Executor", "Future", "CompletionStage", "CountDownLatch",
            "Phaser", "CyclicBarrier"
        };
        for (Class<?> type : exposedTypes) {
            for (String fragment : forbidden) {
                assertFalse(type.getName().contains(fragment), type.getName());
            }
        }
    }

    private static void addAll(List<Class<?>> destination, Class<?>[] values) {
        for (Class<?> value : values) {
            destination.add(value);
        }
    }

    private static int arrayCapacity(Object store, Class<?> arrayType)
            throws Exception {
        for (Field field : store.getClass().getDeclaredFields()) {
            if (field.getType() == arrayType) {
                field.setAccessible(true);
                return java.lang.reflect.Array.getLength(field.get(store));
            }
        }
        throw new AssertionError("No field of type " + arrayType.getName());
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new AssertionError(exception);
        }
    }

    private static BatchProjection row(
            final int quantity, final String symbol, final byte[] payload) {
        return new BatchProjection() {
            @Override
            public int quantity() {
                return quantity;
            }

            @Override
            public String symbol() {
                return symbol;
            }

            @Override
            public byte[] payload() {
                return payload;
            }
        };
    }

    private static void assertRow(
            BatchProjection row,
            int quantity,
            String symbol,
            byte[] payload) {
        assertEquals(quantity, row.quantity());
        assertEquals(symbol, row.symbol());
        assertSame(payload, row.payload());
    }
}

@ProjectionSchema
interface StoreMethodNameProjection {
    String add();

    int equals();
}
