/**
 * Compile-time generation for columnar projection stores.
 *
 * <p>This package is provided by the
 * {@code columnar-projection-store-processor} artifact. Applications place
 * that artifact on the annotation-processor path; the Java compiler discovers
 * its processor through the standard service-provider mechanism. Generated
 * schema-specific contracts expose typed batch append and a synchronous typed
 * column appender while the runtime artifact remains schema-agnostic.
 */
package io.github.jutil.columnarprojection.processor;
