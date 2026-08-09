package io.github.jutil.columnarprojection;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Array;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import org.junit.jupiter.api.Test;

class BatchAppendRuntimeTest {

    @Test
    void generatedApiIsPublicTypedFluentAndStoreSpecific()
            throws Exception {
        Class<BatchProjection__ColumnarProjectionStore> storeType =
                BatchProjection__ColumnarProjectionStore.class;
        Class<?> batchType =
                BatchProjection__ColumnarProjectionStore.Batch.class;

        assertTrue(Modifier.isPublic(batchType.getModifiers()));
        assertTrue(Modifier.isFinal(batchType.getModifiers()));
        Constructor<?>[] constructors = batchType.getDeclaredConstructors();
        boolean hasPrivateConstructor = false;
        for (Constructor<?> constructor : constructors) {
            assertFalse(Modifier.isPublic(constructor.getModifiers()));
            hasPrivateConstructor |= Modifier.isPrivate(
                    constructor.getModifiers());
        }
        assertTrue(hasPrivateConstructor);

        Method batch = storeType.getMethod("batch", Integer.TYPE);
        assertSame(batchType, batch.getReturnType());
        assertSame(batchType, batchType.getMethod(
                "quantity", int[].class).getReturnType());
        assertSame(batchType, batchType.getMethod(
                "symbol", String[].class).getReturnType());
        assertSame(batchType, batchType.getMethod(
                "payload", byte[][].class).getReturnType());
        assertThrows(NoSuchMethodException.class, () -> batchType.getMethod(
                "quantity", int[].class, Integer.TYPE));
        for (Field field : batchType.getDeclaredFields()) {
            assertFalse(field.getName().contains("sourceOffset"));
        }
        assertSame(Void.TYPE, batchType.getMethod("append").getReturnType());

        boolean genericInterfaceHasBatch = false;
        for (Method method : ProjectionStore.class.getMethods()) {
            genericInterfaceHasBatch |= method.getName().equals("batch");
        }
        assertFalse(genericInterfaceHasBatch);
    }

    @Test
    void appendsEveryPrimitiveAndReferenceCategoryAndIgnoresTrailingValues() {
        AllValuesProjection__ColumnarProjectionStore store =
                new AllValuesProjection__ColumnarProjectionStore(0);
        Object firstObject = new Object();
        int[] firstPrimitiveArray = new int[] {10, 20};
        String[] firstReferenceArray = new String[] {"nested", null};

        store.batch(2)
                .booleanValue(new boolean[] {true, false, true})
                .byteValue(new byte[] {3, 4, 99})
                .shortValue(new short[] {5, 6, 98})
                .intValue(new int[] {7, 8, 97})
                .longValue(new long[] {9L, 10L, 96L})
                .charValue(new char[] {'a', 'b', 'x'})
                .floatValue(new float[] {1.5F, 2.5F, 95.0F})
                .doubleValue(new double[] {3.5D, 4.5D, 94.0D})
                .textValue(new String[] {null, "text", "ignored"})
                .objectValue(new Object[] {firstObject, null, "ignored"})
                .primitiveArrayValue(
                        new int[][] {
                            firstPrimitiveArray, null, new int[] {99}
                        })
                .referenceArrayValue(
                        new String[][] {
                            firstReferenceArray, null, new String[] {"ignored"}
                        })
                .append();

        assertEquals(2, store.size());
        store.seal();

        AllValuesProjection first = store.viewAt(0);
        assertTrue(first.booleanValue());
        assertEquals((byte) 3, first.byteValue());
        assertEquals((short) 5, first.shortValue());
        assertEquals(7, first.intValue());
        assertEquals(9L, first.longValue());
        assertEquals('a', first.charValue());
        assertEquals(1.5F, first.floatValue());
        assertEquals(3.5D, first.doubleValue());
        assertNull(first.textValue());
        assertSame(firstObject, first.objectValue());
        assertSame(firstPrimitiveArray, first.primitiveArrayValue());
        assertSame(firstReferenceArray, first.referenceArrayValue());

        AllValuesProjection second = store.viewAt(1);
        assertFalse(second.booleanValue());
        assertEquals((byte) 4, second.byteValue());
        assertEquals((short) 6, second.shortValue());
        assertEquals(8, second.intValue());
        assertEquals(10L, second.longValue());
        assertEquals('b', second.charValue());
        assertEquals(2.5F, second.floatValue());
        assertEquals(4.5D, second.doubleValue());
        assertEquals("text", second.textValue());
        assertNull(second.objectValue());
        assertNull(second.primitiveArrayValue());
        assertNull(second.referenceArrayValue());
    }

    @Test
    void appendsWholeArraysAndGrowsCapacityOnceForTheBatch()
            throws Exception {
        BatchProjection__ColumnarProjectionStore store =
                new BatchProjection__ColumnarProjectionStore(1);
        byte[] firstPayload = new byte[] {1};
        byte[] secondPayload = new byte[] {2};

        store.batch(2)
                .quantity(new int[] {11, 22})
                .symbol(new String[] {"first", "second"})
                .payload(new byte[][] {firstPayload, secondPayload})
                .append();

        assertEquals(2, store.size());
        assertEquals(2, generatedCapacity(store));
        store.seal();
        assertBatchRow(store.viewAt(0), 11, "first", firstPayload);
        assertBatchRow(store.viewAt(1), 22, "second", secondPayload);
    }

