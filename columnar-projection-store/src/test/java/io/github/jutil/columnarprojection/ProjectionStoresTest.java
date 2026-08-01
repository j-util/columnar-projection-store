package io.github.jutil.columnarprojection;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class ProjectionStoresTest {

    @Test
    void rejectsNullProjectionType() {
        assertThrows(
                NullPointerException.class,
                () -> ProjectionStores.create(null, 0));
    }

    @Test
    void rejectsNegativeExpectedSize() {
        assertThrows(
                IllegalArgumentException.class,
                () -> ProjectionStores.create(FactoryProjection.class, -1));
    }

    @Test
    void rejectsAConcreteProjectionType() {
        assertThrows(
                IllegalArgumentException.class,
                () -> ProjectionStores.create(ConcreteProjection.class, 0));
    }

    @Test
    void rejectsAnInterfaceWithoutAGeneratedImplementation() {
        IllegalStateException thrown = assertThrows(
                IllegalStateException.class,
                () -> ProjectionStores.create(UngeneratedProjection.class, 0));

        assertTrue(thrown.getMessage().contains(
                "io.github.j-util:columnar-projection-store-processor"));
        assertTrue(thrown.getMessage().contains("annotation-processor path"));
        assertInstanceOf(ClassNotFoundException.class, thrown.getCause());
    }

    @Test
    void rejectsAConventionallyNamedClassThatIsNotAStore() {
        assertThrows(
                IllegalStateException.class,
                () -> ProjectionStores.create(NotAStoreProjection.class, 0));
    }

    @Test
    void rejectsAStoreImplementationWithoutTheRequiredConstructor() {
        assertThrows(
                IllegalStateException.class,
                () -> ProjectionStores.create(
                        MissingConstructorProjection.class, 0));
    }

    @Test
    void wrapsRuntimeFailureFromGeneratedConstructor() {
        IllegalStateException thrown = assertThrows(
                IllegalStateException.class,
                () -> ProjectionStores.create(
                        RuntimeFailingConstructorProjection.class, 0));

        assertSame(
                RuntimeFailingConstructorProjection__ColumnarProjectionStore
                        .FAILURE,
                thrown.getCause());
    }

    @Test
    void wrapsCheckedFailureFromGeneratedConstructor() {
        IllegalStateException thrown = assertThrows(
                IllegalStateException.class,
                () -> ProjectionStores.create(
                        CheckedFailingConstructorProjection.class, 0));

        assertSame(
                CheckedFailingConstructorProjection__ColumnarProjectionStore
                        .FAILURE,
                thrown.getCause());
    }
}

@ProjectionSchema
interface FactoryProjection {
    String name();
}

final class ConcreteProjection {
}

interface UngeneratedProjection {
}

interface NotAStoreProjection {
}

final class NotAStoreProjection__ColumnarProjectionStore {
    public NotAStoreProjection__ColumnarProjectionStore(int expectedSize) {
    }
}

interface MissingConstructorProjection {
}

final class MissingConstructorProjection__ColumnarProjectionStore
        extends StubProjectionStore<MissingConstructorProjection> {
    public MissingConstructorProjection__ColumnarProjectionStore() {
    }
}

interface RuntimeFailingConstructorProjection {
}

final class RuntimeFailingConstructorProjection__ColumnarProjectionStore
        extends StubProjectionStore<RuntimeFailingConstructorProjection> {
    static final RuntimeException FAILURE =
            new IllegalArgumentException("constructor failure");

    public RuntimeFailingConstructorProjection__ColumnarProjectionStore(
            int expectedSize) {
        throw FAILURE;
    }
}

interface CheckedFailingConstructorProjection {
}

final class CheckedFailingConstructorProjection__ColumnarProjectionStore
        extends StubProjectionStore<CheckedFailingConstructorProjection> {
    static final Exception FAILURE = new Exception("constructor failure");

    public CheckedFailingConstructorProjection__ColumnarProjectionStore(
            int expectedSize) throws Exception {
        throw FAILURE;
    }
}

abstract class StubProjectionStore<T> implements ProjectionStore<T> {
    @Override
    public void add(T projection) {
        throw new UnsupportedOperationException();
    }

    @Override
    public int size() {
        throw new UnsupportedOperationException();
    }

    @Override
    public void seal() {
        throw new UnsupportedOperationException();
    }

    @Override
    public ProjectionCursor<T> cursor() {
        throw new UnsupportedOperationException();
    }

    @Override
    public T viewAt(int index) {
        throw new UnsupportedOperationException();
    }
}
