/**
 * Supported runtime abstractions and the factory for generated columnar
 * storage of client-defined projections.
 *
 * <p>Annotate an interface with
 * {@link io.github.jutil.columnarprojection.ProjectionSchema}, compile it with
 * annotation processing enabled, and create its common row-oriented store
 * through {@link io.github.jutil.columnarprojection.ProjectionStores}. For
 * typed batching or synchronous per-column filling, prefer the generated public
 * schema-specific store contract; its static factory directly constructs its
 * compile-time-known implementation. Per-column methods use each projection
 * accessor's exact name and corresponding generated source-array type.
 * {@code ProjectionStores} instead performs reflective discovery for generic
 * row-oriented code. The generated contract and concrete store are emitted into
 * the schema package. The contract and the concrete store's public constructor
 * are supported; all other generated implementation details are unsupported.
 */
package io.github.jutil.columnarprojection;
