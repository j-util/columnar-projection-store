package io.github.jutil.columnarprojection;

/**
 * Stores copied column values for projections of type {@code T}.
 *
 * <p>A newly created store is in the building state and is logically empty.
 * The building state is not thread-safe. Calling {@link #seal()} permanently
 * ends the building state. After sealing and safe publication, the store may
 * be read concurrently, but each thread must use its own
 * {@link ProjectionCursor}.
 *
 * @param <T> the projection type
 */
public interface ProjectionStore<T> {

    /**
     * Appends a logical row by immediately copying all projected accessor
     * values from {@code projection}.
     *
     * <p>The source projection object is never retained. Primitive values are
     * copied by value. Reference values, including {@code null}, are copied as
     * references; no deep copy is implied.
     *
     * <p>If an accessor throws, its exception is propagated and no logical row
     * is appended. The source projection must not be mutated concurrently
     * while this method executes.
     *
     * @param projection the projection whose accessor values are copied
     * @throws NullPointerException if {@code projection} is {@code null}
     * @throws IllegalStateException if this store has been sealed
     */
    void add(T projection);

    /**
     * Returns the number of successfully added logical rows.
     *
     * <p>This method is available in both the building and sealed states.
     *
     * @return the number of logical rows
     */
    int size();

    /**
     * Permanently ends the building state.
     *
     * <p>Sealing an empty store is valid. This method is idempotent.
     */
    void seal();

    /**
     * Creates a new independent cursor positioned before the first row.
     *
     * @return a new cursor
     * @throws IllegalStateException if this store has not been sealed
     */
    ProjectionCursor<T> cursor();

    /**
     * Returns a stable, retainable, read-only projection permanently bound to
     * the row at {@code index}.
     *
     * <p>View identity and caching are not guaranteed. Repeated calls for the
     * same row may return different objects.
     *
     * @param index the zero-based row index
     * @return a projection bound to the requested row
     * @throws IllegalStateException if this store has not been sealed
     * @throws IndexOutOfBoundsException if {@code index} does not identify a
     *         stored row
     */
    T viewAt(int index);
}
