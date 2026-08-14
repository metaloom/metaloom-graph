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

| Method | Meaning |
|--------|---------|
| `store.begin()` | Snapshot isolation |
| `store.begin(IsolationLevel)` | `READ_COMMITTED`, `SNAPSHOT`, `SERIALIZABLE` |
| `store.readOnly()` | A snapshot with no write set; cheaper and cannot conflict |
| `tx.commit()` | Durable per `wal.sync.mode`; throws `TransactionConflictException` on conflict |
| `tx.rollback()` | Undo; `close()` without `commit()` rolls back |
| `store.inTransaction(Function<Transaction,T>)` | Auto-retry on conflict, configurable attempts |

A `Transaction` is `AutoCloseable` and **not** thread-safe. Concurrency comes from many
transactions, not from sharing one.

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

---

## 7. Maintenance and Introspection

```java
store.checkpoint();                       // force a fuzzy checkpoint
store.vacuum(Duration.ofSeconds(5));      // bounded incremental vacuum
store.verify(VerifyLevel.CHECKSUMS);      // scrub: CHECKSUMS or FULL
StoreStats s = store.stats();             // per-kind live/dead counts and bytes
StoreMetrics m = store.metrics();         // counters and gauges; subtract two snapshots to cost an interval
store.snapshot(Path.of("/backup/x"));     // reflink-based, O(1) where supported

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
- **`inTransaction` retries; `begin` does not.** A `TransactionConflictException` from a manual
  `begin` is the caller's to handle.
- **Read-only transactions still must be closed**, or vacuum stalls
  ([TRANSACTIONS.md §8](TRANSACTIONS.md#8-conventions-and-gotchas)).
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
- `ExampleTest`: the README example compiles and runs against a temp store — the documentation is
  executable, which is the only way it stays true.

---

## 12. Progress Assessment

The transactional API of sections 3 and 4 is the Phase 5 target. What exists today is the same
surface **without** transactions: `GraphStore` carries the element and traversal methods directly,
because a `Transaction` whose `commit()` guaranteed nothing would be a lie.

- [x] `GraphStore.open/close` and `GraphStoreOptions`
- [x] `createNode` / `nodeProperties` / `setNodeProperties` / `deleteNode`
- [x] `createEdge` / `createRelationship` / `deleteEdge` / `deleteRelationship`
- [x] `Properties` typed accessors, absent versus null
- [x] `neighbours(direction, types)` and breadth-first `traverse(maxDepth)`
- [x] Maintenance: `checkpoint`, `verify`, `stats`
- [x] Exception hierarchy per §8
- [ ] `Transaction` with begin/commit/rollback and `inTransaction` retry (Phase 5)
- [ ] `NodeView` / `RelationshipView` flyweights with epoch checks
- [ ] `NodeCursor` batch read with offset sorting (Phase 6)
- [ ] `EdgeCursor` / `TraversalCursor` streaming with prefetch (Phase 6)
- [ ] `materialise` for promoting a lightweight edge in place
- [x] `reclaim` (hole punching) and `compact` with `CompactionOptions` / `CompactionReport`
- [x] `metrics()` returning `StoreMetrics` / `IoStats`, and throttled `verify` via `VerifyOptions`
- [ ] `vacuum` (Phase 5), `snapshot` (Phase 6)
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

_Git HEAD revision: `c016a41`_
_Last updated: 2026-08-13 (synced with the Phase 0 to 3 implementation)_
