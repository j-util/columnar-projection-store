# Columnar Projection Store

[![Maven Central](https://img.shields.io/maven-central/v/io.github.j-util/columnar-projection-store.svg?label=Maven%20Central)](https://central.sonatype.com/artifact/io.github.j-util/columnar-projection-store)
[![Javadoc](https://javadoc.io/badge2/io.github.j-util/columnar-projection-store/javadoc.svg)](https://javadoc.io/doc/io.github.j-util/columnar-projection-store)
[![CI](https://github.com/j-util/columnar-projection-store/actions/workflows/ci.yml/badge.svg)](https://github.com/j-util/columnar-projection-store/actions/workflows/ci.yml)

Columnar Projection Store generates an append-only, in-memory columnar store
for a Java interface. Annotate a projection schema, compile it with the
separate annotation processor, add source projections, seal the store, and read
rows through stable indexed views or allocation-conscious cursors.

Primitive-valued columns use primitive arrays. Reference-valued columns use
arrays of the accessors' erased return types and keep references, including
`null`; referenced objects are not copied or flattened. The runtime
abstractions and generic reflective factory live in
`io.github.jutil.columnarprojection`. The processor generates a public
schema-specific `<Projection>Store` contract and the supported concrete store
constructor into the schema package; all other generated implementation details
are unsupported. The library has no runtime dependencies and targets Java 8.

## Status and installation

Version `1.2.0` is the latest release and provides the generated, type-safe
batch API for appending column arrays while preserving the row-oriented API and
storage semantics introduced in `1.0.0`. Development version
`1.3.0-SNAPSHOT` adds generated synchronous methods for progressively filling
individual columns; distinct columns may be filled concurrently.
The installation example below therefore keeps the published `1.2.0`
coordinate; per-column filling is development API until a `1.3.0` release is
available.
Maven Central listings:
[runtime API](https://central.sonatype.com/artifact/io.github.j-util/columnar-projection-store)
and
[annotation processor](https://central.sonatype.com/artifact/io.github.j-util/columnar-projection-store-processor).

Javadocs:
[runtime API](https://javadoc.io/doc/io.github.j-util/columnar-projection-store)
and
[annotation processor](https://javadoc.io/doc/io.github.j-util/columnar-projection-store-processor).

The build produces two JARs. Runtime users depend only on
`io.github.j-util:columnar-projection-store`; configure
`io.github.j-util:columnar-projection-store-processor` only on the annotation
processor path. The processor is not required on the runtime classpath. This
Maven 3 / Maven Compiler Plugin 3.x configuration makes that boundary and
processor execution explicit:

```xml
<properties>
    <maven.compiler.release>8</maven.compiler.release>
    <columnar-projection-store.version>1.2.0</columnar-projection-store.version>
</properties>

<dependencies>
    <dependency>
        <groupId>io.github.j-util</groupId>
        <artifactId>columnar-projection-store</artifactId>
        <version>${columnar-projection-store.version}</version>
    </dependency>
</dependencies>

<build>
    <plugins>
        <plugin>
            <groupId>org.apache.maven.plugins</groupId>
            <artifactId>maven-compiler-plugin</artifactId>
            <version>3.15.0</version>
            <configuration>
                <annotationProcessorPaths>
                    <path>
                        <groupId>io.github.j-util</groupId>
                        <artifactId>columnar-projection-store-processor</artifactId>
                        <version>${columnar-projection-store.version}</version>
                    </path>
                </annotationProcessorPaths>
            </configuration>
        </plugin>
    </plugins>
</build>
```

Explicit processor configuration is important on modern JDKs: beginning with
JDK 23, `javac` no longer discovers and runs processors merely because they are
on the ordinary compile class path. `annotationProcessorPaths` also limits
processor discovery to the dependencies intentionally listed there.

## Complete example

```java
package example;

import io.github.jutil.columnarprojection.ProjectionCursor;
import io.github.jutil.columnarprojection.ProjectionSchema;
import io.github.jutil.columnarprojection.ProjectionStore;
import io.github.jutil.columnarprojection.ProjectionStores;

public final class OrderExample {

    @ProjectionSchema
    public interface OrderProjection {
        long id();

        String customer();

        int itemCount();

        default boolean isLarge() {
            return itemCount() >= 10;
        }
    }

    private static final class Order implements OrderProjection {
        private final long id;
        private final String customer;
        private final int itemCount;

        private Order(long id, String customer, int itemCount) {
            this.id = id;
            this.customer = customer;
            this.itemCount = itemCount;
        }

        @Override
        public long id() {
            return id;
        }

        @Override
        public String customer() {
            return customer;
        }

        @Override
        public int itemCount() {
            return itemCount;
        }
    }

    public static void main(String[] args) {
        ProjectionStore<OrderProjection> store =
                ProjectionStores.create(OrderProjection.class, 2);

        store.add(new Order(1001L, "Ada", 3));
        store.add(new Order(1002L, null, 12));
        store.seal();

        ProjectionCursor<OrderProjection> cursor = store.cursor();
        while (cursor.moveNext()) {
            // This object is reused and is valid only at the current row.
            OrderProjection row = cursor.current();
            System.out.println(row.id() + " " + row.customer()
                    + " large=" + row.isLarge());
        }

        // Unlike cursor.current(), an indexed view is stable and retainable.
        OrderProjection first = store.viewAt(0);
        System.out.println("first=" + first.id());
    }
}
```

For schema-specific use, prefer the generated `<Projection>Store` interface.
Its static `create(int)` method directly constructs the generated concrete
implementation known at compile time while exposing typed batch operations and
a nested typed column-appender contract.
Because the contract and implementation are generated into the schema package,
this path works in a named module even when that package is neither exported nor
opened.

Per-column filling does not replace or change `create(int)`, the generated
concrete store's public constructor, `ProjectionStores.create`, or the generated
batch interface. The common `ProjectionStore<T>` interface intentionally does
not expose the column appender.

`ProjectionStores.create(Projection.class, ...)` instead performs runtime
reflective discovery for schema-agnostic and row-oriented code. It returns the
common `ProjectionStore<T>` contract, whose static type does not expose
schema-specific batch setters or `columnAppender()`. Using `var` does not change
that: local-variable type inference uses the declared factory return type and
cannot derive a generated interface from `Class<T>`. If the schema is in a
named module, this generic reflective factory requires the module to export the
schema package or open it to the runtime module, for example:

```java
opens hidden to columnar.projection.store;
```

No export or open is required by the generated schema-specific factory.

The generated concrete store's public constructor remains compatible for
direct construction, but it is no longer necessary for typed batching. Runtime
abstractions remain in `io.github.jutil.columnarprojection`. Other generated
implementation details, such as backing fields, private batch implementations,
the private column-appender implementation, and view classes, are not supported
API.

## Schema-specific column appender and batch append

For a top-level projection named `PriceProjection`, the processor generates the
public `PriceProjectionStore` contract and the compatible concrete
`PriceProjection__ColumnarProjectionStore`. The contract's store-specific batch
accepts exactly the array type corresponding to each projection accessor.
Whole-array mode copies every element:

```java
@ProjectionSchema
public interface PriceProjection {
    double price();

    String symbol();
}

PriceProjectionStore prices = PriceProjectionStore.create(expectedSize);

prices.batch()
        .price(new double[] {15.1, 25.2})
        .symbol(new String[] {"A", "B"})
        .append();
```

### Per-column filling

For pipelines that already produce columns independently, the generated store
also declares a nested typed appender. It has two synchronous methods with each
projection accessor's exact name. The whole-array overload appends every
element; the range overload appends the half-open source range. Accessor-named
filling methods are not declared directly on the store:

```java
@ProjectionSchema
interface Trade {
    long timestamp();
    double price();
    String symbol();
}

TradeStore trades = TradeStore.create(expectedSize);
TradeStore.ColumnAppender columns = trades.columnAppender();

columns.timestamp(timestampChunk);
columns.timestamp(moreTimestamps, fromIndex, toIndex);

columns.price(priceChunk);
columns.price(morePrices, differentFromIndex, differentToIndex);

columns.symbol(symbolChunk);
columns.symbol(moreSymbols);

// Join any caller-managed filling tasks before sealing.
trades.seal();
```

The official generated store returns the same store-owned appender object from
every `columnAppender()` call. Each appender method copies synchronously and
retains no source-array reference, so the caller may reuse or mutate the source
after successful return. The selected source elements must remain stable until
the call returns. Chunk boundaries may differ between columns. Each column owns
its append count and grows only its own backing array. Calls for distinct
columns may execute concurrently; calls to the same column require external
single-writer serialization. No column call waits for another column.

`columnAppender()` is a default method on the generated store contract. This
lets an external implementation or decorator written against the generated
1.2.0 contract continue to link and recompile without adding a method. Its
inherited default throws `UnsupportedOperationException`; the official
processor-generated implementation overrides it with the appender described
above. The generated concrete store itself does not implement the appender
interface or expose accessor-named filling methods.

During per-column filling, `size()` remains zero. After the caller has joined
all filling tasks, `seal()` compares every generated column count. A successful
seal publishes the common count as the logical size. A mismatch throws
`IllegalStateException` with column/count information, leaves the store
unsealed, preserves all copied values, and permits corrective filling followed
by another `seal()` call. A sealed multi-column store always requires equal
column counts. Missing columns are not inferred as `null`, zero, or any other
default. An untouched store still seals with size zero.

### Typed batch behavior

Common-range mode applies one half-open source range to every column:

```java
prices.batch(sourceFromIndex, sourceToIndex)
        .price(priceValues)
        .symbol(symbols)
        .append();
```

Generated top-level names use the projection's binary name. Consequently, a
member schema named `Outer.PriceProjection` generates the top-level contract
`Outer$PriceProjectionStore`, not `PriceProjectionStore` nested inside `Outer`.

`sourceFromIndex` is inclusive and `sourceToIndex` is exclusive. They are
indexes into each supplied source array, never indexes into the store. Both
modes always append at the store's current size when `append()` executes; they
cannot address, replace, or overwrite existing rows.

The returned nested type is ordinarily named `Batch`. If that name would
shadow the first identifier of a type reference required by generated source,
the processor chooses a deterministic collision-safe name by appending
underscores. This covers named-package roots observable through the bounded
inputs described below and unnamed-package top-level types. Chained use through
either `batch` factory, as above, does not require callers to spell the nested
type name.

The column-appender contract is ordinarily named `ColumnAppender`. Its nested
contract and private implementation names use the same deterministic
collision-safe underscore rule as the batch types, so neither name shadows a
source type or package required by generated signatures. Chained use through
`columnAppender()` avoids depending on the selected nested type name.

The generated store contract ordinarily uses the projection's binary simple
name followed by `Store`. If that candidate is a package observable through the
inputs described below or would shadow a source type root needed by the
contract, the processor appends underscores until neither conflict remains. For
example, a schema named `example.Schema` generates `example.SchemaStore_` when
the package `example.SchemaStore` is observable, and `example.SchemaStore__`
when both `example.SchemaStore` and `example.SchemaStore_` are observable. If
the selected generated contract or the fixed concrete implementation name is
already declared by user code, compilation fails with a generated-name
collision diagnostic; the processor does not rename around, overwrite, or
silently skip a user type.

Package detection uses only Java 8-compatible standard annotation-processing
and language-model APIs; the processor does not scan arbitrary class-path
directories or use compiler internals. It supports ordinary class-path
compilation and one named or unnamed source module per compiler invocation.
Maven reactor modules remain supported when Maven invokes the compiler
separately for each module. If an invocation that contains a projection schema
has roots from more than one source module, compilation fails with a diagnostic
requiring separate compilation. That check occurs before schema preparation,
name selection, or generated-file creation, so it leaves no partial generated
output.

Before selecting any generated name in a supported compilation, the processor
records every ancestor prefix of every package represented by a current type or
package root. For example, a root declared in `example.SchemaStore.sub` records
`example`, `example.SchemaStore`, and `example.SchemaStore.sub`. For a single
named source module, the module root is not treated as a source type:
annotations directly present on it are inspected, and directly enclosed
`PackageElement` instances contribute package prefixes. Package members are not
recursively enumerated, and class-path types are not reclassified as current
source. Collection is independent of source-file and root-element order.

The processor cycle-safely examines current top-level and nested declarations:
types, fields and enum constants, methods and constructors, parameters and type
parameters, and record components when the running compiler supports them. It
also examines package-info declarations and annotations directly present on the
module declaration. Declaration-signature traversal covers superclasses and
interfaces, field types, method returns, constructor and method parameters,
executable receiver types, thrown types, and type-parameter bounds. It
recursively follows arrays, generic arguments, wildcards, enclosing types, type
variables, intersections, and declared or unresolved error types. Resolved
return types of effective projection accessors, including inherited accessors,
are also included.

Annotations on each such declaration and on every recursively visited
`TypeMirror` are included. Traversal follows each annotation's declared type,
explicitly supplied values, class-literal types, enum constants, nested
annotations, and annotation-value arrays. It also follows default values
declared by annotation members that are themselves current source. Arbitrary
members and default values of external annotation types are not inspected.
Type-use annotations can therefore affect collision-safe naming, but they
remain intentionally omitted from generated source-array signatures; the
projection interface remains authoritative for them.

The processor cannot observe external types referenced only by module
`uses`/`provides` directives, imports without a declaration-signature use,
method or constructor bodies, initializer or anonymous-class bodies, genuinely
unreferenced descendant class-path packages, or packages first created by
another annotation processor in a later round after names have been selected
and emitted. If a candidate parent package would otherwise be invisible, place
`package-info.java` in that candidate parent package and make it available in
the schema's naming round, either as current source or through its
`package-info.class`. This makes the parent prefix observable for naming.

The provenance marker used to recognize processor-owned output exists only in
output generated by the 1.2 processor. When upgrading from a pre-1.2 processor,
remove the old class output and generated-source output and perform one clean
build. An unmarked generated implementation left by an earlier processor cannot
be distinguished safely from an external user type.

After that one-time clean build, the processor recognizes its own 1.2-generated
contract and concrete implementation from a previous compilation. An unchanged
schema, or a schema with changed accessors but unchanged generated top-level
names, can therefore be compiled again with the previous class output on the
class path without a clean build. Current source declarations, external
class-path types, and cross-schema conflicts remain collisions. If schema
evolution selects a different generated top-level name and stale output cannot
be removed safely, compilation reports the specific stale name and requests a
clean rebuild.

For a parameterized accessor, its batch setter and column-appender methods
preserve the resolved declared return type whenever every part of that type can
legally be named from the generated store. For example,
`List<String> labels()` produces source parameters accepting `List<String>[]`;
an `ArrayList<String>[]` is compatible while an `ArrayList<Integer>[]` is
rejected at compilation. Backing columns still use erased, reifiable arrays
such as `List[]`. If a generic argument is inaccessible to the generated
top-level class, the source parameter falls back to the erased array type. This
fallback preserves schemas accepted by earlier versions, but necessarily loses
generic argument checking for that column. Generated source-array signatures
preserve source-nameable Java type structure and generic arguments, but
intentionally omit type-use annotations. The projection interface remains
authoritative for type-use annotations.

In whole-array mode, the first non-null array successfully assigned to a
column establishes the row count. Every later column array must have exactly
that length. An unequal array throws `IllegalArgumentException` before that
column is assigned, so it remains available for correction. Every generated
batch column setter must be called exactly once, even for an empty whole-array
batch.
Consequently, `batch().append()` fails with `IllegalStateException`; represent
an empty whole-array batch by supplying an empty array for every column.

In common-range mode, creation requires
`0 <= sourceFromIndex <= sourceToIndex`; an invalid range throws
`IndexOutOfBoundsException`. Every supplied array must have length at least
`sourceToIndex`. Arrays may have different physical lengths as long as each
contains the complete range. A too-short array throws
`IndexOutOfBoundsException` before that column is assigned, so it remains
available for correction. A positive range requires every generated column
method exactly once. An empty explicit range may be appended as a no-op without
any column assignments; if a column is supplied, it is still validated against
`sourceToIndex`.

In both batch modes, a column rejects `null` with `NullPointerException`, and a
second successful assignment to the same column throws
`IllegalStateException`. Missing-column failures leave the unfinished batch
available for correction and retry.

An unfinished batch retains its source arrays but does not copy them until
`append()` executes. Mutations to selected source-array elements before
`append()` are therefore visible. A successful positive append reserves
destination capacity once, performs one `System.arraycopy` from each column,
and increases the logical size only after all column copies complete. It does
not modify the source arrays and releases its references to them after success.
Later replacement or mutation of source-array elements does not change the
stored values. Reference-valued elements still use the store's shallow-copy
semantics: the references are copied, while the referenced objects, including
array-valued projection results, are not cloned.

Column-appender methods have different ownership: they validate and copy during
the method call and never retain the source array. Selected source elements must
remain stable until the call returns. On successful return, replacing or
mutating source-array elements cannot change stored column values. As with batch
append, reference elements are shallow-copied; mutating an object already
referenced by both source and store remains visible through that object.

A null column-appender source throws `NullPointerException` while the store is
building. Range overloads require
`0 <= sourceFromIndex <= sourceToIndex <= source.length` and validate the range
before selecting a mutation mode or changing capacity. Null elements in
reference columns are valid. An all-null reference array contributes its full
length. Primitive columns have no null representation; boxed accessor types are
reference columns and may contain null elements. Empty arrays and empty ranges
are valid no-ops.

The destination starts at the store's size when `append()` executes, not when
the batch is created. Consequently, multiple unfinished whole-array and ranged
batches may be appended in any order, and their successful append calls
interleave with `add` in execution order. Ordinary validation happens before
the logical size changes. After a successful append, including an empty-range
no-op, the batch is consumed and cannot be reused.

`batch` and `append` are building-state operations. Creating a batch on a
sealed store fails, and sealing after batch creation makes `append()` fail
before any column is copied. Batch construction, column assignment, `append`,
`add`, and `seal` are not thread-safe and must not be called concurrently.

## Store lifecycle and failure behavior

A new store is logically empty and in its building state. `expectedSize` is an
initial-capacity hint, not a row limit; zero is valid and the store grows when
necessary.

- While building, use either row mode (`add` and batch append) or per-column
  mode. Calling `cursor` or `viewAt` before sealing throws
  `IllegalStateException`.
- `seal` permanently moves the store to its read state after validating column
  counts. Sealing an untouched empty store is valid, and repeated successful
  calls are harmless. An unequal-count failure leaves the store building and
  can be retried after corrective filling.
- After sealing, `size`, `cursor`, and `viewAt` are available. `add` throws
  `IllegalStateException`, as do all generated column-appender methods; a store
  cannot be unsealed.

The first positive `add` or batch append selects row mode. The first positive
per-column call selects column mode. Positive operations from the other mode are
rejected before store mutation. Empty no-ops and validation failures do not
select a mode. Existing `add` and batch append operations remain mixable with
each other in row mode.

In column mode, the store intentionally exposes no partially aligned logical
rows: `size()` stays zero until a successful seal. `seal()` never waits for
column filling and must not run concurrently with it. The caller is responsible
for joining all filling tasks first. If counts differ, the exception reports a
mismatched column pair and their counts; no values are discarded, and missing
columns are never synthesized from null or primitive defaults.

`add` immediately evaluates the projection accessors and copies their results;
it never retains the source projection. Each accessor reached during an add
attempt is invoked exactly once. Evaluation order is unspecified, and if an
accessor throws, evaluation stops, the same exception is propagated, and no
logical row is appended. Side effects from accessors already invoked are not
rolled back. Primitive results are copied by value. Reference results,
including `null`, are copied as references without a deep copy. The source
projection must not be mutated concurrently while it is being read.

While building, passing a `null` projection to `add` throws
`NullPointerException`. Every generated factory and
`ProjectionStores.create` rejects a negative `expectedSize` with
`IllegalArgumentException`. For `ProjectionStores.create`, a `null` projection
type throws `NullPointerException`, and a non-interface projection type produces
`IllegalArgumentException`. A missing generated implementation produces
`IllegalStateException` whose message explains that the processor artifact must
be configured on the annotation-processor path; the original class-loading
failure is retained as the cause. Malformed or incompatible generated code and
any failure to instantiate it also produce `IllegalStateException` with the
underlying cause where one is available. If reflective constructor access is
denied, the message explains the named-module export/open requirement and the
`IllegalAccessException` remains the cause.

`viewAt(index)` returns a stable, read-only projection permanently bound to one
row. It throws `IndexOutOfBoundsException` for an invalid index after the store
has been sealed. Calls are not guaranteed to return the same object for the same
index, so callers must not depend on view identity or caching.

## Cursor semantics

A new cursor starts before the first row. `moveNext()` positions it and returns
`true`, or returns `false` when exhausted. Once exhausted it remains exhausted
until `rewind()` is called. `current()` is valid only after a successful
`moveNext()` and before exhaustion or rewind; otherwise it throws
`IllegalStateException`.

Each cursor owns one reusable projection view. `current()` returns that same
view as the cursor advances, so do not retain it or compare its identity across
rows. Use `viewAt` when a row projection must outlive the current cursor
position. Cursors own no external resources and are not `AutoCloseable`.

## Schema validation

The annotation processor rejects an invalid schema during compilation. A valid
schema follows these rules:

- The annotated type is a non-generic interface declared at top level or as a
  member interface. It and each enclosing type must not be private.
- Each effective abstract instance method defines one column. Inherited
  accessors are included; overridden or compatible covariant declarations are
  resolved to their effective accessor.
- An accessor has no parameters or method type parameters, returns a non-void
  value, and declares no checked exceptions.
- The erased accessor return type is accessible to a generated top-level class
  in the schema's package. Primitive and reference return types are supported.
- Static methods are not columns. Default methods provide behavior and are not
  stored columns. Conflicting inherited abstract/default declarations or
  incompatible inherited return types are rejected.
- The schema declares or inherits at least one effective abstract accessor.
  Schemas with no methods or only default/static methods are rejected.
- Abstract declarations that conflict with `java.lang.Object` methods, such as
  `toString()`, `hashCode()`, or `equals(Object)`, are rejected as columns.

The schema interface itself cannot declare type parameters. Fully parameterized
generic parent interfaces are allowed; raw generic parents are rejected, and
inherited accessors are resolved in the annotated schema's type context.

## Complexity and storage

Let `c` be the number of stored accessors, `r` the current row count, `n` the
number of selected source values, `capacity` a backing-array length, and
`newCapacity` its length after a growth step. The bounds below describe store
overhead; they exclude work performed inside user-written accessor bodies and
one-time JVM class loading.

| Operation | Time |
| --- | --- |
| Generated `<Projection>Store.create` or `ProjectionStores.create` | `O(c * (capacity + 1))` for the requested initial capacity |
| `add` | Amortized `O(c)`; a growth step is `O(c * newCapacity)` |
| Generated `batch` construction | `O(c)` |
| A batch column assignment | `O(1)` |
| Batch `append` | Without growth, `O(c * (n + 1))`; with growth, `O(c * (newCapacity + n + 1))` |
| `columnAppender()` | `O(1)` |
| One column-appender whole-array or range call | Without growth, `O(n)`; with growth, `O(newCapacity + n)` for that column only |
| `seal` | `O(c)` count validation |
| `size`, `cursor`, `viewAt` | `O(1)` |
| Cursor `moveNext`, `current`, `rewind` | `O(1)` |
| A generated projection accessor | `O(1)` |

In row mode, retained column storage is `O(c * capacity)` array slots. Shared
row/batch growth can temporarily require another `O(c * newCapacity)` slots
while existing columns are copied. At the peak, both generations can be live,
for `O(c * (capacity + newCapacity))` backing-array slots. This includes a large
batch that makes `newCapacity` jump from `capacity` to at least `r + n`; its
temporary growth storage is not bounded by `O(c * r)`. `expectedSize` can avoid
those copies when the eventual row count is known, but it allocates that initial
capacity for every column.

For a positive batch, append time is linear in the total number of copied
values (`c * n`) in addition to any growth work. An unfinished batch retains
one source-array reference per assigned column until it is successfully
appended. In common-range mode, caller-owned source arrays may be much longer
than `n`, so the amount of source storage kept reachable is not bounded by the
selected row count.

In column mode, capacities may differ. Retained backing storage is the sum of
the individual column capacities. A growth step temporarily retains only that
column's old array and new array and never grows, copies, or replaces another
column. A column-appender call uses constant additional bookkeeping beyond any new
backing array allocated for growth, and it retains no caller source after
successful return.

## Thread safety

Calls to generated column-appender methods for distinct columns may execute
concurrently. Each column is single-writer: calls targeting the same column
must be externally serialized. A column-appender call never waits for another
column and does not lock or grow another column on its normal copy path.

Do not invoke `add`, either `batch` factory, batch column methods, batch
`append`, `size`, `seal`, `cursor`, or `viewAt` concurrently with per-column
filling. Join the caller-managed filling tasks before sealing. `seal()` performs
only count validation and never waits for filling to finish. Row mutation and
batch mutation remain externally serialized with each other.

After `seal()` and safe publication to reader threads, the store and stable
indexed views may be read concurrently. Each thread must use its own cursor;
cursors are not thread-safe.

Safe publication applies only to the stored references. The library does not
make mutable referenced objects immutable or thread-safe.

## V1 limitations and non-goals

- Stores are in-memory, append-then-read structures. V1 has no update, removal,
  clear, unseal, or persistence API.
- References are opaque and are never recursively flattened, cloned, or
  serialized.
- Annotation processing is required; there is no reflective or dynamic-proxy
  storage fallback.
- Runtime abstractions are supported only in
  `io.github.jutil.columnarprojection`. In each schema package, the generated
  `<Projection>Store` contract and concrete store constructor are also
  supported; all other generated implementation details are unsupported.
- JPMS annotation-processing support is limited to the
  one-source-module-per-compiler-invocation boundary described above. V1 makes
  no native-image compatibility guarantee.
- Query planning, indexing, filtering, aggregation, schema migration, and
  durability are outside the V1 scope.

## Building

Run the complete local quality gate from the repository root:

```shell
./mvnw clean verify
```

The project is licensed under the [Apache License 2.0](LICENSE).
