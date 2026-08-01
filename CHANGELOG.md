# Changelog

This file records user-visible changes to Columnar Projection Store.

## 0.1.0-SNAPSHOT (unreleased)

### Added

- `@ProjectionSchema` and its compile-time processor for generating dedicated
  columnar stores from non-generic projection interfaces.
- Support for primitive and opaque reference columns, inherited accessors, and
  schema default methods.
- `ProjectionStores.create` with a growable expected-size capacity hint.
- Append and seal lifecycle with failure-atomic logical row addition.
- Stable indexed row views and reusable, rewindable cursors.
- Concurrent reads after sealing and safe publication, using an independent
  cursor per thread.
- Java 8 compatibility, Maven build verification, source artifacts, Javadocs,
  and Apache-2.0 licensing.

This snapshot has not been published as a release.
