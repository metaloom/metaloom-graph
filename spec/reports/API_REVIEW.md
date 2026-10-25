# API Stability Review

The Phase 9 review of the public surface, and an explicit recommendation on the 1.0 freeze that phase asks for.

**Recommendation: do not freeze.** The reasons are in §4 and they are specific, not cautious-by-default.

> **Since 2026-10-25 the surface is *locked*, which is not the freeze this review argues against.** Every API
> change now fails the build until a person accepts it, and during the alpha breaking changes may still be
> accepted — [API_LOCK.md](../API_LOCK.md). Parts of §1 predate `module-info.java`: the exported package is
> now `...core.api` alone, and `ElementId`, `Kind` and `StaleIdException` sit in the unexported `...core.id`.
> `Kind` is still named by exported signatures, which [API_LOCK.md §2](../API_LOCK.md) records as an open
> decision.

---

## 1. What Is Public

Everything in `io.metaloom.graph.core.api`, plus `ElementId` and `Kind` in `io.metaloom.graph.core.id`. Nothing else
is intended to be: `store`, `segment`, `index`, `wal`, `maintenance`, `metrics`, `catalog`, `page` and `util` are
implementation and are only public because there is no `module-info.java` yet.

| Type | Role | Assessment |
|------|------|------------|
| `GraphStore` | The whole API surface: lifecycle, elements, traversal, indexed lookup, maintenance | 34 methods. Coherent, but see §3 and §4 |
| `GraphStoreOptions` | Builder; format-affecting options recorded in the manifest | Stable |
| `Transaction` | `commit` / `rollback` / `close`, plus `id` and `touchedPages` | Stable |
| `Properties` | Typed property bag; absent and null are distinguishable | Stable |
| `Edge`, `Direction` | One traversal result and its direction | Stable |
| `StoreStats` | Counts and byte totals | Stable, but a record — adding a field breaks the constructor |
| `StoreMetrics`, `IoStats` | Counters and gauges | New in Phase 9; records, same caveat |
| `CompactionOptions`, `CompactionReport` | What compaction may do, and what it did | New in Phase 8 |
| `VerifyLevel`, `VerifyOptions` | Verification depth and throttling | `VerifyLevel` is missing the `STRUCTURE` value its own javadoc mentions |
| `WalSyncMode` | Durability of a commit | Stable |
| Exceptions | `GraphStoreException` and seven subtypes | See §2 |
| `ElementId`, `Kind` | Id arithmetic; ids are plain `long` values | Stable and load-bearing |

---

## 2. What the Review Found

### 2.1 `StaleIdException` is in the wrong package

It extends `GraphStoreException` and is thrown across the public API, but it lives in `io.metaloom.graph.core.id`
rather than `...core.api`. A caller writing `catch (GraphStoreException e)` is unaffected; a caller importing the
specific type has to reach into a package that is otherwise internal. **Fix before 1.0**, by moving it.

### 2.2 `VerifyLevel` documents a value it does not have

`GraphStore.verify` refers to `CHECKSUMS | STRUCTURE | FULL`, and [API.md](../API.md) does too, but the enum has only
`CHECKSUMS` and `FULL`. Either add `STRUCTURE` or stop mentioning it. **Fix before 1.0** — a documented constant that
does not exist is worse than a missing feature.

### 2.3 The records will break on extension

`StoreStats`, `IoStats`, `StoreMetrics`, `CompactionReport` are records, so their canonical constructors are public
API and adding a field is a source and binary break. They are all *outputs*, never constructed by callers, so the
practical risk is low — but freezing them means freezing the field list. Builders would cost more than they are worth
here; the right answer is to acknowledge that these four evolve and not to freeze them at 1.0.

### 2.4 `IoStats` exposes mutable arrays

`recordReadsByKind()` returns the internal `long[]`. A caller can write to it. Accessors `recordReads(Kind)` exist and
are what callers should use. **Fix before 1.0** by cloning, or by dropping the array components in favour of a map.

### 2.5 `compact()` has no way to say "and do not move ids"

Compaction is the only id-invalidating operation, and the API makes that clear in the javadoc, in
[API.md](../API.md) and in `CompactionReport`. But a caller who wants only the safe half has to call `reclaim()`
instead, and the relationship between the two is documentation rather than types. It works; it is not discoverable.

### 2.6 The read path is documented as single-writer but the interface does not say so per method

`GraphStore.begin()` says only one transaction may be active and others block. That is right, but a method-level
reader would not learn from `createNode` that it may block indefinitely behind another writer. Worth a sentence per
mutating method, not a redesign.

### 2.7 Nothing exposes the store's own liveness

`CompactionReport` reports liveness before and after, but there is no way to ask "should I compact?" without running
a compaction. `StoreStats` would be the natural home. Small gap, real.

---

## 3. What Is Good

Worth recording, because a review that only lists problems gives a false impression:

- **Ids are plain `long` values.** No wrapper, no allocation, and they work as map keys and array indices. This is the
  single best decision in the API and it is a direct consequence of the addressing design.
- **`Properties` distinguishes absent from null**, which the proof of concept's `Map<String,String>` could not.
- **The exception hierarchy is genuinely useful.** One root, subtypes that mean different things, and every message
  names the segment, page or slot involved. The hostile-input work leaned on this heavily.
- **Maintenance is explicit.** `checkpoint`, `reclaim`, `compact` and `verify` are calls, not background threads.
  For an embedded store that is the right default: the embedder knows when it can afford them.
- **Auto-commit and explicit transactions are the same methods.** A mutating call joins the caller's transaction if
  there is one. That is why the batching advice in [OPERATIONS.md](../OPERATIONS.md) §3 is a one-line change for a
  caller rather than a rewrite.

---

## 4. Why Not To Freeze at 1.0

A freeze is a promise about the future, and three things that are not built would change this surface.

1. **MVCC.** Phase 5 is unimplemented. Snapshot isolation adds a read transaction — a concept the API currently has no
   type for, since `begin()` returns a write transaction and reads take none. That is not an addition; it changes what
   a read *is*.
2. **Cursors.** [API.md](../API.md) specifies `NodeCursor`, `EdgeCursor` and `TraversalCursor` for streaming, and
   `neighbours` currently returns a materialised `List`. Adding streaming later means either a second set of methods
   or changing these return types.
3. **Backup.** `snapshot(Path)` is designed and absent. Whatever fills that gap will be public surface, and §5 of
   [OPERATIONS.md](../OPERATIONS.md) is the reason it has to exist before anyone depends on this in production.

Freezing now would either lock in a shape that cannot accommodate those, or produce a 2.0 shortly afterwards. The
honest position is: **the API is stable in practice and unfrozen in principle.** The four fixes in §2.1, §2.2 and
§2.4 should be made regardless, because they are defects rather than design decisions.

---

## 5. Suggested Sequence

| Step | Change | Breaking? |
|------|--------|-----------|
| 1 | Move `StaleIdException` to `...core.api` | Source-breaking for anyone catching it specifically |
| 2 | Add `VerifyLevel.STRUCTURE`, or remove it from the docs | No |
| 3 | Clone the arrays `IoStats` returns | No |
| 4 | Add a liveness figure to `StoreStats` | Breaks the record constructor |
| 5 | Add `module-info.java`, making the internal packages actually internal | Breaks anyone reaching into them |
| 6 | Revisit the freeze once MVCC and cursors exist | — |

Step 5 is the one that would make this review enforceable rather than advisory.

---

_Git HEAD revision: `c016a41` (plus the Phase 0 to 9 working tree)_
_Last updated: 2026-08-13_
