# Public Java API

The surface an embedding application sees. Internals are in [ARCHITECTURE.md](ARCHITECTURE.md).

The PoC's API is a good starting shape — `GraphStorage` with `create`, `readNode`,
`readRelationship`, `traverse` — but it has no transactions, returns fully materialised objects with
`Map<String,String>` properties, and offers no cursor or batch form. The target keeps the shape and
fixes those three things.

---

## 1. Design Principles

1. **Transactions are explicit and cheap.** Everything happens in one, including reads. An
   auto-commit convenience form exists but is sugar over the real thing.
2. **Nothing allocates per element on the read path unless asked.** The default read returns a flyweight
   view over a page slice; materialising a detached object is an explicit call.
3. **Ids are `long`.** `ElementId` is a record over a `long` and the API accepts both.
4. **Typed properties.** No `Map<String,String>`.
5. **Cursors, not collections.** `traverse` returning a `Set<Relationship>` (the PoC's signature)
   materialises an unbounded result; the target streams.
6. **Batch forms exist wherever they let the engine reorder I/O.**

---

## 2. Store Lifecycle

```java
GraphStoreOptions opts = GraphStoreOptions.builder()
    .pageSize(8192)
    .cacheSize(4L << 30)
    .ioEngine(IoEngineType.AUTO)
    .walSyncMode(WalSyncMode.FSYNC_ON_COMMIT)
    .build();

try (GraphStore store = GraphStore.open(Path.of("/data/mygraph"), opts)) {
    ...
}
```

`open` creates the store if the directory is empty, and validates the manifest, the superblocks and
the format version otherwise. `close` stops background workers, checkpoints, flushes and releases
the `LOCK` file — see [ARCHITECTURE.md §7](ARCHITECTURE.md#7-threading-and-lifecycle).

---

## 3. Transactions

```java
try (Transaction tx = store.begin()) {                 // SNAPSHOT by default
    long alice = tx.createNode("Person", props -> props
        .set("name", "Wes Anderson")
        .set("born", 1969)
        .set("active", true));

    long car = tx.createNode("Vehicle", props -> props.set("name", "VW Beetle"));

    tx.createEdge(alice, "OWNS", car);                 // lightweight, no relationship record

    long rel = tx.createRelationship(alice, "DROVE", car, props -> props
        .set("since", Instant.parse("2019-04-01T00:00:00Z")));

    tx.commit();
}
```

| Method | Meaning | Built |
|--------|---------|-------|
| `store.begin()` | A write transaction. One writer at a time; a second waits | **Yes** |
| `tx.commit()` | Durable per `wal.sync.mode` | **Yes** |
| `tx.rollback()` | `close()` without `commit()` rolls back | **Yes** |
| `store.inTransaction(work, attempts)` | Runs the work in a transaction, retrying what retrying can fix | **Yes** |
| `store.beginRead()` | A `SNAPSHOT` read view over many reads | **Yes** |
| `store.beginRead(IsolationLevel)` | `SNAPSHOT` or `READ_COMMITTED` | **Yes** |
| `store.snapshotStats()` | What open views cost right now | **Yes** |
| `store.begin(IsolationLevel)` | Per-transaction write isolation | **Yes** — `SNAPSHOT` or `SERIALIZABLE`; a writer at `READ_COMMITTED` is refused |
| `store.inTransaction(work, attempts, IsolationLevel)` | The same, with the retry seam | **Yes** |
| `store.inStatement(work)` | One statement, whose conflicts the engine absorbs | **Yes** — cannot end in a conflict: optimistic attempts, then one attempt with every other writer excluded. Refused inside an open transaction |
| `SERIALIZABLE` | Serializable write transactions | **Yes**, by validating what the transaction read at commit rather than by SSI; see TRANSACTIONS.md §6.4 |
| `TransactionConflictException` | The loser of a write-write or read-write conflict | **Yes** — a normal outcome; `inTransaction` retries it up to its attempt limit and then raises it, while auto-commit mutations and Cypher write queries go through `inStatement` and never raise it at all |

Both handles are `AutoCloseable` and **thread-confined**. A read view is published to the thread that
opened it and using it from another is refused rather than tolerated, because the reconstruction cache
underneath it is unsynchronised by design.

### 3.1 Read views

A view fixes what every read on the thread sees, so the ordinary API is used unchanged rather than
duplicated:

```java
try (ReadTransaction view = store.beginRead()) {
    List<Edge> edges = store.traverse(start, Direction.OUT, null, 6);
    // Writers commit freely underneath; this view does not move.
    for (Edge edge : edges) {
        Properties props = store.nodeProperties(edge.neighbourId());
    }
}
```

**Close it.** An open view holds back reclamation of everything committed since it began. Past
`retainedVersionBytes` the store abandons the oldest views and they raise `SnapshotTooOldException`,
which is a normal outcome to retry rather than a defect.

**A writer never reads through a view**, even on a thread that has one open: inside a write
transaction, reads see the writer's own uncommitted pages and otherwise the current state. That is
what stops a writer computing new state from stale input.

`reclaim()` and `compact()` are **refused while any view is open** — see §7.

---

## 4. Batch and Cursor APIs

Reads:

```java
NodeView n = tx.node(alice);                 // flyweight over the page; valid until tx ends
String name = n.getString("name");
int born    = n.getInt("born");
Optional<Instant> seen = n.getOptional("lastSeen", Instant.class);

Node detached = n.detach();                  // explicit materialisation, allocates
```

Batch read — the form that lets the engine sort by offset and coalesce pages
([SSD_IO.md §5](SSD_IO.md#5-prefetch-and-queue-depth)):

```java
try (NodeCursor c = tx.nodes(idArray)) {
    while (c.next()) {
        process(c.id(), c.label(), c.getString("name"));
    }
}
```

Traversal:

```java
try (EdgeCursor c = tx.neighbours(alice, Direction.OUT, Types.of("OWNS", "DROVE"))) {
    while (c.next()) {
        long neighbour = c.neighbourId();
        int  type      = c.typeId();
        if (c.isMaterialised()) {
            RelationshipView r = c.relationship();
            Instant since = r.get("since", Instant.class);
        }
    }
}
```

Breadth-first expansion with a depth bound, replacing the PoC's `traverse(uuid, maxDepth)`:

```java
try (TraversalCursor c = tx.traverse(alice)
        .direction(Direction.OUT)
        .types("OWNS")
        .maxDepth(3)
        .uniqueness(Uniqueness.GLOBAL_NODE)
        .build()) {
    while (c.next()) { ... }
}
```

Cursors are `AutoCloseable`, single-threaded, and prefetch ahead. They never materialise the full
result set — the PoC's `Set<Relationship>` return type cannot express a traversal that does not fit
in memory.

**One of these exists today, in a smaller shape.** `GraphStore.neighbourCursor(nodeId, direction,
types)` returns a `NeighbourCursor` with `next()` / `edge()` / `close()`, which streams the block
chain — one block decoded at a time, so stopping early costs the head block and nothing else
([RELATIONSHIP_STORAGE.md §5.4](RELATIONSHIP_STORAGE.md)). Three differences from the sketch above
are real rather than cosmetic: it yields an `Edge` record instead of a flyweight over the page, so
one small object per row survives; it does not prefetch, which needs the io_uring engine; and it is
on the store rather than a transaction, because `GraphStore`'s mutations join whatever transaction
the calling thread holds. What it does have is the lifetime the sketch describes — it holds a read
view until closed, which is the part that makes it different from a list.

---

## 5. Edges vs Relationships

The distinction is user-visible because it is the main space lever
([RELATIONSHIP_STORAGE.md §4](RELATIONSHIP_STORAGE.md#4-lightweight-edges)):

| Call | Creates | Has an id | Can carry properties |
|------|---------|-----------|----------------------|
| `tx.createEdge(from, type, to)` | Two adjacency entries only | No stable external id | No |
| `tx.createRelationship(from, type, to, props)` | Adjacency entries plus a 64-byte `REL` record | Yes | Yes |
| `tx.materialise(from, type, to)` | Promotes an existing lightweight edge | Yes, from this point on | Yes |

Setting a property on a lightweight edge materialises it automatically; the API returns the new id.
Applications that never need edge properties should use `createEdge` and save 64 bytes plus a
property block per edge.

---

## 6. Property Access

```java
props.set("name", "Wes Anderson");      // SHORT_STRING
props.set("born", 1969);                // INT, 2 bytes
props.set("score", 0.93f);              // FLOAT
props.set("active", true);              // BOOL, 0 bytes of payload
props.set("created", Instant.now());    // TIMESTAMP
props.set("tags", List.of("a","b"));    // LIST
props.set("embedding", float[] vec);    // VECTOR_F32, stored as a blob
props.setNull("middleName");            // explicit null, distinct from absent
props.remove("obsolete");
```

Reading distinguishes absent from null:

```java
n.has("middleName");            // true
n.isNull("middleName");         // true
n.getOptional("middleName", String.class);   // Optional.empty()
n.has("nothingHere");           // false
```

Type mismatch throws `PropertyTypeException` naming the key, the stored type and the requested type.
There is no implicit string coercion — that is what the PoC's `Map<String,String>` forced and it is
the source of silent data loss.

### 6.1 Finding nodes by something other than an id

```java
List<Long> people = store.nodesByLabel("Person");             // label index
List<Long> found  = store.nodesByProperty("email", "a@b.c");  // property index, then verification
store.createUniqueConstraint("email");                        // refused if the data already breaks it

store.createIndex("Person", "active");                        // declared, backfilled, and used by Cypher
List<Long> active = store.nodesByLabelAndProperty("Person", "active", true);
store.createUniqueConstraint("Person", "email");              // no two :Person, rather than no two nodes
store.dropIndex("Person", "active");                          // and its entries, in the same transaction

List<Neighbour> near = store.nearestByScan("Person", "embedding", query, 10, VectorMetric.COSINE);

store.createVectorIndex(VectorIndexDefinition.of("Person", "embedding", 768, VectorMetric.COSINE));
List<Neighbour> fast = store.nearest("Person", "embedding", query, 10, null);
List<Neighbour> filtered = store.nearest("Person", "embedding", query, 10, id -> isActive(id));
```

`createIndex(label, key)` is the one index that has to be **asked for**: the label and property
indexes maintain themselves for everything, and a composite one is declared because there are
`labels × keys` possible pairs ([INDEX.md §3.3](INDEX.md)). It scans and backfills before it returns,
so the index is complete the moment it exists. `nodesByLabelAndProperty` answers the same question
with or without a declaration — only the number of candidates read differs — so adding one changes a
query's cost and never its result.

`createUniqueConstraint(label, key)` is that declaration plus a flag, and it is the only way to say
"no two `:Person` share an email"; the one-argument form is store-wide over every node. It is
enforced on `addNodeLabel` as well as on a property write, because adding the label is the other way
to break it.

`createVectorIndex` needs a store created with `vectorDimensions(768)`: the vector and its links live
in one fixed-size record, and that size is fixed at creation like the page size. It walks the label's
nodes, refuses if any holds a vector of another length, and builds in batches — resumably, so calling
it again after an interruption continues rather than restarting.

`nearest` is approximate; `nearestByScan` is exact and is what the approximate one is measured against
([VECTOR.md §8](VECTOR.md)). Querying an index that is absent or still building **fails by name**
rather than returning an empty list, because "no index" and "no matches" are opposite answers.

The `filter` is applied when admitting a result, never to steer the search — steering it is the
standard way to collapse an approximate search's recall.

`nearestByScan` is **exact and linear**: it reads every candidate's vector and compares it, with no
index and no approximation. That is deliberate — it is the answer an approximate index gets measured
against ([VECTOR.md §8](VECTOR.md)), so it must be computable whether or not one exists and must not
share code with one. `label` may be null to scan every live node instead of probing the label index.

Results come back **nearest first under the metric**, which is the part to rely on. `Neighbour.score`
is the metric's own value — a similarity for `COSINE` and `DOT`, a distance for `EUCLIDEAN` — so
sorting a result list on it without consulting `VectorMetric.higherIsNearer()` reverses two of the
three. A node whose value is not a vector, or is a vector of a different length, is skipped rather
than refused: a property key is free-form, and the refusal belongs on the write path of a declared
index.

---

## 7. Maintenance and Introspection

```java
store.checkpoint();                       // force a fuzzy checkpoint
store.vacuum(Duration.ofSeconds(5));      // bounded incremental vacuum
store.verify(VerifyLevel.CHECKSUMS);      // scrub: CHECKSUMS or FULL
StoreStats s = store.stats();             // per-kind live/dead counts and bytes
StoreMetrics m = store.metrics();         // counters and gauges; subtract two snapshots to cost an interval
// A whole-store reflink clone is still designed and unbuilt. What exists instead is a *logical* backup, outside
// the engine and against this API: see spec/BACKUP.md and metaloom-graph-cli.

int pages = store.reclaim();              // punch all-free pages; breaks no ids
CompactionReport r = store.compact();     // relocate records; BREAKS IDS, see FILE_FORMAT.md §9
```

`reclaim` and `compact` are the two halves of space reclamation and they are not
interchangeable. `reclaim` returns the blocks of pages that are already empty and moves nothing, so
every id in the store and every id the caller is holding stays valid. `compact` moves records to
close holes *within* pages, and is the only operation in the engine that invalidates an id.

Ids the caller holds across a `compact` may afterwards name a different record or nothing at all.
A stale one is rejected rather than misresolved — freeing a slot advances its generation — but code
that caches ids has to re-find them. Nothing stored *inside* the engine is affected: every internal
reference is rewritten in the same transaction as the move.

```java
CompactionReport report = store.compact(CompactionOptions.builder()
    .livenessThreshold(0.5)               // only kinds emptier than this
    .maxRelocationsPerTransaction(4096)   // one commit per batch; bounds memory and writer latency
    .pauseBetweenBatchesMillis(5)         // leave room for foreground writers
    .build());

report.totalRelocated();                  // records moved
report.slotsReclaimed();                  // slots the high water marks came down by
report.livenessBefore();                  // and after
```

`verify(FULL)` runs the invariant checkers from
[TESTING.md §5](TESTING.md#5-invariant-checkers) against a live store. Throttling is explicit:

```java
store.verify(VerifyLevel.FULL, VerifyOptions.throttled());          // yields every 4096 elements
store.verify(VerifyLevel.FULL, VerifyOptions.consistentSnapshot()); // one pass, one instant
```

A throttled scan drops the read latch between batches, so a writer waits for a batch rather than for
the whole store — at the cost of no longer seeing a single instant. A scan cut short by its violation
limit or time budget says so in its output rather than reporting a clean bill of health.

`metrics()` is what an operator watches; [OPERATIONS.md §4](OPERATIONS.md#4-monitoring) lists which
signals mean what.

---

## 8. Exceptions

See [ARCHITECTURE.md §9](ARCHITECTURE.md#9-error-model). All extend `GraphStoreException`, which
extends `RuntimeException` — checked `IOException` on every accessor (as the PoC has) makes the API
unusable inside lambdas and streams, and there is nothing an application can meaningfully do with an
`IOException` from a page read that it cannot do with an unchecked one.

`GraphStore.open` and `close` are the exceptions and do throw `IOException`, because failure there is
an ordinary, actionable condition.

---

## 9. Migration From the PoC API

| PoC | Target |
|-----|--------|
| `new GraphStorageImpl(basePath)` | `GraphStore.open(path, opts)` |
| `st.create(node)` | `tx.createNode(label, props -> ...)` |
| `st.create(rel)` | `tx.createEdge(...)` or `tx.createRelationship(...)` |
| `st.readNode(uuid)` | `tx.node(id)` |
| `st.readRelationship(uuid)` | `tx.relationship(id)` |
| `st.traverse(uuid, depth)` returning `Set<Relationship>` | `tx.traverse(id)...build()` returning a cursor |
| `GraphUUID` | `ElementId` / `long` |
| `Map<String,String> props()` | Typed `PropertyView` / `PropertyWriter` |
| implicit auto-commit, no durability | explicit `Transaction` |

The PoC's on-disk format is **not** readable by the target engine and no converter is planned; the
PoC contains no data worth migrating. Say so in the release notes rather than quietly changing the
format.

---

## 10. Conventions and Gotchas

- **A `NodeView` is only valid inside its transaction.** It is a flyweight over a page. Escaping one
  is the most likely misuse of this API, and views therefore carry a transaction epoch and throw on
  use after close.
- **Cursors must be closed.** They hold prefetch buffers and, for `GLOBAL_NODE` uniqueness, a
  visited set.
- **`createEdge` gives no id.** If you need to refer to an edge later, use `createRelationship`.
- **`inTransaction` retries; `begin` does not; `inStatement` cannot fail.** The three are a ladder,
  and which one you want is decided by whether you have anything to reconsider. `begin` hands you the
  conflict. `inTransaction` retries a bounded number of times and then hands you the conflict, and
  refuses to retry a `ConstraintViolationException` or a `SnapshotTooOldException` into the same
  answer. `inStatement` is for work that is one statement: it retries, and when that runs out it
  excludes every other writer and finishes, so there is no conflict left to hand back. **The default
  three attempts on `inTransaction` is low** under real contention — six writers on one index leaf
  need two hundred — so pass an attempt count if writers share an index.
- **Read views must be closed**, or reclamation stalls and the store eventually abandons them
  ([TRANSACTIONS.md §8](TRANSACTIONS.md#8-conventions-and-gotchas)).
- **A read view is thread-confined**, and using one from another thread is refused rather than
  tolerated. Open one per thread.
- **A writer never reads through a view**, even one open on its own thread.
- **Batch reads reorder.** `tx.nodes(ids)` does not guarantee cursor order matches the input array;
  use `c.id()`. An ordered variant costs a sort of the results.

---

## 11. Test Setup

- `ApiContractTest`: every method's happy path plus its documented failure mode.
- `ViewLifetimeTest`: assert a `NodeView` used after `tx.close()` throws, does not read freed memory,
  and reports the transaction it belonged to.
- `TypedPropertyTest`: round trip every supported type through the API; assert `PropertyTypeException`
  on mismatch and that absent and null are distinguishable.
- `CursorTest`: assert cursors stream (bounded memory over a 10^7-edge traversal), close cleanly,
  and are safe to abandon mid-iteration.
- `RetryTest`: `inTransaction` under contention; assert it converges and respects the attempt limit.
- `UsageExampleTest` (**built**, `src/test/java/io/metaloom/graph/core/example/`): the README example
  compiles and runs against a temp store, with an assertion behind every claim — the documentation is
  executable, which is the only way it stays true. It found one defect on its first run: an index
  lookup after a rolled-back first transaction threw `StaleIdException` on a healthy store, because
  the catalog page id was cached in memory while the allocation that produced it was discarded.

---

## 12. Progress Assessment

`GraphStore` carries the element and traversal methods directly, and a transaction — read or write —
scopes them for the calling thread rather than duplicating them.

- [x] `GraphStore.open/close` and `GraphStoreOptions`
- [x] `createNode` / `nodeProperties` / `setNodeProperties` / `deleteNode`
- [x] `nodeLabels` / `nodeHasLabel` / `addNodeLabel` / `removeNodeLabel` — the last two added for
      Cypher's `SET n:Label` and `REMOVE n:Label`. They maintain the label index themselves, since
      `IndexCatalog` had no hook for a changed label set, and they move the set in and out of the
      record's three inline slots and its overflow block
- [x] `hasReadTransaction` / `hasWriteTransaction` — the second because `begin()` does **not** refuse
      to nest and a nested transaction stops being rollbackable without saying so
- [x] `createEdge` / `createRelationship` / `deleteEdge` / `deleteRelationship`
- [x] `Properties` typed accessors, absent versus null
- [x] `neighbours(direction, types)` and breadth-first `traverse(maxDepth)`
- [x] `neighbourCursor(direction, types)` — the streaming form of the same walk, which `neighbours`
      is now a drain of. It holds a read view until closed, so it is the one read on the API that a
      caller can leak; §4 says what that costs and why the list form does not pay it
- [x] `hasEdge(nodeId, neighbourId)` — "are these two connected", answered without materialising the
      node's neighbours. Direction- and type-agnostic, stops at the first match, and skips the blocks
      whose location range cannot hold the target
      ([RELATIONSHIP_STORAGE.md §6.4](RELATIONSHIP_STORAGE.md))
- [x] Maintenance: `checkpoint`, `verify`, `stats`
- [x] Exception hierarchy per §8, plus `ConstraintViolationException`
- [x] `createUniqueConstraint` / `uniqueConstraints`, durable across a reopen
- [x] `createIndex` / `dropIndex` / `hasIndex` / `compositeIndexes` / `nodesByLabelAndProperty` — the
      declared `(label, key)` index, backfilled at declaration and swept at drop, and
      `createUniqueConstraint(label, key)` for the label-scoped constraint it is what makes
      expressible. Reachable over HTTP and from the browser UI, which the query language is not:
      there is no `CREATE INDEX` in the Cypher subset ([CYPHER.md §8](CYPHER.md))
- [x] `Transaction` with begin/commit/rollback, plus auto-commit for a mutation made outside one
- [x] `UsageExampleTest`: the documented example, executable and asserting
- [x] `beginRead` / `ReadTransaction` / `IsolationLevel` / `SnapshotStats` / `SnapshotTooOldException`
- [x] `inTransaction(work, attempts)` with retry, refusing to retry what retrying cannot fix
- [ ] `NodeView` / `RelationshipView` flyweights with epoch checks
- [ ] `NodeCursor` batch read with offset sorting (Phase 6)
- [ ] `EdgeCursor` / `TraversalCursor` streaming with prefetch (Phase 6)
- [ ] `materialise` for promoting a lightweight edge in place
- [x] `reclaim` (hole punching) and `compact` with `CompactionOptions` / `CompactionReport`
- [x] `metrics()` returning `StoreMetrics` / `IoStats`, and throttled `verify` via `VerifyOptions`
- [x] Vacuum — reclamation at the end of every commit rather than a separate call or a worker thread
- [ ] `snapshot` (Phase 6): a whole-store reflink clone. `FICLONE` is bound and called by nothing
- [x] `options()` — the geometry a store was created with, which a restore has to be able to ask for
- [x] `forEachNodeWhile(consumer, ScanOptions)` — a paced scan, so a whole-store walk does not block every writer.
      The second gap a real consumer found in this API, after `forEachNode` itself ([BACKUP.md §2.1](BACKUP.md))
- [x] Logical backup and restore, built on those two: [BACKUP.md](BACKUP.md)
- [x] README rewritten against the new API

---

## 13. Key Classes Reference (target)

| Class | Package | Purpose |
|-------|---------|---------|
| `GraphStore` | `io.metaloom.graph.core.api` | Store lifecycle, transaction factory, maintenance |
| `GraphStoreOptions` | `io.metaloom.graph.core.api` | Builder for all configuration |
| `CompactionOptions` | `io.metaloom.graph.core.api` | What a compaction run is allowed to do |
| `CompactionReport` | `io.metaloom.graph.core.api` | What it actually did, including how many ids moved |
| `StoreMetrics` / `IoStats` | `io.metaloom.graph.core.api` | Counters and gauges for monitoring |
| `VerifyOptions` | `io.metaloom.graph.core.api` | Throttling, violation limit and time budget for an online scan |
| `Transaction` | `io.metaloom.graph.core.api` | Unit of work and the mutation API |
| `NodeView` / `RelationshipView` | `io.metaloom.graph.core.api` | Zero-copy flyweights over a page slice |
| `PropertyView` / `PropertyWriter` | `io.metaloom.graph.core.api` | Typed property access |
| `EdgeCursor` / `NodeCursor` / `TraversalCursor` | `io.metaloom.graph.core.api` | Streaming results with prefetch |
| `StoreStats` | `io.metaloom.graph.core.api` | Introspection |

---

## Identity

`AuthStore` is part of graph-core's public API, in `io.metaloom.graph.core.api`, alongside `Privilege`,
`Grant`, `Principal`, `User` and `Role`. It is there rather than in a module of its own because
`GraphDatabases` already owns the root and `<root>/.auth/` is a directory in it — see
[AUTH.md §1](AUTH.md) for why that beat a new leaf module.

**It changes nothing about the engine's own guarantees.** `GraphStore` has no notion of a principal, no
method takes one, and nothing in the storage layer consults one. Authorization is enforced by the
servers, on the ports, which is the same line `--allow-write` has always drawn — and
[OPERATIONS.md §9](OPERATIONS.md)'s statement that anything able to open the directory has full access
is unchanged.

The one public type that is deliberately incomplete is `User`: it carries no password hash, because the
hash lives in the unexported `io.metaloom.graph.core.auth` and a field that is not on the object cannot
be serialised into a response or printed by a generated `toString()`.

New exceptions, both under `GraphStoreException`: `AuthException` for a refused edit, and
`AuthFormatException` for an identity file that cannot be read. A wrong password is neither — 
`authenticate` returns an empty `Optional`, and returns the same one for every reason.

---

_Git HEAD revision: `da8c93e`_
_Last updated: 2026-08-17 (authentication and RBAC authorization)_
