package io.github.jutil.columnarprojection;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks an interface as a schema for generated columnar projection storage.
 *
 * <p>Each abstract, zero-argument method with a non-void return type defines a
 * stored column. Primitive and reference return types are permitted. Reference
 * values, including {@code null}, are stored as opaque references and are not
 * recursively flattened.
 *
 * <p>Default methods define behavior and are not columns. Projection accessors
 * must not declare checked exceptions.
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.CLASS)
@Documented
public @interface ProjectionSchema {
}
