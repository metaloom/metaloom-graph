# INDEX_TASKS.md — the work items behind INDEX.md §11

[INDEX.md](INDEX.md) ends with a list of things that are **not built**. This file is the other half of
that list: for each gap, whether it is worth building, what it would actually cost, and what "done"
means. It is a task list, not a design document — the design lives in the spec of the layer it
touches, and a task that grows past a paragraph of design gets its own file
([VECTOR.md](VECTOR.md) is the first one that did).

> **Do not put here**: how the trees work ([INDEX.md](INDEX.md)), how adjacency is laid out
> ([RELATIONSHIP_STORAGE.md](RELATIONSHIP_STORAGE.md)), the cross-project backlog ordering
> ([PROGRESS.md §5](PROGRESS.md)). A task that is picked up moves its checkbox in the spec of its
> layer, not here.
>
> **A finished task leaves this file.** Its reasoning moves to the layer it changed, because that is
> where the next reader of that code will look; a backlog entry marked "done" is a second copy that
> goes stale. §3 is the ledger of what left and where it went. **Ids are never reused** — other specs
> link to them, and IDX-3 stays IDX-3 after IDX-2 is gone.

---

## 0. The task list

| # | Task | Priority | Effort | Verdict |
|---|---|---|---|---|
| [IDX-4](#idx-4--a-streaming-neighbour-cursor) | Streaming neighbour cursor | **P1** | M | Build. A `LIMIT 1` over a supernode currently materialises every edge |
| [IDX-5](#idx-5--a-relationship-scan-that-does-not-go-through-the-nodes) | `forEachRelationship` over the `REL` segment | P2 | S | Build. ~20 lines, and it removes most of the reason to want IDX-6 |
| [IDX-6](#idx-6--a-relationship-type-index) | Relationship type index | P3 | L | **Do not build yet.** See §1 |
| [IDX-7](#idx-7--composite-label-property-indexes) | Composite `(label, property)` indexes | P2 | M | Build when a workload has a label plus a low-selectivity property |
| [IDX-8](#idx-8--statistics-enough-to-choose-a-label) | Per-label / per-key cardinality | P2 | M | Build with IDX-7 — it is what makes a choice between two indexes possible |
| [IDX-9](#idx-9--vector-data) | Vector properties and ANN search | P1 | L | **In progress** — HNSW, six stages, planned and tracked in [VECTOR.md §7](VECTOR.md) |

---

## 1. First: are unindexed relationships a problem for high-degree nodes?

**No — and conflating the two hides the actual cost.** [INDEX.md §8](INDEX.md) says relationships are
not indexed. That gap has exactly one victim: the query that starts from *no node at all*,
`MATCH ()-[r:KNOWS]->()`, which becomes a scan of every node plus a decode of every node's adjacency.
A supernode query is a different question, and it does not start from nothing — it starts from a node
id, which is the case this engine is fastest at.

What a supernode actually costs today, for `MATCH (n:Person {id: 42})-[:RARE]->(m)` on a node of
degree 10^6:

| Step | Cost | Where |
|---|---|---|
| Find `n` | one property probe, one verify | fine |
| Read the block chain | ~124 blocks of 8 KiB, ~1 MiB, sequential-ish | fine — this is what the format is for |
| Skip blocks that cannot hold `:RARE` | **fixed** — a block whose type bitmap admits no filtered type is walked past undecoded ([RELATIONSHIP_STORAGE.md §5.3](RELATIONSHIP_STORAGE.md)). It saves the decode, not the read: the chain pointer is inside each block | closed, §3 |
| Materialise every entry into a `long[]`, then into `List<Edge>` | **10^6 longs and 10^6 `Edge` objects, before the caller sees the first row** | [IDX-4](#idx-4--a-streaming-neighbour-cursor) |
| For each *materialised* entry, read the 64 byte `REL` record **and then** test the type | **fixed** — the entry caches its type ([RELATIONSHIP_STORAGE.md §3.1](RELATIONSHIP_STORAGE.md)), so the record is read once per surviving candidate | closed, §3 |

Two of the three are now fixed, and the one that remains is the materialisation of every entry before
the caller sees a row. None of the three is an index. A relationship type index answers "which
relationships have type T" globally, while the supernode query already knows which node it is on —
its problem is that the adjacency it holds is enumerated in full and unfiltered. **So the supernode
work was IDX-3 and IDX-4, both inside the adjacency layer**, and only IDX-4 is left.

The relationship index is worth building only when a workload really does ask the global question, and
[IDX-5](#idx-5--a-relationship-scan-that-does-not-go-through-the-nodes) answers a large part of that
question for a fraction of the cost. Its own entry says why it stays at P3.

---

## IDX-4 — a streaming neighbour cursor

**Priority P1.** `AdjacencyStore.edges` returns `long[]` and `GraphStoreImpl.neighbours` returns
`List<Edge>`. Both are fully materialised before the caller sees anything. At degree 10^6 that is an
8 MB `long[]` plus a million `Edge` records, for a query that may want one row.

`NeighbourCursor` is listed in [RELATIONSHIP_STORAGE.md §11](RELATIONSHIP_STORAGE.md) as a target
class and does not exist. The Cypher layer already has the right shape to consume one: `RowConsumer`
and `forEachNodeWhile` exist precisely because "a scan of ten million nodes must not become a list of
ten million rows", and the same argument applies one level down.

**Build it on the filtered path.** IDX-3 is done and reduced how many blocks are decoded; IDX-4
reduces how many entries are touched at all when the query stops early. The cursor should walk the
chain the way `AdjacencyStore.edges(nodeId, typeIds)` does — consulting `admitsAnyType` per block —
rather than duplicating that walk, and it must apply the entry-level type filter through
`PackedEdge.excludedByType` and not re-derive the rule: a second implementation is a second place to
get "type 0 means not cached" wrong, and that mistake silently drops edges
([RELATIONSHIP_STORAGE.md §3.1](RELATIONSHIP_STORAGE.md)).

**Watch the read view.** `neighbours` runs inside `read(...)`, which may take the optimistic path and
re-run the body. A cursor that outlives the call cannot do that — it needs the latch held outright,
the way `forEachNodeWhile` uses `runRead` and documents why ("the consumer has side effects").

**Done when**: the read-counting harness shows a `LIMIT 1` over a degree-10^6 node reading one block;
`neighbours(...)` is expressed in terms of the cursor rather than duplicating it.

---

## IDX-5 — a relationship scan that does not go through the nodes

**Priority P2, effort: small.** `MATCH ()-[r:KNOWS]->()` currently seeds its first node pattern with
`forEachNode` and expands every node's adjacency — every node record, every adjacency block, every
relationship record, to find one type. `SchemaScanner` does the same thing to count relationship types
for the browser sidebar.

Relationships are fixed-stride 64 byte records in their own segments, and `SegmentSet` already has
`forEachLiveIdWhile` — the exact primitive `forEachNode` is built on. A `forEachRelationship` is a
handful of lines and turns the global question into a sequential scan of the `REL` segments, which is
the cheapest scan the engine can perform.

**The catch, stated up front**: this only sees **materialised** relationships. A lightweight edge has
no `REL` record and exists only as two adjacency entries
([RELATIONSHIP_STORAGE.md §4](RELATIONSHIP_STORAGE.md)), so a complete answer still needs the
adjacency walk. That makes `forEachRelationship` a correct fast path for property-carrying
relationships and a wrong one for everything else — it must be named and documented so that no caller
can mistake it for "every edge". `SchemaScanner` in particular must not adopt it naively; its counts
would silently drop every lightweight edge.

**Done when**: `forEachRelationship` exists with the lightweight caveat in its javadoc; a test asserts
that it sees materialised relationships and does not see lightweight edges.

---

## IDX-6 — a relationship type index

**Priority P3. Do not build this yet**, and the reasons are worth writing down so the decision is not
re-litigated from scratch.

1. **It has one beneficiary.** Per §1, the supernode case is not helped by it at all. What it buys is
   `MATCH ()-[:T]->()` and `MATCH ()-[r]->() WHERE type(r) = 'T'` — global questions that the engine
   is explicitly not built for.
2. **Half of it cannot be built the obvious way.** A B+tree entry is `(k1, k2, value)` where the value
   is an element id. A lightweight edge *has no id* — that is the entire point of the tier, and the
   API acknowledges it ([API.md §5](API.md), `createEdge` vs `createRelationship`). Indexing
   lightweight edges means inventing a synthetic key, `(fromLocation, type, toLocation)` packed across
   `k1`/`k2`, and accepting that the index cannot hand back anything a caller can dereference. That is
   a different structure from the one that indexes materialised relationships by id, so "a
   relationship index" is really two indexes.
3. **It taxes the hot path.** Every edge insert would become an index insert. Loading 10M nodes and
   20M relationships takes 11 minutes today ([reports/SCALE.md](reports/SCALE.md)); the adjacency
   entry already records the type, so this pays a B+tree write on every edge to answer a question the
   engine's shape says will rarely be asked.
4. **The cheap 80 percent is IDX-5.** A sequential `REL` scan answers the global question for
   property-carrying relationships without touching the write path at all.

**What would change the verdict**: a measured workload that runs global type queries, or a
`type(r) = 'T'` predicate appearing in the Cypher layer's query log often enough to matter. Build
IDX-8 first — per-type counts are what would let anyone argue this from numbers rather than from
intuition.

**If it is ever built**, the design is settled by the existing machinery and does not need inventing:
a third tree rooted in the catalog page (§4 of INDEX.md leaves offsets 32..63 free, past the offset-30
generation trap), `k1 = typeId`, `k2 = relId`, maintained by two new hooks alongside the four that
exist. Relationship *property* indexing is the same tree with `k1 = (keyId << 32) | valueHash` and the
same candidate verification — and it is strictly for materialised relationships, which is why
[INDEX.md §6](INDEX.md) says there is no unique constraint on a relationship property.

---

## IDX-7 — composite (label, property) indexes

**Priority P2.** [INDEX.md §11](INDEX.md) states the case: `MATCH (n:Person {email: …})` is a property
probe plus a label verification, where one probe would do. Two things make it worth more than that
sentence suggests:

- **A label-scoped unique constraint becomes possible.** Today `createUniqueConstraint(key)` is global
  over every node in the store — "no two nodes share an email", not "no two `:Person` share an email".
  The second is what applications actually mean, and it cannot be expressed at all right now.
- **It is the honest fix for a low-selectivity property.** [INDEX.md §7](INDEX.md) records that a
  property probe wins over a label scan unconditionally, "which is right almost always and wrong for a
  property that is nearly constant". `{active: true}` on a store of ten million nodes returns five
  million candidates, each of which is then read and verified. A composite key cuts that to the
  labelled subset in one probe.

**Key design.** Do not pack the fields into fixed sub-widths — label ids and key ids are `int`s from
the dictionary, and a width choice made now becomes a format constraint forever. Hash the triple
instead, which is what the property tree already does with its value:

```
k1 = mix64(labelId, keyId, valueHash(value))
k2 = nodeId
value = nodeId
```

Collisions are already survivable: `nodesByProperty` verifies every candidate against the stored
property, and a composite lookup verifies the label as well. The verification is what makes hashing
safe, and it is the same argument [INDEX.md §3.2](INDEX.md) makes for the property tree — including
the part that bites: the verification has to use `IndexCatalog.valueEquals`, the partner of
`valueHash`, and not `Objects.deepEquals`.

**Declaration, not implicit maintenance.** The two existing trees index everything automatically. A
composite index must be *declared* — there are `labels × keys` possible pairs and indexing them all
would multiply the write cost of every node by the size of its label set. So:

- a catalog list of `(labelId, keyId)` pairs, alongside the unique-constraint list at offset 64 —
  note that list can already reach offset 1092 with 256 entries, so pick the new offsets after it, not
  in the 32..63 gap if IDX-6 has claimed that;
- `createIndex(label, key)` scans every live node and backfills, the way `addUniqueConstraint` scans
  before declaring;
- the four maintenance hooks consult the declared list — and `onLabelsChanged` becomes interesting for
  the first time, because adding a label to a node now has to insert composite entries for every
  declared key that node already carries;
- `PatternMatcher.candidates` prefers a composite probe when the pattern carries both a label and a
  literal-valued property, before its current property-then-label order.

**The rollback trap.** `ensureInitialised` already documents that the catalog page can be allocated by
a transaction that rolls back, which is why `catalogPageId` is a hint re-derived on every use. A
declared-index list adds a second thing on that page whose absence must mean "not declared" rather
than "corrupt". Reuse the same re-derivation rather than adding a parallel mechanism.

**Done when**: `createIndex(label, key)` exists and backfills; the planner uses it and a test asserts
the *choice* (not just the answer) — the existing tests would pass with the index ignored entirely;
`ReferenceModelTest` covers a store with a composite index declared mid-workload; a label-scoped
unique constraint either works or is explicitly out of scope in `INDEX.md §6`.

---

## IDX-8 — statistics, enough to choose a label

**Priority P2, and IDX-7 is much less useful without it.** [INDEX.md §7](INDEX.md) is blunt about
this: "the store does not expose any statistics at all — there is no cardinality estimate anywhere in
this engine, and the planner is a fixed order rather than a cost model." Two consequences are already
documented as known costs: only the first label of a pattern drives the scan, and a property probe
always beats a label scan.

The minimum useful thing is **one count per label and one per (key) prefix**, which is enough to (a)
start `MATCH (n:Person:Admin)` from the rarer label, and (b) decline a property probe when the
property is nearly constant.

Two ways to get it, and the choice is a real trade:

| | Exact, computed | Maintained counters |
|---|---|---|
| How | `BTree.countPrefix(root, k1)` walks the matching leaves | a counter per label id in the catalog page, updated by the four hooks |
| Cost to read | O(entries under the prefix) — 3,000 leaf reads for a million-entry label | O(1) |
| Cost to write | none | one page write per hook call, on the hot load path |
| Correctness | cannot drift | drifts if any hook is missed, and a drifted estimate silently changes plans |

**Recommendation**: `countPrefix` first, because it cannot be wrong and needs no format change, cached
in memory per label with the cache dropped at commit. Maintained counters only if the cache proves
insufficient — and if they are built, the invariant checker must recount them, exactly as it recounts
`degree_out` ([RELATIONSHIP_STORAGE.md §8](RELATIONSHIP_STORAGE.md), where a drifted degree counter is
called out as silently changing traversal plans).

**Done when**: `MATCH (n:Common:Rare)` starts from `Rare`, with a test that asserts the access path
rather than the result; the numbers are exposed on `StoreStats` so an operator can see them.

---

## IDX-9 — vector data

**Priority P1, in progress. The design and the stage list live in [VECTOR.md](VECTOR.md); this entry
is a pointer, not a second copy.**

Vectors were already storable — `ValueCodec.T_VECTOR_F32`, `Properties.getVector` — and an
*exact-equality* lookup on one works, because the property tree hashes it by content
([INDEX.md §3.2](INDEX.md)). What nothing did is nearest neighbour, which
[INDEX.md §11](INDEX.md) lists as "Full text, spatial, vector".

The decision is **HNSW**, in six stages ([VECTOR.md §7](VECTOR.md)). Two things about it belong here
rather than only there, because they land on this file's subject matter:

- **It is a declared index**, which makes it the same declaration machinery as
  [IDX-7](#idx-7--composite-label-property-indexes) — a list on the catalog page past the unique-key
  list, a backfill scan, and the `initialised()` re-derivation so that absence reads as "not declared"
  rather than "corrupt". IDX-7 is no longer a prerequisite for it, but the two should share that
  machinery rather than grow two of it.
- **It needs both hooks to carry both halves of a node.** `onLabelsChanged` gets no properties and
  `onPropertiesChanged` gets no labels, so a `(label, key)` index cannot maintain itself from either
  one alone. The fix planned is a property loader and a label loader on `IndexCatalog`, wired in the
  `GraphStoreImpl` constructor and never called when nothing is declared — which is worth knowing
  before anybody widens those signatures for another reason.

**Stage 1 is built**: the three `vector.*` Cypher functions, the parser fold that lets a namespaced
name exist at all, `GraphStore.nearestByScan` — the exact answer an approximate index gets measured
against — and a bound on the vector dimension in `ValueCodec`, which was the last unchecked length
field in the property codec.

---

## 2. What this list deliberately leaves out

- **Range queries** ([INDEX.md §11](INDEX.md)). A real gap with a settled design — an
  order-preserving key instead of a hash — and no task here because nothing in the Cypher layer has
  asked for `n.age > 30` to be fast. It would be a third tree with the same maintenance hooks.
- **Index rebalancing on delete.** Deferred to compaction by design ([INDEX.md §2](INDEX.md)), which
  rebuilds both trees via `resetTrees`. Worth revisiting only if a delete-heavy workload appears that
  cannot compact.
- **Full text and spatial.** No use case in this repository or in the MetaLoom integration.

---

## 3. Closed

The reasoning moved with the work; this is only the ledger, so that a spec still linking to one of
these ids leads somewhere.

| # | Task | Where it lives now |
|---|---|---|
| IDX-1 | A `float[]` property was indexed under its identity hash, so an equality lookup missed, a delete left the entry dangling and a unique constraint never fired. Fixed by hashing arrays and lists by content, with `IndexCatalog.valueEquals` as the matching verification | [INDEX.md §3.2](INDEX.md); tests in [TESTING.md](TESTING.md); the vectors-stay-in-the-property-tree decision it forced in [VECTOR.md §1.3](VECTOR.md) |
| IDX-2 | A type-filtered supernode traversal read one 64 byte relationship record per *edge* to learn the type it was filtering on. Fixed by caching the type in the materialised adjacency entry, with `0` reserved for "not cached" so no format version bump was needed | [RELATIONSHIP_STORAGE.md §3.1](RELATIONSHIP_STORAGE.md) (and §6.2 for the tombstone argument, §8 for the three rules); budget in [TESTING.md §7](TESTING.md) |
| IDX-3 | The `type_bitmap` and `min/max_location` a block maintains on every append were read only by the delete path, so a type-filtered scan of a supernode decoded every block of the chain and `hasEdge(a, b)` did not exist at all. Fixed by `edges(nodeId, typeIds)` and `hasEdge`, with two header flags saying when either summary may be trusted to rule something out — the bitmap under-describes a block holding an entry that caches no type, and the range mixes two address spaces in a block holding a materialised one. The saving is in blocks *decoded*; §5.3 says why it cannot be in blocks read, and corrects the O(log blocks) claim the section used to make | [RELATIONSHIP_STORAGE.md §5, §5.3, §6.4](RELATIONSHIP_STORAGE.md) (and §8 for the two skip rules); budget in [TESTING.md §7](TESTING.md) |

---

_Git HEAD revision: `8f178b4`_
_Last updated: 2026-08-16 (IDX-3 closed out; §1's cost table and IDX-4's order-of-work note follow it)_
