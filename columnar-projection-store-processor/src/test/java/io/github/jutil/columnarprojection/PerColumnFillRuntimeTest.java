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
    void generatedContractHasWholeArrayAndRangeMethodsOnlyOnTypedStore()
            throws Exception {
        Class<?> contract = BatchProjectionStore.class;

        assertEquals(Void.TYPE, contract.getMethod(
                "quantity", int[].class).getReturnType());
        assertEquals(Void.TYPE, contract.getMethod(
                "quantity", int[].class, Integer.TYPE, Integer.TYPE)
                .getReturnType());
        assertEquals(Void.TYPE, contract.getMethod(
                "symbol", String[].class).getReturnType());
        assertEquals(Void.TYPE, contract.getMethod(
                "symbol", String[].class, Integer.TYPE, Integer.TYPE)
                .getReturnType());
        assertEquals(Void.TYPE, contract.getMethod(
                "payload", byte[][].class).getReturnType());
        assertEquals(Void.TYPE, contract.getMethod(
                "payload", byte[][].class, Integer.TYPE, Integer.TYPE)
                .getReturnType());
        assertThrows(NoSuchMethodException.class,
                () -> contract.getMethod("quantity", int[].class,
                        Integer.TYPE));
        assertThrows(NoSuchMethodException.class,
                () -> ProjectionStore.class.getMethod(
                        "quantity", int[].class));

        Constructor<?>[] constructors =
                BatchProjection__ColumnarProjectionStore.class
                        .getDeclaredConstructors();
        assertEquals(1, constructors.length);
        assertEquals(1, constructors[0].getParameterTypes().length);
        assertSame(Integer.TYPE, constructors[0].getParameterTypes()[0]);
        assertGeneratedStoreContainsNoCoordinationApi(
                BatchProjection__ColumnarProjectionStore.class);
    }

    @Test
    void repeatedChunksWithDifferentBoundariesAlignAtSeal() {
        BatchProjectionStore store = BatchProjectionStore.create(1);
        byte[] payload0 = new byte[] {0};
        byte[] payload1 = new byte[] {1};
        byte[] payload2 = new byte[] {2};
        byte[] payload3 = new byte[] {3};

        int[] firstQuantities = new int[] {10, 11, 99};
        store.quantity(firstQuantities, 0, 2);
        firstQuantities[0] = -1;
        store.quantity(new int[] {0, 12, 13}, 1, 3);

        String[] firstSymbols = new String[] {"A"};
        store.symbol(firstSymbols);
        firstSymbols[0] = "changed";
        store.symbol(new String[] {"ignored", "B", "C", "D"}, 1, 4);

        byte[][] firstPayloads =
                new byte[][] {payload0, payload1, payload2};
        store.payload(firstPayloads);
        firstPayloads[0] = new byte[] {99};
        store.payload(new byte[][] {payload3});

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
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService workers = Executors.newFixedThreadPool(3);
        try {
            Future<?> quantities = workers.submit(() -> {
                await(start);
                for (int index = 0; index < rowCount; index++) {
                    store.quantity(new int[] {index});
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
                    store.symbol(chunk);
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
                    store.payload(chunk);
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
        ExecutorService worker = Executors.newSingleThreadExecutor();
        try {
            Future<?> completed = worker.submit(
                    () -> store.quantity(new int[] {1, 2, 3}));
            completed.get(2, TimeUnit.SECONDS);
        } finally {
            worker.shutdownNow();
        }

        assertEquals(0, store.size());
        IllegalStateException mismatch = assertThrows(
                IllegalStateException.class, store::seal);
        assertTrue(mismatch.getMessage().contains("count"),
                mismatch.getMessage());
        store.symbol(new String[] {"A", "B", "C"});
        store.payload(new byte[][] {
            new byte[] {1}, new byte[] {2}, new byte[] {3}
        });
        store.seal();
        assertEquals(3, store.size());
    }

    @Test
    void unequalSealPreservesDataAndAllowsCorrectiveFilling() {
        BatchProjectionStore store = BatchProjectionStore.create(0);
        byte[] first = new byte[] {1};
        byte[] second = new byte[] {2};
        byte[] third = new byte[] {3};
        store.quantity(new int[] {1, 2, 3});
        store.symbol(new String[] {"A", "B"});
        store.payload(new byte[][] {first});

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

        store.symbol(new String[] {"C"});
        store.payload(new byte[][] {second, third});
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
        singleColumn.value(new int[] {1, 2});
        singleColumn.value(new int[] {0, 3, 4}, 1, 3);
        assertEquals(0, singleColumn.size());
        singleColumn.seal();
        assertEquals(4, singleColumn.size());
        assertEquals(1, singleColumn.viewAt(0).value());
        assertEquals(4, singleColumn.viewAt(3).value());
    }

    @Test
    void nullReferenceElementsAndAllNullColumnsPreserveTheirLengths() {
        BatchProjectionStore store = BatchProjectionStore.create(0);
        store.quantity(new int[] {7, 8, 9});
        store.symbol(new String[] {null, null, null});
        store.payload(new byte[][] {null, null, null});
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
        assertThrows(NullPointerException.class,
                () -> store.quantity(null));
        assertThrows(NullPointerException.class,
                () -> store.quantity(null, 0, 0));
        assertThrows(IndexOutOfBoundsException.class,
                () -> store.quantity(new int[] {1}, -1, 0));
        assertThrows(IndexOutOfBoundsException.class,
                () -> store.quantity(new int[] {1}, 1, 0));
        assertThrows(IndexOutOfBoundsException.class,
                () -> store.quantity(new int[] {1}, 0, 2));

        store.quantity(new int[0]);
        store.symbol(new String[0], 0, 0);
        store.payload(new byte[0][]);
        assertEquals(0, store.size());

        Field quantityCount =
                BatchProjection__ColumnarProjectionStore.class
                        .getDeclaredField("column1Count");
        quantityCount.setAccessible(true);
        quantityCount.setInt(store, Integer.MAX_VALUE);
        assertThrows(IllegalStateException.class,
                () -> store.quantity(new int[] {1}));
        quantityCount.setInt(store, 0);

        store.quantity(new int[] {1});
        store.symbol(new String[] {"A"});
        store.payload(new byte[][] {new byte[] {1}});
        store.seal();

        assertThrows(IllegalStateException.class,
                () -> store.quantity(new int[] {2}));
        assertThrows(IllegalStateException.class,
                () -> store.quantity(null));
        assertThrows(IllegalStateException.class,
                () -> store.quantity(new int[0]));
        assertThrows(IllegalStateException.class,
                () -> store.quantity(new int[] {2}, -1, 0));
        assertThrows(IllegalStateException.class,
                () -> store.symbol(new String[] {"B"}, 0, 1));
        assertThrows(IllegalStateException.class,
                () -> store.payload(new byte[0][], 0, 0));
    }

    @Test
    void perColumnGrowthNeverGrowsAnotherColumn() throws Exception {
        BatchProjectionStore store = BatchProjectionStore.create(0);
        store.quantity(new int[32]);

        assertTrue(arrayCapacity(store, int[].class) >= 32);
        assertEquals(0, arrayCapacity(store, String[].class));
        assertEquals(0, arrayCapacity(store, byte[][].class));

        int quantityCapacity = arrayCapacity(store, int[].class);
        store.symbol(new String[] {"A"});
        assertEquals(quantityCapacity, arrayCapacity(store, int[].class));
        assertEquals(0, arrayCapacity(store, byte[][].class));
    }

    @Test
    void positiveRowAndColumnModesCannotMixButNoOpsDoNotSelectMode() {
        BatchProjectionStore rowMode = BatchProjectionStore.create(0);
        rowMode.quantity(new int[0]);
        rowMode.symbol(new String[0], 0, 0);
        rowMode.payload(new byte[0][]);
        rowMode.add(row(1, "A", new byte[] {1}));
        rowMode.quantity(new int[0]);
        assertThrows(IllegalStateException.class,
                () -> rowMode.quantity(new int[] {2}));
        rowMode.batch()
                .quantity(new int[] {2})
                .symbol(new String[] {"B"})
                .payload(new byte[][] {new byte[] {2}})
                .append();
        rowMode.seal();
        assertEquals(2, rowMode.size());

        BatchProjectionStore columnMode = BatchProjectionStore.create(0);
        assertThrows(IndexOutOfBoundsException.class,
                () -> columnMode.quantity(new int[] {1}, 0, 2));
        columnMode.batch(0, 0).append();
        columnMode.quantity(new int[] {3});
        assertThrows(IllegalStateException.class,
                () -> columnMode.add(row(4, "D", new byte[] {4})));
        BatchProjectionStore.Batch positiveBatch = columnMode.batch()
                .quantity(new int[] {4})
                .symbol(new String[] {"D"})
                .payload(new byte[][] {new byte[] {4}});
        assertThrows(IllegalStateException.class, positiveBatch::append);
        columnMode.batch(0, 0).append();
        columnMode.symbol(new String[] {"C"});
        columnMode.payload(new byte[][] {new byte[] {3}});
        columnMode.seal();
        assertEquals(1, columnMode.size());
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
