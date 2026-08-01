package io.github.jutil.columnarprojection;

import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.util.Objects;

/**
 * Creates generated {@link ProjectionStore projection stores}.
 *
 * <p>The implementation for a projection schema is generated when the schema
 * is compiled. Applications normally interact only with this factory and the
 * public store abstractions; generated implementation classes are not part of
 * the supported API.
 */
public final class ProjectionStores {

    private static final String GENERATED_CLASS_SUFFIX =
            "__ColumnarProjectionStore";

    private ProjectionStores() {
    }

    /**
     * Creates an empty store for {@code projectionType}.
     *
     * <p>{@code expectedSize} is an initial-capacity hint, not a row limit. A
     * store grows as needed while it is in its building state.
     *
     * @param projectionType the projection schema interface
     * @param expectedSize the expected number of rows, or zero when unknown
     * @param <T> the projection type
     * @return a new empty store in the building state
     * @throws NullPointerException if {@code projectionType} is {@code null}
     * @throws IllegalArgumentException if {@code expectedSize} is negative,
     *         or {@code projectionType} is not an interface
     * @throws IllegalStateException if no generated implementation is
     *         available, or if the generated implementation is malformed,
     *         incompatible, or cannot be instantiated
     */
    public static <T> ProjectionStore<T> create(
            Class<T> projectionType, int expectedSize) {
        Objects.requireNonNull(projectionType, "projectionType");
        if (expectedSize < 0) {
            throw new IllegalArgumentException(
                    "expectedSize must be greater than or equal to zero");
        }
        if (!projectionType.isInterface()) {
            throw new IllegalArgumentException(
                    "projectionType must be an interface: "
                            + projectionType.getName());
        }

        String generatedClassName = projectionType.getName()
                + GENERATED_CLASS_SUFFIX;
        Class<?> generatedClass;
        try {
            generatedClass = Class.forName(
                    generatedClassName, true, projectionType.getClassLoader());
        } catch (ClassNotFoundException exception) {
            throw new IllegalStateException(
                    "No generated projection store was found for "
                            + projectionType.getName()
                            + ". Configure the "
                            + "io.github.j-util:columnar-projection-store-"
                            + "processor artifact on the annotation-processor "
                            + "path when compiling the schema.",
                    exception);
        } catch (LinkageError error) {
            throw malformedImplementation(generatedClassName, error);
        } catch (SecurityException exception) {
            throw malformedImplementation(generatedClassName, exception);
        }

        if (!ProjectionStore.class.isAssignableFrom(generatedClass)) {
            throw new IllegalStateException(
                    "Generated class does not implement ProjectionStore: "
                            + generatedClassName);
        }

        try {
            Constructor<?> constructor =
                    generatedClass.getConstructor(Integer.TYPE);
            Object store = constructor.newInstance(expectedSize);
            return castStore(store);
        } catch (NoSuchMethodException exception) {
            throw malformedImplementation(generatedClassName, exception);
        } catch (InstantiationException exception) {
            throw malformedImplementation(generatedClassName, exception);
        } catch (IllegalAccessException exception) {
            throw malformedImplementation(generatedClassName, exception);
        } catch (InvocationTargetException exception) {
            throw malformedImplementation(
                    generatedClassName, exception.getCause());
        } catch (SecurityException exception) {
            throw malformedImplementation(generatedClassName, exception);
        }
    }

    @SuppressWarnings("unchecked")
    private static <T> ProjectionStore<T> castStore(Object store) {
        return (ProjectionStore<T>) store;
    }

    private static IllegalStateException malformedImplementation(
            String className, Throwable cause) {
        return new IllegalStateException(
                "Could not instantiate generated projection store "
                        + className,
                cause);
    }
}
