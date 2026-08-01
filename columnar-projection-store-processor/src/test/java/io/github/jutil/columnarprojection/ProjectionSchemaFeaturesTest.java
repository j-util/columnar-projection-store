package io.github.jutil.columnarprojection;

import static org.junit.jupiter.api.Assertions.assertEquals;

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
