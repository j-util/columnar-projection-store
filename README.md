# Columnar Projection Store

Columnar Projection Store generates an append-only, in-memory columnar store
for a Java interface. Annotate a projection schema, compile it with the
separate annotation processor, add source projections, seal the store, and read
rows through stable indexed views or allocation-conscious cursors.

Primitive-valued columns use primitive arrays. Reference-valued columns keep
opaque references, including `null`; referenced objects are not copied or
flattened. The application-facing API is in
`io.github.jutil.columnarprojection`. The library has no runtime dependencies
and targets Java 8.

## Status and installation

Version `1.0.0` is the first functional release of Columnar Projection Store.

The build produces two JARs. Runtime users depend only on
`io.github.j-util:columnar-projection-store`; configure
`io.github.j-util:columnar-projection-store-processor` only on the annotation
processor path. The processor is not required on the runtime classpath. This
Maven 3 / Maven Compiler Plugin 3.x configuration makes that boundary and
processor execution explicit:

```xml
<properties>
    <maven.compiler.release>8</maven.compiler.release>
    <columnar-projection-store.version>1.0.0</columnar-projection-store.version>
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

The generated class is public so the factory can instantiate it, but generated
class names, constructors, and implementation shape are not supported API.
Always create stores through `ProjectionStores`.

## Store lifecycle and failure behavior

A new store is logically empty and in its building state. `expectedSize` is an
initial-capacity hint, not a row limit; zero is valid and the store grows when
necessary.

- While building, call `add` and `size`. Calling `cursor` or `viewAt` before
  sealing throws `IllegalStateException`.
- `seal` permanently moves the store to its read state. Sealing an empty store
  is valid, and repeated calls are harmless.
- After sealing, `size`, `cursor`, and `viewAt` are available. `add` throws
  `IllegalStateException`; a store cannot be unsealed.

`add` immediately evaluates the projection accessors and copies their results;
it never retains the source projection. Each accessor reached during an add
attempt is invoked exactly once. Evaluation order is unspecified, and if an
accessor throws, evaluation stops, the same exception is propagated, and no
logical row is appended. Side effects from accessors already invoked are not
rolled back. Primitive results are copied by value. Reference results,
including `null`, are copied as references without a deep copy. The source
projection must not be mutated concurrently while it is being read.

While building, passing a `null` projection to `add` throws
`NullPointerException`. Creating a store with a `null` projection type also
throws `NullPointerException`; a negative `expectedSize`, a non-interface
projection type produces `IllegalArgumentException`. A missing generated
implementation produces `IllegalStateException` whose message explains that
the processor artifact must be configured on the annotation-processor path;
the original class-loading failure is retained as the cause. Malformed or
incompatible generated code and any failure to instantiate it also produce
`IllegalStateException` with the underlying cause where one is available.

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

Let `c` be the number of stored accessors, `r` the current row count, and `a`
the total number of initially allocated column slots implied by
`expectedSize`. The bounds below describe store overhead; they exclude work
performed inside user-written accessor bodies and one-time JVM class loading.

| Operation | Time |
| --- | --- |
| `ProjectionStores.create` | `O(c + a)` |
| `add` | Amortized `O(c)`; a growth step is `O(c * r)` |
| `size`, `seal`, `cursor`, `viewAt` | `O(1)` |
| Cursor `moveNext`, `current`, `rewind` | `O(1)` |
| A generated projection accessor | `O(1)` |

The retained column storage is `O(c * capacity)` array slots. Growth can
temporarily require another `O(c * r)` slots while existing columns are copied.
`expectedSize` can avoid those copies when the eventual row count is known, but
it allocates that initial capacity for every column.

## Thread safety

Building a store is not thread-safe. Do not call `add` or `seal` concurrently,
and do not read through views or cursors while building. After `seal()` and safe
publication to reader threads, the store and stable indexed views may be read
concurrently. Each thread must use its own cursor; cursors are not thread-safe.

Safe publication applies only to the stored references. The library does not
make mutable referenced objects immutable or thread-safe.

## V1 limitations and non-goals

- Stores are in-memory, append-then-read structures. V1 has no update, removal,
  clear, unseal, or persistence API.
- References are opaque and are never recursively flattened, cloned, or
  serialized.
- Annotation processing is required; there is no reflective or dynamic-proxy
  storage fallback.
- Generated public classes are unsupported implementation details.
- V1 makes no explicit JPMS or native-image compatibility guarantee.
- Query planning, indexing, filtering, aggregation, schema migration, and
  durability are outside the V1 scope.

## Building

Run the complete local quality gate from the repository root:

```shell
./mvnw clean verify
```

The project is licensed under the [Apache License 2.0](LICENSE).
