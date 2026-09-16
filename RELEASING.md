# Releasing

Columnar Projection Store releases the core and annotation-processor artifacts
together from the same commit. Choose a release version `<version>` and use the
annotated tag `v<version>`.

For a pre-release compatibility evaluation, keep the snapshot version and do
not run the tagging or publishing steps below. Download both published 1.2.0
JARs from Maven Central into a temporary directory and run:

```shell
./mvnw clean verify \
  -Dcompatibility.baseline.core=/absolute/path/columnar-projection-store-1.2.0.jar \
  -Dcompatibility.baseline.processor=/absolute/path/columnar-projection-store-processor-1.2.0.jar
```

The generated compatibility fixture then invokes the published processor in a
separate `javac` process, compiles a consumer against that generated API, runs
it on both versions, recompiles it against the candidate, and checks the
default unsupported appender on a precompiled external implementation. Without
the two properties, the normal build uses its self-contained legacy API fixture.
Both properties must be supplied together. Run the published-artifact check on
Java 8 and the current CI JDK. Also compare the runtime and processor JARs with
an API comparison tool and compile a clean consumer outside the reactor.

For per-column performance evidence, use the separate
[`HardwoodDestinationMaterializationBenchmark`](https://github.com/j-util/performance-lab#hardwooddestinationmaterializationbenchmark)
in `performance-lab`. Keep Parquet decoding outside the measured operation,
include typed batches, synchronous and fixed-pool appenders, and an exact-capacity
`ArrayList`, and repeat the large workload with time and allocation profiling.
Record the environment, commands, exact source commits and per-fork variation.
Small workloads need not win; an unstable or unfavorable large-workload result
blocks a performance-based release verdict.

1. Confirm that the worktree contains only the intended release changes and
   that neither release coordinate at `<version>` already exists on Maven
   Central.
2. Set the root aggregator, core module, processor module, and the processor's
   dependency on core to `<version>`. Set both published-module SCM tags to
   `v<version>`, and update the changelog and stable-version documentation.
3. Run the complete local verification:

   ```shell
   ./mvnw clean verify
   ./mvnw javadoc:javadoc
   ./mvnw dependency:tree
   ./mvnw -Prelease -Dcentral.skipPublishing=true clean verify
   git diff --check
   ```

4. Run the no-upload rehearsal explicitly with publishing skipped:

   ```shell
   ./mvnw -Prelease -Dcentral.skipPublishing=true clean deploy
   ```

   Inspect the generated Central bundle before continuing. It must contain only
   the core and processor coordinates at `<version>`, with their POM, main JAR,
   sources JAR, Javadoc JAR, signatures, and checksums. Publishing is skipped by
   default; the explicit `true` makes the rehearsal's no-upload boundary clear.
5. Commit the verified release content and confirm that CI succeeds for that
   exact commit.
6. Create the annotated tag `v<version>` at that commit and confirm that the tag
   points to it.
7. From the tagged commit, perform the real tagged upload with the required
   explicit override:

   ```shell
   ./mvnw -Prelease -Dcentral.skipPublishing=false clean deploy
   ```

8. Wait for the deployment to pass Maven Central validation. Because
   `autoPublish=false`, explicitly publish the validated deployment in the
   Central Publisher Portal.
9. Create the GitHub release from `v<version>`.
10. From a fresh temporary Maven repository, resolve both published artifacts,
   compile a minimal annotated schema with the processor only on the annotation
   processor path, and run the consumer with only its own classes and the core
   artifact on the runtime classpath.
11. Advance the root aggregator, both modules, and the processor's dependency on
    core to the next `-SNAPSHOT` development version. Change both
    published-module SCM tags back to `HEAD`, while leaving stable-version
    installation examples on the latest published release.
