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

---

## 0. The task list

| # | Task | Priority | Effort | Verdict |
|---|---|---|---|---|
| [IDX-1](#idx-1--a-float-property-is-indexed-under-a-non-deterministic-key) | `float[]` was indexed under an identity hash | **P0 — defect** | S | **Done.** Vectors are hashed by content; lists too |
| [IDX-2](#idx-2--push-the-type-filter-into-adjacency-for-materialised-entries) | Type filter before the relationship read | **P1** | M | **Done.** The record is read once per surviving candidate |
| [IDX-3](#idx-3--block-skipping-on-the-read-path) | Type-bitmap / min-max block skipping on reads | **P1** | S | Build. The metadata is already written and nothing reads it |
| [IDX-4](#idx-4--a-streaming-neighbour-cursor) | Streaming neighbour cursor | **P1** | M | Build. A `LIMIT 1` over a supernode currently materialises every edge |
| [IDX-5](#idx-5--a-relationship-scan-that-does-not-go-through-the-nodes) | `forEachRelationship` over the `REL` segment | P2 | S | Build. ~20 lines, and it removes most of the reason to want IDX-6 |
| [IDX-6](#idx-6--a-relationship-type-index) | Relationship type index | P3 | L | **Do not build yet.** See §1 |
| [IDX-7](#idx-7--composite-label-property-indexes) | Composite `(label, property)` indexes | P2 | M | Build when a workload has a label plus a low-selectivity property |
| [IDX-8](#idx-8--statistics-enough-to-choose-a-label) | Per-label / per-key cardinality | P2 | M | Build with IDX-7 — it is what makes a choice between two indexes possible |
| [IDX-9](#idx-9--vector-data) | Vector properties and ANN search | P3 | L | Explored separately in [VECTOR.md](VECTOR.md) |

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
| Skip blocks that cannot hold `:RARE` | **not done** — every block is decoded | [IDX-3](#idx-3--block-skipping-on-the-read-path) |
| Materialise every entry into a `long[]`, then into `List<Edge>` | **10^6 longs and 10^6 `Edge` objects, before the caller sees the first row** | [IDX-4](#idx-4--a-streaming-neighbour-cursor) |
| For each *materialised* entry, read the 64 byte `REL` record **and then** test the type | **fixed** — the entry caches its type, so the read is paid per surviving candidate | [IDX-2](#idx-2--push-the-type-filter-into-adjacency-for-materialised-entries) |

The last row was the one that hurt, and it is worth being precise about why it existed.
`GraphStoreImpl.materialise` read the relationship record before it could apply the type filter,
because a materialised adjacency entry (`dir == 3`) did not carry its type — the type came from the
record:

```java
// GraphStoreImpl.materialise, before IDX-2
long relId = liveRelationshipId(PackedEdge.relationshipId(packed));
int typeId = rels.typeId(relId);              // <- the read happens here
if (typeIds != null && !typeIds.contains(typeId)) {
    return;                                    // <- the filter happens after it
}
```

The entry now caches the type and the filter runs first, off bits that were already decoded
([RELATIONSHIP_STORAGE.md §3.1](RELATIONSHIP_STORAGE.md)). The two remaining rows are still open.

A relationship type index would not fix any of those five rows. It answers "which relationships have
type T" globally; the supernode query already knows which node it is on, and its problem is that the
adjacency it holds is enumerated in full and unfiltered. **So the supernode work is IDX-2, IDX-3 and
IDX-4, all of which are inside the adjacency layer, and none of which is an index.**

The relationship index is worth building only when a workload really does ask the global question, and
[IDX-5](#idx-5--a-relationship-scan-that-does-not-go-through-the-nodes) answers a large part of that
question for a fraction of the cost. Its own entry says why it stays at P3.

---

## IDX-1 — a `float[]` property is indexed under a non-deterministic key

**Priority P0, and it was a defect rather than a gap. Fixed.**

`IndexCatalog.valueHash` normalised the numeric types and hashed `String` and `byte[]` by content, and
everything else fell through to `value.hashCode()` — which for a `float[]` is the identity hash, so
every vector property was indexed under a key nothing could reproduce. Three symptoms, none of them
noisy: an equality lookup on a vector returned nothing, a delete left the entry behind for
`verify(FULL)`'s `NoDanglingRefs` to report at some later unrelated moment, and a unique constraint on
a vector key never fired because `conflicts()` probed with the same unusable key. Queries never
returned *wrong* nodes — the candidate verification handles arrays — so the whole cost was a missed
answer and an index that leaked entries.

**The decision this forced**, which [VECTOR.md §1.3](VECTOR.md) put here rather than answering itself:
**vectors stay in the property tree, hashed by content.** Exact equality on a 768-float embedding is a
narrow question and it is a well-defined one, it is what makes index maintenance sound, and skipping
the value at the hook would have meant a value type the tree silently ignores — a second kind of
silent miss in place of the first. It is not, and does not grow into, nearest-neighbour search;
[VECTOR.md §7](VECTOR.md) stage 0 is where that story continues.

**What changed:**

- `float[]` hashes by `Arrays.hashCode`, which compares the same bits `Arrays.equals` does and so
  agrees with the verification applied to every candidate.
- A list hashes by mixing its elements' `valueHash` instead of by `List::hashCode`, which delegates to
  its elements and inherited the defect from any array inside one.
- The `default` branch routes any remaining array shape through `Arrays.deepHashCode`. That is the
  branch that matters next: it stops the next array-shaped value type from reintroducing this.
- `IndexCatalog.valueEquals` — `Objects.deepEquals` plus an element-wise walk of a list — replaces
  `Objects.deepEquals` at the three sites that verify a candidate against a stored value
  (`nodesByProperty`, `checkUnique`, `onPropertiesChanged`). Without it the hash found the entry for a
  list holding a vector and the verification then threw the node away, which is the same silent miss
  arriving one step later. Hash and equality are one decision made in two places
  ([INDEX.md §3.2](INDEX.md)).

**Verified by**: `IndexKeyTest` (equal `float[]`s, lists holding one, unnamed array shapes, and the
numeric normalisation that was already there); `IndexTest.testAVectorPropertyIsFoundByItsValue` — which
also asserts that create-then-delete puts `labelIndexSize()` and `propertyIndexSize()` back where they
started — plus the list and update variants; `UniqueConstraintTest.testADuplicateVectorIsRefused`; and
`ReferenceModelTest`, whose random workload now writes a vector property drawn from a small pool, so
the differential comparison covers vector lookups over thousands of operations across rollbacks,
crashes, a compaction and `verify(FULL)`. Reverting the `float[]` branch to the identity hash fails
three of its four tests, which is the check that the workload has teeth.

---

## IDX-2 — push the type filter into adjacency for materialised entries

**Priority P1. Done.** The 14 type bits of a materialised adjacency entry were zero, so a
type-filtered traversal over a supernode had to read a relationship record before it could decide
whether the edge was even a candidate — one random 64 byte read per edge, for a query that may return
nothing. The entry now caches its type, and the filter runs off the entry's own bits, so the read is
paid **once per surviving candidate**.

**The tombstone was the constraint that made this delicate.** `TOMBSTONE == -1L` was unreachable
precisely *because* a materialised entry always carried type 0, so `dir=3, type=MAX_TYPE` could not
be produced. `pack` now rejects exactly that one combination instead — a materialised entry may cache
any type but `MAX_TYPE` — which keeps the tombstone unreachable through the constructors, caps the
fast path at 16,382 distinct types, and needs no new bits.

**No format version bump.** Type id `0` is reserved as "not recorded" alongside `MAX_TYPE`, which
costs nothing: `Catalog` already hands out 0 for an unknown label, so no relationship has type 0. An
older store's entries all hold zero, which under the new reading means what it meant then — read the
record. Three rules follow, and each is a way this could have silently lost an edge:

- **A zero is never a mismatch.** `PackedEdge.excludedByType` refuses to filter out a materialised
  entry that caches no type. A false positive costs the record read that used to be unconditional; a
  false negative drops a real edge from an answer.
- **Deletion matches by relationship, not by value.** `AdjacencyStore.removeMaterialised` compares
  through `PackedEdge.TYPE_AGNOSTIC_MASK`, because the entry on disk may hold either shape and
  `deleteRelationship` builds only one of them. Matching exact bits would have left every older
  entry behind as dangling adjacency — visible only later, as a `verify(FULL)` failure.
- **A stale cache is corruption.** `InvariantChecker` compares every cached type against its record.

**What it does not fix.** Direction still comes from the record: the 48 bit field holds the
relationship's location rather than the neighbour's, so `outgoing = relFrom == fromNodeId` is not
answerable from the entry and `Direction.OUT` with no type filter still reads every record. That is
the right trade — the type filter is the selective one.

**A note for [IDX-3](#idx-3--block-skipping-on-the-read-path)**: an entry that caches no type
contributes bit 0 to its block's `type_bitmap`, not the bit of its real type. Block skipping on the
read path must not skip on that bitmap alone for a chain that may hold pre-IDX-2 entries
([RELATIONSHIP_STORAGE.md §8](RELATIONSHIP_STORAGE.md)).

**Verified by**: `ReadBudgetTest.testATypeFilteredSupernodeReadsOnlyTheSurvivingRelationships`, which
counts `Kind.REL` record touches for a filter matching 5 of 5,005 materialised edges on one node — 20
touches, against 10,020 with the caching reverted, and against the unfiltered scan that still resolves
every entry. `PackedEdgeTest` round-trips a cached type, pins both uncacheable type ids to the
fallback, and asserts the tombstone is still unreachable through every constructor including the new
one. `AdjacencyTierTest` covers the entry shape, the pre-IDX-2 shape traversing, filtering and
deleting, and the stale cache that `verify(FULL)` must reject.

---

## IDX-3 — block skipping on the read path

**Priority P1, and the cheapest item on this list.** `AdjacencyBlock` already maintains a
`type_bitmap` and `min_location` / `max_location` per block, updated on every append. Nothing on the
read path consults them:

- `mayContainType` has exactly one caller, `AdjacencyStore.remove`.
- `mayContainLocation` has **no callers at all**.

`AdjacencyStore.edges(nodeId)` walks the whole chain and decodes every block unconditionally.
[RELATIONSHIP_STORAGE.md §10](RELATIONSHIP_STORAGE.md) already lists "Type-bitmap skipping on the read
path" as unchecked; this is that item.

**Shape.** A filtered variant, `edges(nodeId, Set<Integer> typeIds)`, which skips a block when the
bitmap says no type in the filter can be present, and a `hasEdge(a, b)` that uses `mayContainLocation`
to skip blocks that cannot reference the target — which is the operation
[RELATIONSHIP_STORAGE.md §5.3](RELATIONSHIP_STORAGE.md) claims is `O(log blocks)` and which does not
exist in the code at all.

**Two honest limits.** The bitmap is 64 bits over `typeId & 63`, so past ~64 distinct types the
false-positive rate makes skipping worth little; and skipping only pays on a *chain*, which starts
above one page — roughly 8,000 entries. Below that there is one block and nothing to skip. So this is
a supernode optimisation and has to be measured as one.

**There is nothing to measure it with.** [RELATIONSHIP_STORAGE.md §9](RELATIONSHIP_STORAGE.md) lists
`SupernodeTest` ("assert that a filtered scan on a rare type skips at least 90 percent of blocks") and
`TraversalCostTest` ("an instrumented `IoEngine` counting device reads"), and neither class exists —
§9 is a statement of intent whose names were never reconciled with the tree. The behaviour is covered
elsewhere under other names (`AdjacencyTierTest`, `SpaceEfficiencyTest`, `SupernodeBenchmark`), but
**block-read counting is not covered anywhere**, which is why nobody has noticed that the read path
ignores the bitmap. Build the counting harness first; it is the deliverable IDX-2 and IDX-4 also need.

**Done when**: a type-filtered traversal over a chain reads only the blocks whose bitmap admits the
type, counted by an instrumented `IoEngine`; `mayContainLocation` has a caller; `hasEdge` exists or
§5.3's `O(log blocks)` claim is corrected; §9's test list matches the classes that exist.

---

## IDX-4 — a streaming neighbour cursor

**Priority P1.** `AdjacencyStore.edges` returns `long[]` and `GraphStoreImpl.neighbours` returns
`List<Edge>`. Both are fully materialised before the caller sees anything. At degree 10^6 that is an
8 MB `long[]` plus a million `Edge` records, for a query that may want one row.

`NeighbourCursor` is listed in [RELATIONSHIP_STORAGE.md §11](RELATIONSHIP_STORAGE.md) as a target
class and does not exist. The Cypher layer already has the right shape to consume one: `RowConsumer`
and `forEachNodeWhile` exist precisely because "a scan of ten million nodes must not become a list of
ten million rows", and the same argument applies one level down.

**Interaction with the other three.** IDX-2 and IDX-3 reduce how much work each entry costs; IDX-4
reduces how many entries are touched at all when the query stops early. IDX-2 is done; do IDX-3 next
(it is small and the cursor should be built on the filtered path), then IDX-4. The cursor must apply
the entry-level type filter IDX-2 added, which is `PackedEdge.excludedByType` — a cursor that
re-derives the rule is a second place to get the "type 0 means not cached" case wrong.

**Watch the read view.** `neighbours` runs inside `read(...)`, which may take the optimistic path and
re-run the body. A cursor that outlives the call cannot do that — it needs the latch held outright,
the way `forEachNodeWhile` uses `runRead` and documents why ("the consumer has side effects").

**Done when**: IDX-3's read-counting harness shows a `LIMIT 1` over a degree-10^6 node reading one
block; `neighbours(...)` is expressed in terms of the cursor rather than duplicating it.

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
safe, and it is the same argument [INDEX.md §3.2](INDEX.md) makes for the property tree.

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

**Priority P3, explored in [VECTOR.md](VECTOR.md).** Vectors are already storable —
`ValueCodec.T_VECTOR_F32`, blob-escaped, `Properties.getVector` — and nothing indexes one, which
[INDEX.md §11](INDEX.md) lists as "Full text, spatial, vector". The short version of the separate
file: an ANN index is a *declared* index over a `(label, property key)` pair, which makes it the same
declaration machinery as IDX-7 and a completely different structure underneath, because nearest
neighbour is not a prefix scan and no B+tree answers it.

IDX-1 is a prerequisite in the narrow sense that it decides what an equality lookup on a vector
property even means today.

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

_Git HEAD revision: `3a05a2a`_
_Last updated: 2026-08-16_
