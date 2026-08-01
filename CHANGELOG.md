# Changelog

This file records user-visible changes to Columnar Projection Store.

## Unreleased

## 1.1.0 - 2026-08-01

### Changed

- Generated reference columns now use each accessor's erased return type rather
  than widening every reference column to `Object[]`.
- Generated add locals and projection getters preserve erased reference types,
  eliminating unnecessary casts while retaining raw erased array types for
  parameterized accessors.
- Array-valued accessors now generate correctly dimensioned typed column
  allocation and growth code.

## 1.0.0 - 2026-08-01

The first functional release of Columnar Projection Store.

### Added

- `@ProjectionSchema` and its compile-time processor for generating dedicated
  columnar stores from non-generic projection interfaces.
- Separate runtime and annotation-processor artifacts, with the processor used
  only on the annotation-processor path.
- Support for primitive and opaque reference columns, inherited accessors, and
  schema default methods.
- Compile-time rejection of schemas without an effective abstract accessor and
  of accessor declarations that conflict with `java.lang.Object` methods.
- `ProjectionStores.create` with a growable expected-size capacity hint.
- Consistent factory failures that distinguish invalid arguments from missing,
  malformed, incompatible, or uninstantiable generated implementations.
- Append and seal lifecycle with failure-atomic logical row addition.
- Stable indexed row views and reusable, rewindable cursors.
- Concurrent reads after sealing and safe publication, using an independent
  cursor per thread.
- Java 8 compatibility, Maven Wrapper build verification, source artifacts,
  Javadocs, and Apache-2.0 licensing.
