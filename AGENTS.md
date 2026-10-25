# Instructions for AI Coding Agents

Start at [spec/CONTEXT.md](spec/CONTEXT.md). The code is the source of truth; when a spec disagrees, fix
the spec in the same change.

## The public Java API is locked

`graph-core`, `graph-cypher`, `graph-protocol` and `graph-client` each have an `ApiLockTest` that fails the
build on any change to their public Java API, compared against `<module>/api/<module>.api`. Accepting an API
change is a human decision. See [spec/API_LOCK.md](spec/API_LOCK.md).

- **Never** run `tools/api-lock.sh accept` or `tools/api-lock.sh policy`, or `ApiLockTool` directly.
  `tools/api-lock.sh check` is read-only and fine.
- **Never** edit a `*/api/*.api` baseline or its `sha256` line.
- **Never** weaken, skip, disable, exclude or delete an `ApiLockTest`, or narrow a scope in `LockedModule`.
- **When the lock fails:** make the change additive — a new overload, a new default method, deprecate
  instead of remove ([spec/API_LOCK.md §3.1](spec/API_LOCK.md)) — or **stop and report the diff to the
  user** and let them decide.

## The on-disk format is versioned

Any change to the bytes a store writes — a record layout, a page header, a value tag, the manifest, a
dictionary or WAL encoding — follows [spec/MIGRATION.md §6](spec/MIGRATION.md): bump the store format
version, write the migration step, generate the next golden fixture with `tools/golden-fixture.sh`.

- **Never** regenerate, edit or delete an existing `graph-core/src/test/resources/format/v<N>/` fixture: it
  is the only evidence of what version N looked like.
- **Never** change the expected contents in `GoldenFixtureTest` to make it pass. A failing golden fixture
  means the format changed without a version bump — stop and report it.

## Releases

Versions, `build.sh` and the smoke test: [spec/RELEASE.md](spec/RELEASE.md). `./build.sh --release` creates
a git tag; do not run it, or push anything, unless the user asks.
