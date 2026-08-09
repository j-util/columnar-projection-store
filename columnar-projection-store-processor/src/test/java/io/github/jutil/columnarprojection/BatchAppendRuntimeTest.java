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
import java.util.ArrayList;
import java.util.LinkedHashMap;
import org.junit.jupiter.api.Test;

class BatchAppendRuntimeTest {

    @Test
    void generatedApiHasOnlyWholeArrayAndCommonRangeFactories()
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

        Method wholeArrayBatch = storeType.getMethod("batch");
        Method rangeBatch = storeType.getMethod(
                "batch", Integer.TYPE, Integer.TYPE);
        assertSame(batchType, wholeArrayBatch.getReturnType());
        assertSame(batchType, rangeBatch.getReturnType());
        assertThrows(NoSuchMethodException.class,
                () -> storeType.getMethod("batch", Integer.TYPE));
        assertSame(batchType, batchType.getMethod(
                "quantity", int[].class).getReturnType());
        assertSame(batchType, batchType.getMethod(
                "symbol", String[].class).getReturnType());
        assertSame(batchType, batchType.getMethod(
                "payload", byte[][].class).getReturnType());
        assertThrows(NoSuchMethodException.class, () -> batchType.getMethod(
                "quantity", int[].class, Integer.TYPE));
        assertSame(Void.TYPE, batchType.getMethod("append").getReturnType());

        int generatedBatchMethods = 0;
        for (Method method : storeType.getMethods()) {
            if (method.getName().equals("batch")) {
                generatedBatchMethods++;
            }
        }
        assertEquals(2, generatedBatchMethods);

