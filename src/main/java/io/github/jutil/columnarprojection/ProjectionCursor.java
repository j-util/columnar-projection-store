package io.github.jutil.columnarprojection;

/**
 * Traverses the rows of a sealed {@link ProjectionStore} using a reusable
 * projection view.
 *
 * <p>A new cursor is positioned before the first row. A cursor owns one
 * reusable projection view; it does not allocate a new view for each row. The
 * same view object is reused as the cursor moves and must not be retained.
 *
 * <p>A cursor owns no external resources and is not {@link AutoCloseable}. It
 * is not thread-safe.
 *
 * @param <T> the projection type
 */
public interface ProjectionCursor<T> {

    /**
     * Advances to the next row.
     *
     * <p>After this method returns {@code false}, the cursor is exhausted and
     * subsequent calls continue to return {@code false} until
     * {@link #rewind()} is called.
     *
     * @return {@code true} if the cursor advanced to a row; {@code false} if
     *         the cursor is exhausted
     */
    boolean moveNext();

    /**
     * Returns the cursor-owned reusable projection view for the current row.
     *
     * <p>This method is valid only after a successful call to
     * {@link #moveNext()} and before exhaustion or a call to
     * {@link #rewind()}. The returned object is reused as this cursor moves and
     * must not be retained.
     *
     * @return the reusable projection view for the current row
     * @throws IllegalStateException if the cursor is not positioned on a row
     */
    T current();

    /**
     * Returns this cursor to its before-first position.
     *
     * <p>After this method returns, {@link #current()} is invalid until
     * {@link #moveNext()} again returns {@code true}.
     */
    void rewind();
}
