package io.github.jutil.columnarprojection;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class ProjectionSchemaFeaturesTest {

    @Test
    void generatedViewsRetainDefaultMethodBehavior() {
        ProjectionStore<DefaultMethodProjection> store =
                ProjectionStores.create(DefaultMethodProjection.class, 1);
        store.add(new DefaultMethodProjection() {
            @Override
            public int quantity() {
                return 6;
            }

            @Override
            public int doubledQuantity() {
                throw new AssertionError(
                        "default methods must not be copied as columns");
            }

            @Override
            public String description() {
                throw new AssertionError(
                        "default methods must not be copied as columns");
            }
        });
        store.seal();

        DefaultMethodProjection view = store.viewAt(0);
        assertEquals(6, view.quantity());
        assertEquals(12, view.doubledQuantity());
        assertEquals("quantity=6", view.description());
    }

    @Test
    void resolvesAccessorsInheritedFromAGenericParent() {
        ProjectionStore<InheritedStringProjection> store =
                ProjectionStores.create(InheritedStringProjection.class, 0);
        store.add(new InheritedStringProjection() {
            @Override
            public String inheritedValue() {
                return "resolved generic value";
            }
        });
        store.seal();

        InheritedStringProjection view = store.viewAt(0);
        assertEquals("resolved generic value", view.inheritedValue());
        GenericValueProjection<String> asParent = view;
        assertEquals("resolved generic value", asParent.inheritedValue());
    }

    @Test
    void implementsTheMostSpecificCovariantAccessor() {
        ProjectionStore<CovariantIntegerProjection> store =
                ProjectionStores.create(CovariantIntegerProjection.class, 1);
        store.add(new CovariantIntegerProjection() {
            @Override
            public Integer amount() {
                return Integer.valueOf(42);
            }
        });
        store.seal();

        CovariantIntegerProjection view = store.viewAt(0);
        assertEquals(Integer.valueOf(42), view.amount());
        NumericProjection asParent = view;
        assertEquals(Integer.valueOf(42), asParent.amount());
    }

    @Test
    void createsAStoreForANestedSchemaUsingItsBinaryName() {
        ProjectionStore<NestedSchemaContainer.NestedProjection> store =
                ProjectionStores.create(
                        NestedSchemaContainer.NestedProjection.class, 0);
        store.add(new NestedSchemaContainer.NestedProjection() {
            @Override
            public long identifier() {
                return 123L;
            }
        });
        store.seal();

        assertEquals(123L, store.viewAt(0).identifier());
    }

    @Test
    void emptySchemaStoresLogicalRowsAndSupportsCursorLifecycle() {
        ProjectionStore<EmptyProjection> store =
                ProjectionStores.create(EmptyProjection.class, 0);
        EmptyProjection source = new EmptyProjection() {
        };

        store.add(source);
        store.add(source);
        store.add(source);
        assertEquals(3, store.size());
        assertThrows(IllegalStateException.class, store::cursor);

        store.seal();
        store.seal();
        assertTrue(store.viewAt(0) instanceof EmptyProjection);
        assertTrue(store.viewAt(2) instanceof EmptyProjection);

        ProjectionCursor<EmptyProjection> cursor = store.cursor();
        assertTrue(cursor.moveNext());
        EmptyProjection reusable = cursor.current();
        assertTrue(cursor.moveNext());
        assertSame(reusable, cursor.current());
        assertTrue(cursor.moveNext());
        assertSame(reusable, cursor.current());
        assertFalse(cursor.moveNext());
        assertThrows(IllegalStateException.class, cursor::current);
    }

    @Test
    void sealedEmptyStoreHasAnImmediatelyExhaustedRewindableCursor() {
        ProjectionStore<EmptyProjection> store =
                ProjectionStores.create(EmptyProjection.class, 0);
        store.seal();

        ProjectionCursor<EmptyProjection> cursor = store.cursor();
        assertFalse(cursor.moveNext());
        assertFalse(cursor.moveNext());
        assertThrows(IllegalStateException.class, cursor::current);
        cursor.rewind();
        assertThrows(IllegalStateException.class, cursor::current);
        assertFalse(cursor.moveNext());
        assertThrows(IndexOutOfBoundsException.class, () -> store.viewAt(0));
    }
}

@ProjectionSchema
interface DefaultMethodProjection {
    int quantity();

    default int doubledQuantity() {
        return quantity() * 2;
    }

    default String description() {
        return "quantity=" + quantity();
    }
}

interface GenericValueProjection<T> {
    T inheritedValue();
}

@ProjectionSchema
interface InheritedStringProjection extends GenericValueProjection<String> {
}

interface NumericProjection {
    Number amount();
}

@ProjectionSchema
interface CovariantIntegerProjection extends NumericProjection {
    @Override
    Integer amount();
}

final class NestedSchemaContainer {
    private NestedSchemaContainer() {
    }

    @ProjectionSchema
    interface NestedProjection {
        long identifier();
    }
}

@ProjectionSchema
interface EmptyProjection {
}
