# Releasing

Columnar Projection Store releases the core and annotation-processor artifacts
together from the same commit. Version `1.0.0` uses the annotated tag
`v1.0.0`.

1. Confirm that the worktree contains only the intended release changes and
   that neither release coordinate already exists on Maven Central. Run the
   complete local verification:

   ```shell
   ./mvnw clean verify
   ./mvnw javadoc:javadoc
   ./mvnw dependency:tree
   ./mvnw -Prelease clean verify
   ./mvnw -Prelease -Dcentral.skipPublishing=true clean deploy
   git diff --check
   ```

   Inspect the generated Central bundle before continuing. It must contain
   only the core and processor coordinates, with their POM, main JAR, sources
   JAR, Javadoc JAR, signatures, and checksums.

2. Commit the verified release content and confirm that CI succeeds for that
   exact commit.
3. Create the annotated tag `v1.0.0` at that commit and confirm that the tag
   points to it.
4. From the tagged commit, run `./mvnw -Prelease clean deploy` with the
   maintainer's secure Maven Central and GPG configuration available.
5. Wait for the deployment to pass Maven Central validation. Automatic
   publication is disabled by the build.
6. Explicitly publish the validated deployment in the Central Publisher
   Portal.
7. Create the GitHub release from `v1.0.0`.
8. From a fresh temporary Maven repository, resolve both published artifacts,
   compile a minimal annotated schema with the processor only on the annotation
   processor path, and run the consumer with only its own classes and the core
   artifact on the runtime classpath.