    @Test
    void unfinishedBatchesAppendAtExecutionTimeAndMixWithAdd() {
        BatchProjection__ColumnarProjectionStore store =
                new BatchProjection__ColumnarProjectionStore(1);
        byte[] earlyPayload = new byte[] {1};
        byte[] laterFirstPayload = new byte[] {2};
        byte[] laterSecondPayload = new byte[] {3};
        byte[] addedPayload = new byte[] {4};

        BatchProjection__ColumnarProjectionStore.Batch early = store.batch(1)
                .quantity(new int[] {40})
                .symbol(new String[] {"early"})
                .payload(new byte[][] {earlyPayload});
        BatchProjection__ColumnarProjectionStore.Batch later = store.batch(2)
                .quantity(new int[] {20, 30})
                .symbol(new String[] {"later-1", "later-2"})
                .payload(new byte[][] {
                    laterFirstPayload, laterSecondPayload
                });

        later.append();
        store.add(batchProjection(35, "added", addedPayload));
        early.append();

        assertEquals(4, store.size());
        store.seal();
        assertBatchRow(store.viewAt(0), 20, "later-1", laterFirstPayload);
        assertBatchRow(store.viewAt(1), 30, "later-2", laterSecondPayload);
        assertBatchRow(store.viewAt(2), 35, "added", addedPayload);
        assertBatchRow(store.viewAt(3), 40, "early", earlyPayload);
    }

    @Test
    void retainsSourcesUntilAppendCopiesThemAndThenReleasesThem()
            throws Exception {
        BatchProjection__ColumnarProjectionStore store =
                new BatchProjection__ColumnarProjectionStore(0);
        int[] quantities = new int[] {1, 2};
        String[] symbols = new String[] {"one", "two"};
        byte[] originalPayload = new byte[] {1};
        byte[] replacementPayload = new byte[] {2};
        byte[][] payloads = new byte[][] {originalPayload, null};
        BatchProjection__ColumnarProjectionStore.Batch batch = store.batch(2)
                .quantity(quantities)
                .symbol(symbols)
                .payload(payloads);

        quantities[0] = 10;
        symbols[0] = "before-append";
        payloads[0] = replacementPayload;
        int[] quantitiesAtAppend = quantities.clone();
        String[] symbolsAtAppend = symbols.clone();
        byte[][] payloadsAtAppend = payloads.clone();
        batch.append();

        assertArrayEquals(quantitiesAtAppend, quantities);
        assertArrayEquals(symbolsAtAppend, symbols);
        assertArrayEquals(payloadsAtAppend, payloads);
        assertBatchSourceReferencesCleared(batch);

        quantities[0] = 100;
        symbols[0] = "after-append";
        payloads[0] = new byte[] {3};
        replacementPayload[0] = 22;

        store.seal();
        assertEquals(10, store.viewAt(0).quantity());
        assertEquals("before-append", store.viewAt(0).symbol());
        assertSame(replacementPayload, store.viewAt(0).payload());
        assertEquals((byte) 22, store.viewAt(0).payload()[0]);
    }

    @Test
    void zeroRowsNeedNoColumnsAndAcceptEmptyOrNonEmptyArrays() {
        BatchProjection__ColumnarProjectionStore store =
                new BatchProjection__ColumnarProjectionStore(0);

        BatchProjection__ColumnarProjectionStore.Batch empty =
                store.batch(0);
        empty.append();
        assertEquals(0, store.size());
        assertThrows(IllegalStateException.class, empty::append);

        store.batch(0)
                .quantity(new int[0])
                .symbol(new String[0])
                .payload(new byte[0][])
                .append();
        store.batch(0)
                .quantity(new int[] {1})
                .symbol(new String[] {"one"})
                .payload(new byte[][] {new byte[] {1}})
                .append();

        assertEquals(0, store.size());
        assertThrows(IllegalArgumentException.class, () -> store.batch(-1));
    }

    @Test
    void invalidColumnCallsLeaveTheColumnAvailableForCorrection() {
        BatchProjection__ColumnarProjectionStore store =
                new BatchProjection__ColumnarProjectionStore(0);
        BatchProjection__ColumnarProjectionStore.Batch batch = store.batch(2);

        assertThrows(NullPointerException.class,
                () -> batch.quantity(null));
        assertThrows(IndexOutOfBoundsException.class,
                () -> batch.quantity(new int[0]));
        assertThrows(IndexOutOfBoundsException.class,
                () -> batch.quantity(new int[] {1}));
        batch.quantity(new int[] {3, 4});

        assertThrows(NullPointerException.class,
                () -> batch.symbol(null));
        batch.symbol(new String[] {"three", "four"});
        batch.payload(new byte[][] {new byte[] {3}, new byte[] {4}});
        batch.append();

        assertEquals(2, store.size());
    }