        boolean genericInterfaceHasBatch = false;
        for (Method method : ProjectionStore.class.getMethods()) {
            genericInterfaceHasBatch |= method.getName().equals("batch");
        }
        assertFalse(genericInterfaceHasBatch);
    }

    @Test
    void wholeArraysUseTheFirstAcceptedLengthInAnyColumnOrder()
            throws Exception {
        BatchProjection__ColumnarProjectionStore store =
                new BatchProjection__ColumnarProjectionStore(1);
        byte[] firstPayload = new byte[] {1};
        byte[] secondPayload = new byte[] {2};
        byte[] thirdPayload = new byte[] {3};

        store.batch()
                .symbol(new String[] {"first", "second"})
                .payload(new byte[][] {firstPayload, secondPayload})
                .quantity(new int[] {11, 22})
                .append();
        store.batch()
                .payload(new byte[][] {thirdPayload})
                .quantity(new int[] {33})
                .symbol(new String[] {"third"})
                .append();

        assertEquals(3, store.size());
        assertEquals(4, generatedCapacity(store));
        store.seal();
        assertBatchRow(store.viewAt(0), 11, "first", firstPayload);
        assertBatchRow(store.viewAt(1), 22, "second", secondPayload);
        assertBatchRow(store.viewAt(2), 33, "third", thirdPayload);
    }

    @Test
    void unequalWholeArrayLengthsLeaveColumnsAvailableForCorrection() {
        BatchProjection__ColumnarProjectionStore store =
                new BatchProjection__ColumnarProjectionStore(0);
        BatchProjection__ColumnarProjectionStore.Batch batch = store.batch();

        assertThrows(NullPointerException.class,
                () -> batch.quantity(null));
        assertSame(batch, batch.symbol(new String[] {"three", "four"}));
        assertThrows(IllegalArgumentException.class,
                () -> batch.payload(new byte[][] {new byte[] {3}}));
        assertThrows(IllegalStateException.class, batch::append);
        assertEquals(0, store.size());

        batch.payload(new byte[][] {new byte[] {3}, new byte[] {4}});
        assertThrows(IllegalArgumentException.class,
                () -> batch.quantity(new int[] {3, 4, 5}));
        batch.quantity(new int[] {3, 4});
        assertThrows(IllegalStateException.class,
                () -> batch.symbol(new String[] {"again", "again"}));
        batch.append();

        assertEquals(2, store.size());
        assertThrows(IllegalStateException.class, batch::append);
        assertThrows(IllegalStateException.class,
                () -> batch.quantity(new int[] {3, 4}));
    }

    @Test
    void wholeArrayAppendWithoutColumnsDoesNotCopyAndCanBeRetried()
            throws Exception {
        BatchProjection__ColumnarProjectionStore store =
                new BatchProjection__ColumnarProjectionStore(1);
        BatchProjection__ColumnarProjectionStore.Batch batch = store.batch();

        assertThrows(IllegalStateException.class, batch::append);
        assertEquals(0, store.size());

        batch.quantity(new int[] {7});
        assertThrows(IllegalStateException.class, batch::append);
        assertColumnSlotIsDefault(store, 0);
        batch.symbol(new String[] {"seven"});
        assertThrows(IllegalStateException.class, batch::append);
        byte[] payload = new byte[] {7};
        batch.payload(new byte[][] {payload});
        batch.append();

        assertEquals(1, store.size());
        store.seal();
        assertBatchRow(store.viewAt(0), 7, "seven", payload);
    }

    @Test
    void emptyWholeArraysRequireEveryColumn() {
        BatchProjection__ColumnarProjectionStore store =
                new BatchProjection__ColumnarProjectionStore(0);
        BatchProjection__ColumnarProjectionStore.Batch empty = store.batch();

        empty.payload(new byte[0][]);
        assertThrows(IllegalArgumentException.class,
                () -> empty.quantity(new int[] {1}));
        empty.quantity(new int[0]);
        assertThrows(IllegalStateException.class, empty::append);
        empty.symbol(new String[0]);
        empty.append();

        assertEquals(0, store.size());
        assertThrows(IllegalStateException.class, empty::append);
    }

    @Test
    void commonRangesCopyPrefixesMiddlesSuffixesAndFullArrays() {
        BatchProjection__ColumnarProjectionStore store =
                new BatchProjection__ColumnarProjectionStore(0);
        int[] quantities = new int[] {10, 11, 12, 13, 14};
        String[] symbols =
                new String[] {"s0", "s1", "s2", "s3", "s4"};
        byte[][] payloads = new byte[][] {
            new byte[] {0}, new byte[] {1}, new byte[] {2},
            new byte[] {3}, new byte[] {4}
        };

        appendRange(store, 0, 2, quantities, symbols, payloads);
        appendRange(store, 1, 4,
                new int[] {20, 21, 22, 23},
                new String[] {"m0", "m1", "m2", "m3", "m4"},
                new byte[][] {
                    new byte[] {20}, new byte[] {21}, new byte[] {22},
                    new byte[] {23}, new byte[] {24}, new byte[] {25}
                });
        appendRange(store, 3, 5, quantities, symbols, payloads);
        appendRange(store, 0, 5, quantities, symbols, payloads);

        assertEquals(12, store.size());
        store.seal();
        assertBatchRow(store.viewAt(0), 10, "s0", payloads[0]);
        assertBatchRow(store.viewAt(1), 11, "s1", payloads[1]);
        assertEquals(21, store.viewAt(2).quantity());
        assertEquals(22, store.viewAt(3).quantity());
        assertEquals(23, store.viewAt(4).quantity());
        assertBatchRow(store.viewAt(5), 13, "s3", payloads[3]);
        assertBatchRow(store.viewAt(6), 14, "s4", payloads[4]);
        for (int index = 0; index < quantities.length; index++) {
            assertBatchRow(store.viewAt(index + 7), quantities[index],
                    symbols[index], payloads[index]);
        }
    }

    @Test
    void invalidRangesAndShortSourcesUseIndexErrorsAndAllowCorrection() {
        BatchProjection__ColumnarProjectionStore store =
                new BatchProjection__ColumnarProjectionStore(0);

        assertThrows(IndexOutOfBoundsException.class,
                () -> store.batch(-1, 0));
        assertThrows(IndexOutOfBoundsException.class,
                () -> store.batch(2, 1));
        assertThrows(IndexOutOfBoundsException.class,
                () -> store.batch(0, -1));

        BatchProjection__ColumnarProjectionStore.Batch batch =
                store.batch(1, 3);
        assertThrows(NullPointerException.class,
                () -> batch.quantity(null));
        assertThrows(IndexOutOfBoundsException.class,
                () -> batch.quantity(new int[] {0, 1}));
        assertThrows(IndexOutOfBoundsException.class,
                () -> batch.symbol(new String[] {"zero", "one"}));
        assertThrows(IndexOutOfBoundsException.class,
                () -> batch.payload(new byte[][] {
                    new byte[] {0}, new byte[] {1}
                }));
        assertThrows(IllegalStateException.class, batch::append);
        assertEquals(0, store.size());

        byte[] firstPayload = new byte[] {1};
        byte[] secondPayload = new byte[] {2};
        batch.quantity(new int[] {0, 1, 2})
                .symbol(new String[] {"zero", "one", "two", "extra"})
                .payload(new byte[][] {
                    new byte[] {0}, firstPayload, secondPayload,
                    new byte[] {3}, new byte[] {4}
                })
                .append();

        assertEquals(2, store.size());
        store.seal();
        assertBatchRow(store.viewAt(0), 1, "one", firstPayload);
        assertBatchRow(store.viewAt(1), 2, "two", secondPayload);
    }

    @Test
    void emptyExplicitRangesNeedNoColumnsAndAreOneShot() {
        BatchProjection__ColumnarProjectionStore store =
                new BatchProjection__ColumnarProjectionStore(0);
        BatchProjection__ColumnarProjectionStore.Batch empty =
                store.batch(4, 4);

        assertThrows(IndexOutOfBoundsException.class,
                () -> empty.quantity(new int[3]));
        empty.append();

        assertEquals(0, store.size());
        assertThrows(IllegalStateException.class, empty::append);

        store.batch(0, 0)
                .quantity(new int[0])
                .append();
        assertEquals(0, store.size());
    }

    @Test
    void unfinishedWholeAndRangeBatchesAppendAtExecutionTimeWithAdd() {
        BatchProjection__ColumnarProjectionStore store =
                new BatchProjection__ColumnarProjectionStore(1);
        byte[] earlyPayload = new byte[] {4};
        byte[] laterFirstPayload = new byte[] {2};
        byte[] laterSecondPayload = new byte[] {3};
        byte[] addedPayload = new byte[] {35};

        BatchProjection__ColumnarProjectionStore.Batch early = store.batch()
                .quantity(new int[] {40})
                .symbol(new String[] {"early"})
                .payload(new byte[][] {earlyPayload});
        BatchProjection__ColumnarProjectionStore.Batch later =
                store.batch(1, 3)
                        .quantity(new int[] {10, 20, 30, 99})
                        .symbol(new String[] {
                            "ignored", "later-1", "later-2"
                        })
                        .payload(new byte[][] {
                            new byte[] {1}, laterFirstPayload,
                            laterSecondPayload, new byte[] {9}
                        });

        later.append();
        store.add(batchProjection(35, "added", addedPayload));
        early.append();

        assertEquals(4, store.size());
        store.seal();
        assertBatchRow(store.viewAt(0), 20, "later-1",
                laterFirstPayload);
        assertBatchRow(store.viewAt(1), 30, "later-2",
                laterSecondPayload);
        assertBatchRow(store.viewAt(2), 35, "added", addedPayload);
        assertBatchRow(store.viewAt(3), 40, "early", earlyPayload);
    }

    @Test
    void retainsSourcesUntilAppendThenOwnsCopiedOuterValues()
            throws Exception {
        BatchProjection__ColumnarProjectionStore store =
                new BatchProjection__ColumnarProjectionStore(0);
        int[] quantities = new int[] {1, 2};
        String[] symbols = new String[] {"one", "two"};
        byte[] originalPayload = new byte[] {1};
        byte[] replacementPayload = new byte[] {2};
        byte[][] payloads = new byte[][] {originalPayload, null};
        BatchProjection__ColumnarProjectionStore.Batch batch = store.batch()
                .quantity(quantities)
                .symbol(symbols)
                .payload(payloads);

        assertEquals(3, batchSourceReferenceCount(batch, false));
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
        assertEquals(3, batchSourceReferenceCount(batch, true));

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
    void rangedBatchSupportsEveryPrimitiveAndReferenceCategory() {
        AllValuesProjection__ColumnarProjectionStore store =
                new AllValuesProjection__ColumnarProjectionStore(0);
        Object selectedObject = new Object();
        int[] selectedPrimitiveArray = new int[] {10, 20};
        String[] selectedReferenceArray = new String[] {"nested", null};

        store.batch(1, 3)
                .booleanValue(new boolean[] {false, true, false})
                .byteValue(new byte[] {0, 3, 4, 99})
                .shortValue(new short[] {0, 5, 6})
                .intValue(new int[] {0, 7, 8, 97})
                .longValue(new long[] {0L, 9L, 10L})
                .charValue(new char[] {'x', 'a', 'b', 'z'})
                .floatValue(new float[] {0.0F, 1.5F, 2.5F})
                .doubleValue(new double[] {0.0D, 3.5D, 4.5D, 94.0D})
                .textValue(new String[] {"ignored", null, "text"})
                .objectValue(new Object[] {
                    "ignored", selectedObject, null, "ignored"
                })
                .primitiveArrayValue(new int[][] {
                    new int[] {0}, selectedPrimitiveArray, null
                })
                .referenceArrayValue(new String[][] {
                    new String[] {"ignored"}, selectedReferenceArray, null,
                    new String[] {"ignored"}
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
        assertSame(selectedObject, first.objectValue());
        assertSame(selectedPrimitiveArray, first.primitiveArrayValue());
        assertSame(selectedReferenceArray, first.referenceArrayValue());

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

    @SuppressWarnings({"rawtypes", "unchecked"})
    @Test
    void wholeBatchSupportsResolvedGenericAndTypedReferenceColumns() {
        TypedReferenceProjection__ColumnarProjectionStore store =
                new TypedReferenceProjection__ColumnarProjectionStore(0);
        RuntimeCustomer customer = new RuntimeCustomer("customer");
        int[] ids = new int[] {1, 2};
        ArrayList<String> labels = new ArrayList<String>();
        labels.add("label");
        LinkedHashMap<String, Integer> lookup =
                new LinkedHashMap<String, Integer>();
        lookup.put("key", Integer.valueOf(1));
        String[] names = new String[] {"name"};
        ArrayList<String>[] labelSources =
                new ArrayList[] {labels};
        LinkedHashMap<String, Integer>[] lookupSources =
                new LinkedHashMap[] {lookup};

        store.batch()
                .labels(labelSources)
                .lookup(lookupSources)
                .customer(new RuntimeCustomer[] {customer})
                .ids(new int[][] {ids})
                .name(new String[] {"typed"})
                .names(new String[][] {names})
                .append();

        store.seal();
        TypedReferenceProjection row = store.viewAt(0);
        assertSame(customer, row.customer());
        assertSame(ids, row.ids());
        assertSame(labels, row.labels());
        assertSame(lookup, row.lookup());
        assertEquals("typed", row.name());
        assertSame(names, row.names());
    }

    @Test
    void sealingRejectsFactoriesAndAnUnfinishedAppend() throws Exception {
        BatchProjection__ColumnarProjectionStore store =
                new BatchProjection__ColumnarProjectionStore(2);
        byte[] existingPayload = new byte[] {1};
        store.add(batchProjection(1, "existing", existingPayload));
        BatchProjection__ColumnarProjectionStore.Batch batch = store.batch()
                .quantity(new int[] {2})
                .symbol(new String[] {"not-copied"})
                .payload(new byte[][] {new byte[] {2}});

        store.seal();

        assertThrows(IllegalStateException.class, batch::append);
        assertThrows(IllegalStateException.class, store::batch);
        assertThrows(IllegalStateException.class, () -> store.batch(0, 0));
        assertThrows(IllegalStateException.class, () -> store.batch(-1, 0));
        assertEquals(3, batchSourceReferenceCount(batch, false));
        assertEquals(1, store.size());
        assertColumnSlotIsDefault(store, 1);
        assertBatchRow(store.viewAt(0), 1, "existing", existingPayload);
    }

    private static void appendRange(
            BatchProjection__ColumnarProjectionStore store,
            int sourceFromIndex,
            int sourceToIndex,
            int[] quantities,
            String[] symbols,
            byte[][] payloads) {
        store.batch(sourceFromIndex, sourceToIndex)
                .payload(payloads)
                .symbol(symbols)
                .quantity(quantities)
                .append();
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

    private static int batchSourceReferenceCount(
            Object batch, boolean expectCleared) throws Exception {
        int sourceCount = 0;
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
            sourceCount++;
        }
        return sourceCount;
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
