# Releasing

Columnar Projection Store releases the core and annotation-processor artifacts
together from the same commit. Choose a release version `<version>` and use the
annotated tag `v<version>`.

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
