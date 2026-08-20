# Changelog

This file records user-visible changes to Columnar Projection Store.

## 1.3.0 - Unreleased

### Added

- Generated schema-specific store interfaces now expose a nested typed
  column-appender contract through `columnAppender()`. Its whole-array and
  half-open-range methods use each projection accessor's exact name and source
  array type. Each call synchronously appends only its own column, copies before
  returning, and retains no source-array reference.
- The official generated store returns one store-owned appender and keeps
  accessor-named filling methods off the store itself. Distinct appender column
  methods may run concurrently. Each column has an independent append count and
  grows only its own backing array; calls to the same column remain externally
  single-writer.
- Column-appender contract and implementation names use the processor's
  deterministic collision-safe nested-type naming rule.
- Column filling keeps the logical size at zero until sealing. `seal()` checks
  all generated column counts in O(number of columns), publishes the common
  count on success, and reports useful column/count information on mismatch.
  Unequal-count failures preserve the unsealed store and all copied values so
  lagging columns can be filled before retrying.
- Per-column filling preserves null reference elements, counts all-null arrays
  by their full length, accepts empty no-ops, validates ranges and overflow
  before mutation, and supports primitive, reference, generic, inherited,
  covariant, boxed, and array-valued accessor types.

### Changed

- Positive row/batch mutation and positive per-column mutation now select
  mutually exclusive building modes. Empty no-ops and validation failures do
  not select a mode; `add` and typed batch append remain mixable with each other.
- The unreleased concurrent batch-copy experiment was replaced rather than
  carried into 1.3.0. The published 1.2.0 API remains compatible: generated
  `create(int)`, the public generated constructor, `add`, both batch factories,
  batch append, `seal`, `cursor`, and `viewAt` are unchanged.
- `columnAppender()` is a default generated-contract method so external 1.2.0
  implementations and decorators continue to link and recompile. Its inherited
  default reports unsupported operation; the official generated implementation
  overrides it. The unreleased direct accessor-named store methods were removed
  to avoid changing overload resolution for valid accessor names such as
  `add()` and `equals()`.

## 1.2.0 - 2026-08-10

### Added

- Every projection schema now generates a public schema-specific store
  interface that extends `ProjectionStore<Projection>`, provides a static
  factory that directly constructs its compile-time-known concrete
  implementation, and exposes a store-specific, type-safe batch contract
  without requiring callers to name that implementation. The typed factory
  works in an encapsulated named-module package without an export or open.
  Top-level names use the schema's binary name, so a member schema
  `Outer.PriceProjection` generates the top-level contract
  `Outer$PriceProjectionStore`.
- The generated batch contract provides `batch()` for whole source arrays and
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
- Batch column signatures preserve source-nameable Java type structure and
  accessible resolved generic arguments while intentionally omitting type-use
  annotations; projection interfaces remain authoritative for those
  annotations. An erased compatibility fallback applies when an argument
  cannot be named from generated source.
- Generated batch types use a deterministic collision-safe name whenever
  `Batch`, `Batch_`, or a later candidate would shadow a named-package root
  detected through the supported inputs below or an unnamed-package top-level
  type required by generated source.
- Generated store-contract names append underscores when their ordinary or
  later candidates are packages detected through those inputs or would shadow a
  type root required by generated source. Thus detected packages named
  `example.SchemaStore` and `example.SchemaStore_` make `example.Schema`
  generate `SchemaStore__`.
- Private batch-implementation names use the deterministic underscore rule when
  their ordinary names would shadow a type root required by generated source.
- Generated store-contract, implementation, and private batch-implementation
  names are collision-checked; conflicts produce compiler diagnostics instead
  of silently skipping or overwriting types.
- Before selecting generated names, package-collision detection now records
  package ancestors from current type and package roots and cycle-safely
  traverses current top-level and nested declaration signatures. Covered
  declarations include fields and enum constants, methods and constructors,
  parameters and type parameters, executable receiver types, and record
  components when supported by the running compiler. Traversal includes
  declared and unresolved error types, arrays, generic arguments, wildcards,
  enclosing types, type variables, intersections, and resolved effective
  projection-accessor return types. Collection is complete before naming and is
  independent of source-file and root-element order.
- Collision detection now also traverses annotation mirrors on those
  declarations and on every recursively visited type, including each
  annotation's declared type and explicit annotation values such as
  class-literal, enum, nested-annotation, and array values, plus defaults
  declared by current-source annotation members. Package-info and directly
  present module annotations are included. Generated batch signatures continue
  to omit type-use annotations.
- Compiler invocations now have an explicit one-source-module boundary:
  ordinary class-path compilation and one named or unnamed source module are
  supported. An invocation containing a projection schema and roots from
  multiple source modules fails before schema preparation, naming, or file
  generation, without partial generated output. Maven reactor modules compiled
  in separate invocations remain supported.
- In a supported named module, the module root is not treated as a source type;
  its directly enclosed package elements contribute prefixes without
  recursively enumerating package members or treating class-path types as
  current source.
- Detection remains Java 8 binary-compatible and uses only standard
  annotation-processing and language-model APIs. It does not inspect external
  types referenced only by module `uses`/`provides` directives, imports without
  a declaration-signature use, method or constructor bodies, initializer or
  anonymous-class bodies, arbitrary external annotation members or defaults,
  genuinely unreferenced descendant class-path packages, or packages created by
  another processor in a later round after this processor has selected and
  emitted its names. A `package-info.java` in a candidate parent package makes
  that prefix observable in the schema's naming round.

### Changed

- `ProjectionStores.create(Class<T>, int)` remains the runtime reflective
  discovery path for generic row-oriented code. In a named module, its schema
  package must be exported or opened to `columnar.projection.store`; denied
  access now reports that guidance while retaining `IllegalAccessException` as
  the cause.
- The provenance marker used to recognize processor-owned output exists only in
  output generated by the 1.2 processor. Upgrading from a pre-1.2 processor
  requires one clean build that removes old class and generated-source output,
  because an unmarked generated implementation cannot be distinguished safely
  from an external user type.
- After that clean upgrade, processor-owned 1.2 contracts and concrete
  implementations from an earlier compilation are recognized on the class
  path, so unchanged schemas and accessor changes that preserve generated
  top-level names compile repeatedly without a clean build. Current-source,
  external user-type, and cross-schema collisions remain errors.
- Schema evolution that changes a generated top-level name reports the specific
  stale output and requests a clean rebuild when that output cannot be removed
  safely.
- Generated store interfaces now document construction, both typed batch modes,
  batch column setters, source-range validation, empty behavior, ownership,
  lifecycle, ordering, and complexity semantics.
- Generated concrete stores implement their schema-specific store interfaces;
  their public constructors and common `ProjectionStore` behavior remain
  compatible, while private batch implementations return the exact generated
  batch-interface type.
- Generated batch appends keep per-method work bounded so valid wide schemas
  compile without changing validation or copy semantics.
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
