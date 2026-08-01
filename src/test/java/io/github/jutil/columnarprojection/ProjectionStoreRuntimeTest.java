package io.github.jutil.columnarprojection;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class ProjectionStoreRuntimeTest {

    @Test
    void storesEveryPrimitiveAndOpaqueReferenceValue() {
        ProjectionStore<AllValuesProjection> store =
                ProjectionStores.create(AllValuesProjection.class, 1);

        MutableAllValues source = new MutableAllValues();
        source.booleanValue = true;
        source.byteValue = (byte) 0x12;
        source.shortValue = (short) 1234;
        source.intValue = 123456;
        source.longValue = 9876543210L;
        source.charValue = '\u03bb';
        source.floatValue = 1.25F;
        source.doubleValue = -9.5D;
        source.textValue = "before";
        Object originalObject = new Object();
        source.objectValue = originalObject;
        int[] originalPrimitiveArray = new int[] {1, 2, 3};
        String[] originalReferenceArray = new String[] {"a", null};
        source.primitiveArrayValue = originalPrimitiveArray;
        source.referenceArrayValue = originalReferenceArray;

        store.add(source);

        source.booleanValue = false;
        source.byteValue = 0;
        source.shortValue = 0;
        source.intValue = 0;
        source.longValue = 0L;
        source.charValue = '\0';
        source.floatValue = 0.0F;
        source.doubleValue = 0.0D;
        source.textValue = "after";
        source.objectValue = new Object();
        source.primitiveArrayValue = new int[] {99};
        source.referenceArrayValue = new String[] {"replacement"};
        originalPrimitiveArray[1] = 22;
        originalReferenceArray[0] = "changed";

        MutableAllValues nullReferences = new MutableAllValues();
        store.add(nullReferences);
        store.seal();

        AllValuesProjection stored = store.viewAt(0);
        assertTrue(stored.booleanValue());
        assertEquals((byte) 0x12, stored.byteValue());
        assertEquals((short) 1234, stored.shortValue());
        assertEquals(123456, stored.intValue());
        assertEquals(9876543210L, stored.longValue());
        assertEquals('\u03bb', stored.charValue());
        assertEquals(1.25F, stored.floatValue());
        assertEquals(-9.5D, stored.doubleValue());
        assertEquals("before", stored.textValue());
        assertSame(originalObject, stored.objectValue());
        assertSame(originalPrimitiveArray, stored.primitiveArrayValue());
        assertSame(originalReferenceArray, stored.referenceArrayValue());
        assertEquals(22, stored.primitiveArrayValue()[1]);
        assertEquals("changed", stored.referenceArrayValue()[0]);

        AllValuesProjection storedNulls = store.viewAt(1);
        assertNull(storedNulls.textValue());
        assertNull(storedNulls.objectValue());
        assertNull(storedNulls.primitiveArrayValue());
        assertNull(storedNulls.referenceArrayValue());
    }

    @Test
    void growsFromZeroInitialCapacityWithoutChangingRowOrder() {
        ProjectionStore<IntProjection> store =
                ProjectionStores.create(IntProjection.class, 0);

        for (int index = 0; index < 257; index++) {
            final int value = index * 3;
            store.add(new IntProjection() {
                @Override
                public int value() {
                    return value;
                }
            });
        }

        assertEquals(257, store.size());
        store.seal();
        for (int index = 0; index < 257; index++) {
            assertEquals(index * 3, store.viewAt(index).value());
        }
    }

    @Test
    void invokesEachAccessorExactlyOnceForASuccessfulAdd() {
        ProjectionStore<CountingProjection> store =
                ProjectionStores.create(CountingProjection.class, 0);
        CountingRow source = new CountingRow();

        store.add(source);

        assertEquals(1, source.firstCalls);
        assertEquals(1, source.secondCalls);
        assertEquals(1, store.size());
        store.seal();
        assertEquals(41, store.viewAt(0).first());
        assertEquals("value", store.viewAt(0).second());
        assertEquals(1, source.firstCalls);
        assertEquals(1, source.secondCalls);
    }

    @Test
    void accessorFailureIsPropagatedAndDoesNotAppendAPartialRow() {
        ProjectionStore<FallibleProjection> store =
                ProjectionStores.create(FallibleProjection.class, 0);
        FallibleRow source = new FallibleRow();
        source.value = 7;
        store.add(source);

        RuntimeException failure = new RuntimeException("accessor failure");
        source.value = 8;
        source.failure = failure;
        RuntimeException thrown = assertThrows(
                RuntimeException.class, () -> store.add(source));

        assertSame(failure, thrown);
        assertEquals(2, source.calls);
        assertEquals(1, store.size());

        source.failure = null;
        source.value = 9;
        store.add(source);
        assertEquals(3, source.calls);
        assertEquals(2, store.size());

        store.seal();
        assertEquals(7, store.viewAt(0).value());
        assertEquals(9, store.viewAt(1).value());
    }

    @Test
    void laterAccessorFailureLeavesTheLogicalStoreUnchanged() {
        ProjectionStore<PartiallyFallibleProjection> store =
                ProjectionStores.create(
                        PartiallyFallibleProjection.class, 1);
        PartiallyFallibleRow source = new PartiallyFallibleRow();
        source.first = 10;
        source.second = "initial";
        store.add(source);

        RuntimeException failure = new RuntimeException("second accessor");
        source.first = 20;
        source.failure = failure;
        RuntimeException thrown = assertThrows(
                RuntimeException.class, () -> store.add(source));

        assertSame(failure, thrown);
        assertEquals(2, source.firstCalls);
        assertEquals(2, source.secondCalls);
        assertEquals(1, store.size());

        source.first = 30;
        source.second = "recovered";
        source.failure = null;
        store.add(source);
        store.seal();

        assertEquals(10, store.viewAt(0).first());
        assertEquals("initial", store.viewAt(0).second());
        assertEquals(30, store.viewAt(1).first());
        assertEquals("recovered", store.viewAt(1).second());
    }

    @Test
    void enforcesBuildingAndSealedLifecycle() {
        ProjectionStore<IntProjection> store =
                ProjectionStores.create(IntProjection.class, 1);
        IntProjection row = intProjection(12);

        assertEquals(0, store.size());
        assertThrows(IllegalStateException.class, store::cursor);
        assertThrows(IllegalStateException.class, () -> store.viewAt(0));
        assertThrows(NullPointerException.class, () -> store.add(null));
        assertEquals(0, store.size());

        store.add(row);
        assertEquals(1, store.size());
        store.seal();
        store.seal();

        assertEquals(1, store.size());
        assertThrows(IllegalStateException.class, () -> store.add(row));
        assertEquals(1, store.size());
        assertThrows(IndexOutOfBoundsException.class, () -> store.viewAt(-1));
        assertThrows(IndexOutOfBoundsException.class, () -> store.viewAt(1));
        assertEquals(12, store.viewAt(0).value());
    }

    @Test
    void stableViewsRemainPermanentlyBoundToTheirRows() {
        ProjectionStore<IntProjection> store = sealedInts(10, 20, 30);

        IntProjection first = store.viewAt(0);
        IntProjection last = store.viewAt(2);
        assertEquals(10, first.value());
        assertEquals(30, last.value());

        ProjectionCursor<IntProjection> cursor = store.cursor();
        while (cursor.moveNext()) {
            cursor.current().value();
        }
        store.viewAt(1).value();

        assertEquals(10, first.value());
        assertEquals(30, last.value());
    }

    @Test
    void cursorReusesOneViewAndSupportsExhaustionAndRewind() {
        ProjectionStore<IntProjection> store = sealedInts(3, 5);
        ProjectionCursor<IntProjection> cursor = store.cursor();

        assertThrows(IllegalStateException.class, cursor::current);
        assertTrue(cursor.moveNext());
        IntProjection reusableView = cursor.current();
        assertEquals(3, reusableView.value());

        assertTrue(cursor.moveNext());
        assertSame(reusableView, cursor.current());
        assertEquals(5, cursor.current().value());

        assertFalse(cursor.moveNext());
        assertThrows(IllegalStateException.class, cursor::current);
        assertFalse(cursor.moveNext());
        assertThrows(IllegalStateException.class, cursor::current);

        cursor.rewind();
        assertThrows(IllegalStateException.class, cursor::current);
        assertTrue(cursor.moveNext());
        assertSame(reusableView, cursor.current());
        assertEquals(3, cursor.current().value());
    }

    @Test
    void cursorsAdvanceIndependentlyAndOwnDifferentViews() {
        ProjectionStore<IntProjection> store = sealedInts(11, 22);
        ProjectionCursor<IntProjection> firstCursor = store.cursor();
        ProjectionCursor<IntProjection> secondCursor = store.cursor();

        assertTrue(firstCursor.moveNext());
        assertTrue(secondCursor.moveNext());
        assertNotSame(firstCursor.current(), secondCursor.current());
        assertEquals(11, firstCursor.current().value());
        assertEquals(11, secondCursor.current().value());

        assertTrue(firstCursor.moveNext());
        assertEquals(22, firstCursor.current().value());
        assertEquals(11, secondCursor.current().value());

        assertTrue(secondCursor.moveNext());
        assertEquals(22, secondCursor.current().value());
        assertFalse(firstCursor.moveNext());
        assertEquals(22, secondCursor.current().value());
    }

    private static ProjectionStore<IntProjection> sealedInts(int... values) {
        ProjectionStore<IntProjection> store =
                ProjectionStores.create(IntProjection.class, values.length);
        for (int value : values) {
            store.add(intProjection(value));
        }
        store.seal();
        return store;
    }

    private static IntProjection intProjection(final int value) {
        return new IntProjection() {
            @Override
            public int value() {
                return value;
            }
        };
    }

    private static final class MutableAllValues
            implements AllValuesProjection {
        private boolean booleanValue;
        private byte byteValue;
        private short shortValue;
        private int intValue;
        private long longValue;
        private char charValue;
        private float floatValue;
        private double doubleValue;
        private String textValue;
        private Object objectValue;
        private int[] primitiveArrayValue;
        private String[] referenceArrayValue;

        @Override
        public boolean booleanValue() {
            return booleanValue;
        }

        @Override
        public byte byteValue() {
            return byteValue;
        }

        @Override
        public short shortValue() {
            return shortValue;
        }

        @Override
        public int intValue() {
            return intValue;
        }

        @Override
        public long longValue() {
            return longValue;
        }

        @Override
        public char charValue() {
            return charValue;
        }

        @Override
        public float floatValue() {
            return floatValue;
        }

        @Override
        public double doubleValue() {
            return doubleValue;
        }

        @Override
        public String textValue() {
            return textValue;
        }

        @Override
        public Object objectValue() {
            return objectValue;
        }

        @Override
        public int[] primitiveArrayValue() {
            return primitiveArrayValue;
        }

        @Override
        public String[] referenceArrayValue() {
            return referenceArrayValue;
        }
    }

    private static final class CountingRow implements CountingProjection {
        private int firstCalls;
        private int secondCalls;

        @Override
        public int first() {
            firstCalls++;
            return 41;
        }

        @Override
        public String second() {
            secondCalls++;
            return "value";
        }
    }

    private static final class FallibleRow implements FallibleProjection {
        private int calls;
        private int value;
        private RuntimeException failure;

        @Override
        public int value() {
            calls++;
            if (failure != null) {
                throw failure;
            }
            return value;
        }
    }

    private static final class PartiallyFallibleRow
            implements PartiallyFallibleProjection {
        private int first;
        private String second;
        private RuntimeException failure;
        private int firstCalls;
        private int secondCalls;

        @Override
        public int first() {
            firstCalls++;
            return first;
        }

        @Override
        public String second() {
            secondCalls++;
            if (failure != null) {
                throw failure;
            }
            return second;
        }
    }
}

@ProjectionSchema
interface AllValuesProjection {
    boolean booleanValue();

    byte byteValue();

    short shortValue();

    int intValue();

    long longValue();

    char charValue();

    float floatValue();

    double doubleValue();

    String textValue();

    Object objectValue();

    int[] primitiveArrayValue();

    String[] referenceArrayValue();
}

@ProjectionSchema
interface IntProjection {
    int value();
}

@ProjectionSchema
interface CountingProjection {
    int first();

    String second();
}

@ProjectionSchema
interface FallibleProjection {
    int value();
}

@ProjectionSchema
interface PartiallyFallibleProjection {
    int first();

    String second();
}
