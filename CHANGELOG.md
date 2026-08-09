# Changelog

This file records user-visible changes to Columnar Projection Store.

## Unreleased

## 1.2.0 - 2026-08-09

### Added

- Every generated concrete store now exposes a store-specific, type-safe batch
  API for appending slices of column arrays with one bulk copy per column.
- Generated batch APIs cover every supported primitive, reference, inherited,
  covariant, and array-valued projection return type.

### Changed

- Generated stores now document their public constructor and typed batch API,
  including validation, ownership, lifecycle, ordering, and complexity
  semantics.

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