    @Test
    void missingAndDuplicateColumnsCanBeCorrectedBeforeRetry() {
        BatchProjection__ColumnarProjectionStore store =
                new BatchProjection__ColumnarProjectionStore(0);
        int[] quantities = new int[] {7};
        BatchProjection__ColumnarProjectionStore.Batch batch = store.batch(1);

        assertSame(batch, batch.quantity(quantities));
        assertThrows(IllegalStateException.class,
                () -> batch.quantity(new int[] {8}));
        assertThrows(IllegalStateException.class, batch::append);
        assertEquals(0, store.size());

        batch.symbol(new String[] {"seven"});
        assertThrows(IllegalStateException.class, batch::append);
        assertEquals(0, store.size());

        byte[] payload = new byte[] {7};
        batch.payload(new byte[][] {payload});
        batch.append();

        assertEquals(1, store.size());
        assertThrows(IllegalStateException.class,
                () -> batch.symbol(new String[] {"again"}));
        assertThrows(IllegalStateException.class, batch::append);
        store.seal();
        assertBatchRow(store.viewAt(0), 7, "seven", payload);
    }

    @Test
    void validationFailuresPreserveExistingLogicalRows() {
        BatchProjection__ColumnarProjectionStore store =
                new BatchProjection__ColumnarProjectionStore(1);
        byte[] existingPayload = new byte[] {5};
        store.add(batchProjection(5, "existing", existingPayload));
        BatchProjection__ColumnarProjectionStore.Batch batch = store.batch(2);

        assertThrows(IndexOutOfBoundsException.class,
                () -> batch.quantity(new int[] {6}));
        assertEquals(1, store.size());
        assertThrows(IllegalStateException.class, batch::append);
        assertEquals(1, store.size());

        batch.quantity(new int[] {6, 7})
                .symbol(new String[] {"six", "seven"})
                .payload(new byte[][] {
                    new byte[] {6}, new byte[] {7}
                })
                .append();

        assertEquals(3, store.size());
        store.seal();
        assertBatchRow(store.viewAt(0), 5, "existing", existingPayload);
        assertEquals(6, store.viewAt(1).quantity());
        assertEquals(7, store.viewAt(2).quantity());
    }

    @Test
    void sealingRejectsBatchCreationAndAnUnfinishedBatchAppend()
            throws Exception {
        BatchProjection__ColumnarProjectionStore store =
                new BatchProjection__ColumnarProjectionStore(2);
        byte[] existingPayload = new byte[] {1};
        store.add(batchProjection(1, "existing", existingPayload));
        BatchProjection__ColumnarProjectionStore.Batch batch = store.batch(1)
                .quantity(new int[] {2})
                .symbol(new String[] {"not-copied"})
                .payload(new byte[][] {new byte[] {2}});

        store.seal();

        assertThrows(IllegalStateException.class, batch::append);
        assertThrows(IllegalStateException.class, () -> store.batch(0));
        assertThrows(IllegalStateException.class, () -> store.batch(-1));
        assertEquals(1, store.size());
        assertColumnSlotIsDefault(store, 1);
        assertBatchRow(store.viewAt(0), 1, "existing", existingPayload);
    }

    private static BatchProjection batchProjection(
            final int quantity,
            final String symbol,
            final byte[] payload) {
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

    private static void assertBatchRow(
            BatchProjection row,
            int expectedQuantity,
            String expectedSymbol,
            byte[] expectedPayload) {
        assertEquals(expectedQuantity, row.quantity());
        assertEquals(expectedSymbol, row.symbol());
        assertSame(expectedPayload, row.payload());
    }

    private static int generatedCapacity(Object store) throws Exception {
        Field capacity = store.getClass().getDeclaredField("capacity");
        capacity.setAccessible(true);
        return capacity.getInt(store);
    }

    private static void assertBatchSourceReferencesCleared(Object batch)
            throws Exception {
        int sourceCount = 0;
        for (Field field : batch.getClass().getDeclaredFields()) {
            if (field.getName().startsWith("source")) {
                field.setAccessible(true);
                assertNull(field.get(batch), field.getName());
                sourceCount++;
            }
        }
        assertEquals(3, sourceCount);
    }

    private static void assertColumnSlotIsDefault(Object store, int index)
            throws Exception {
        int columnCount = 0;
        for (Field field : store.getClass().getDeclaredFields()) {
            if (!field.getName().startsWith("column")) {
                continue;
            }
            field.setAccessible(true);
            Object value = Array.get(field.get(store), index);
            if (field.getType().getComponentType().isPrimitive()) {
                assertEquals(Integer.valueOf(0), value, field.getName());
            } else {
                assertNull(value, field.getName());
            }
            columnCount++;
        }
        assertEquals(3, columnCount);
    }
}

@ProjectionSchema
interface BatchProjection {
    int quantity();

    String symbol();

    byte[] payload();
}
