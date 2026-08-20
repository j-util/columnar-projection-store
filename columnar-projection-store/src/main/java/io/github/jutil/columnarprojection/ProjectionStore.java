package io.github.jutil.columnarprojection;

/**
 * Stores copied column values for projections of type {@code T}.
 *
 * <p>A newly created store is in the building state and is logically empty.
 * Row and batch building operations are not thread-safe. A generated
 * schema-specific store may additionally expose synchronous per-column filling:
 * distinct columns may be filled concurrently, but each column is
 * single-writer, and no common operation declared here may overlap that work.
 * A successful {@link #seal()} permanently ends the building state. After
 * sealing and safe publication, the store may be read concurrently, but each
 * thread must use its own {@link ProjectionCursor}.
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
     * @throws IllegalStateException if this store has been sealed or positive
     *         per-column filling has selected the incompatible mutation mode
     */
    void add(T projection);

    /**
     * Returns the number of successfully added logical rows.
     *
     * <p>This method is available in both the building and sealed states. For a
     * generated store using per-column filling, it remains zero until a
     * successful seal publishes the equal generated-column count.
     *
     * @return the number of logical rows
     */
    int size();

    /**
     * Permanently ends the building state.
     *
     * <p>Sealing an untouched empty store is valid. This method is idempotent
     * after success. A generated store using per-column filling validates that
     * every generated column has the same appended count. Unequal counts cause
     * failure without sealing or discarding data, so lagging columns can be
     * filled before retrying. Missing columns are not inferred as null or
     * primitive default values. The caller must finish and join all filling
     * work before invoking this method; sealing never waits for it.
     *
     * @throws IllegalStateException if generated column counts differ
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
