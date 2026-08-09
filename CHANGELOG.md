# Changelog

This file records user-visible changes to Columnar Projection Store.

## Unreleased

## 1.2.0 - 2026-08-09

### Added

- Every generated concrete store now exposes a store-specific, type-safe batch
  API with `batch()` for whole source arrays and
  `batch(sourceFromIndex, sourceToIndex)` for a common half-open source range.
- Whole-array batches infer their row count from the first accepted column,
  require equal array lengths, and require every column even when all arrays
  are empty.
- Common-range batches accept arrays with different physical lengths, copy the
  requested range from every column, and allow an empty range to append as a
  no-op without column assignments. Source indexes never address existing
  rows; every successful batch appends at the store's current end.
- Positive batches retain their source arrays until append, reserve destination
  capacity once, and copy with one bulk array copy per column before increasing
  the logical size. Failed assignments and missing-column appends remain
  correctable, and successful batches are consumed and release their source
  references.
- Generated batch APIs cover every supported primitive, reference, inherited,
  covariant, and array-valued projection return type.
- Parameterized batch columns preserve accessible resolved generic arguments,
  with an erased compatibility fallback when an argument cannot be named from
  generated source.
- Generated batch types use a deterministic collision-safe name whenever
  `Batch`, `Batch_`, or a later candidate would shadow a named-package root or
  unnamed-package top-level type required by generated source.

### Changed

- Generated stores now document both typed batch modes, including source-range
  validation, empty behavior, ownership, lifecycle, ordering, and complexity
  semantics.
- API-boundary and growth-complexity documentation now distinguishes supported
  generated batch entry points from other generated details and accounts for
  `newCapacity` during large batch growth.

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
