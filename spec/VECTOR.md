# VECTOR.md — vector data in the graph

**Built.** Vectors were storable and not searchable: a `float[]` property round-tripped through
`ValueCodec`, and nothing could answer "which nodes have an embedding close to this one". This file
worked out what tying that in would mean, chose HNSW from four possible shapes (§4), and now records
what was built — including the parts of the design that only survived contact with a test.

Sections 1 to 4 are the reasoning, kept as written because the trade-offs did not change. §7 is the
plan and its outcome.

> **Do not put here**: how a property value is encoded ([PROPERTY_STORAGE.md](PROPERTY_STORAGE.md)),
> how the two existing B+trees work ([INDEX.md](INDEX.md)), the task list this came out of
> ([INDEX_TASKS.md](INDEX_TASKS.md)).

---

## 0. Status

| | |
|---|---|
| **Storable** | Yes — `ValueCodec.T_VECTOR_F32`, `Properties.getVector`, `float[]` of any length |
| **Where** | Always the blob store: 13 bytes inline (tag, `int` dimension, `long` blob id) plus a blob extent |
| **Indexed** | Exact equality in the property tree, **and HNSW** for nearest neighbour — §7 |
| **Equality lookup** | Works — the value is hashed by content, see §1.3 and [INDEX_TASKS.md IDX-1](INDEX_TASKS.md#3-closed) |
| **Nearest-k** | `GraphStore.nearestByScan(label, key, query, k, metric)` — **exact, by scanning**. No index, and that is the point: it is the ground truth an approximate one is measured against |
| **Where, once indexed** | A `VEC` record: the vector and its ANN links in one 4 KiB record, so a visited node costs one page read. Declared per store with `GraphStoreOptions.vectorDimensions` |
| **Cypher surface** | `vector.cosine` / `dot` / `l2`, and `CALL vector.query(label, key, $q, k) YIELD node, score` ([CYPHER.md §1.1](CYPHER.md)). Cypher still cannot *write* a vector |
| **Decision** | **HNSW, in six stages (§7). All six are built.** |

---

## 1. What exists today

### 1.1 Storage

`ValueCodec` treats a vector as a blob escape, unconditionally — unlike a string or a byte array,
which stay inline below `MAX_INLINE_BYTES` (255):

```java
case float[] vector -> {
    if (blobs == null) { throw new IllegalStateException("Vectors require a blob store"); }
    // ... little-endian raw bytes ...
    w.u8(tag(T_VECTOR_F32, 0));
    w.i32(vector.length);
    w.i64(blobs.store(raw));
}
```

So a node's property block holds 13 bytes for the vector, and the payload is one `BLOB` extent chain.
At the default 8 KiB page an extent carries 8,160 payload bytes, which means:

| Dimensions | Raw bytes | Extents | Reads to fetch the vector |
|---|---|---|---|
| 384 | 1,536 | 1 | property block + 1 |
| 768 | 3,072 | 1 | property block + 1 |
| 1,536 | 6,144 | 1 | property block + 1 |
| 3,072 | 12,288 | 2 | property block + 2 |

**One caveat that matters for any scan design**: `BlobStore.store` LZ4-compresses when that is
"worthwhile". Normalised f32 embeddings are close to incompressible, so in practice the flag will read
`COMPRESSION_NONE` and the bytes on the page *are* the vector — but a scan that assumes it can address
a blob extent as a raw `float` array must check the header rather than assume it.

### 1.2 The Cypher and wire surface

`GraphAccess` and `CypherWireValues` both convert `float[]` to a list of doubles, because Cypher has
no vector type. That is a correct choice for display and a bad one for transport: a 768-dim embedding
becomes 768 boxed doubles and roughly double the bytes on the wire. Returning embeddings to a client
in bulk would want a value type in the wire codec ([WIRE.md](WIRE.md)); returning *ids and scores*,
which is what an ANN query actually does, would not.

`Functions.java` has the three distance functions, and `CALL … YIELD` now parses: the refusal in
`CypherParser.unsupportedClause` is gone and there is one procedure, `vector.query`. See §6.

### 1.3 The defect underneath all of it, and the decision it forced

`IndexCatalog.valueHash` used to fall through to `value.hashCode()` for `float[]`, which is the
identity hash, so every vector property was indexed under a key nothing could reproduce: the lookup
missed the node, the delete missed the entry, and a unique constraint on the key never fired. Full
write-up in
[INDEX_TASKS.md IDX-1](INDEX_TASKS.md#3-closed).

It is recorded here because fixing it forced the question this file has to answer anyway — **should a
vector be in the property tree at all?** — and the answer taken was **yes, hashed by content**. A
`float[]` hashes by `Arrays.hashCode`, a list by its elements' hashes, and the candidate verification
walks lists element-wise so it agrees. The alternative was to skip vectors at the maintenance hooks and
document them as unindexed; it was rejected because a value type the tree silently ignores is a second
kind of silent miss, and because equality maintenance is what keeps `verify(FULL)` honest about a store
holding embeddings.

**That is exact equality and nothing more**, which §2 explains is nearly meaningless as a *search*: it
finds the node written from a byte-identical array. It is worth having anyway — it makes maintenance
sound and it costs one hash of the array on write — and it is not a step towards ANN. Everything from
§2 on stands unchanged.

---

## 2. Why the existing indexes cannot answer the vector question

The property tree is keyed on `(keyId << 32) | valueHash(value)` and is scanned by prefix. That
answers "which nodes have exactly this value". For an embedding that question is nearly meaningless:
two embeddings of the same face from the same model differ in the low bits of every component, so an
exact-match probe returns the one node that was written from the identical array and nothing else.

A range index would not help either, and it is worth being explicit about why, because "add an
order-preserving key" is the natural next thought. Nearest-neighbour search in 768 dimensions is not a
range query in any coordinate: the nearest vector by cosine distance can be larger than the query in
every single component. There is no ordering of the key space that makes a B+tree scan return
neighbours. **An ANN index is a genuinely different structure, not a differently-keyed tree.**

So the honest answer to "would it just be vector properties plus another index" is: **yes to the
first half and no to the second.** The declaration and maintenance machinery is shared with the other
indexes; the structure underneath is not.

---

## 3. What the index would be declared on

This is the part of the question with a clean answer.

### 3.1 Node vectors: `(label, property key)`

An ANN index cannot be implicit the way the label and property trees are. Three reasons, each of which
alone is decisive:

- **Dimension has to be fixed.** `float[]` carries its length per value; an index over vectors of
  mixed length is not an index. Declaring it is where the dimension gets pinned and where a
  mismatched write gets refused.
- **The metric has to be chosen.** Cosine, dot product and L2 give different neighbours, and the
  choice belongs to the model that produced the vectors, not to the store.
- **Building it is expensive.** Indexing every `float[]` property that happens to be written would
  make an unrelated bulk load pay for an ANN build nobody asked for.

So the declaration is `createVectorIndex(label, key, dimensions, metric, params)` — which is the same
declaration machinery as
[INDEX_TASKS.md IDX-7](INDEX_TASKS.md#idx-7--composite-label-property-indexes), a catalog list of
declared `(labelId, keyId)` pairs plus a backfill scan, exactly as `addUniqueConstraint` scans before
it declares. Build IDX-7 first and this reuses it.

**Put the producing model in the declaration too.** Two models produce vectors of the same length in
the same datatype and mean completely different things by them, and a distance between two of them is
a plausible-looking number with no meaning at all. Nothing stops two models writing
`:Person.embedding` at different times — an upgrade is exactly when it happens — and an index that
silently mixes them returns confident nonsense. So the identity of an index is
`(label, key, dimensions, metric, model)`, not just the pair it is declared on, and declaring the new
model's index beside the old one is what makes a model upgrade a re-index rather than a migration:
both are queryable, and the old one is dropped once its replacement has been shown to be better.

Either record the model and refuse a write that does not name it, or document that the application
owns that discipline. The first is cheap, and this engine's whole culture is to make the silent
failure impossible rather than to warn about it.

### 3.2 Relationship vectors: `(relationship type, property key)`

Structurally the same, with one hard limit inherited from the storage design: **a lightweight edge has
no property block and no id**, so it cannot carry a vector at all
([RELATIONSHIP_STORAGE.md §4](RELATIONSHIP_STORAGE.md)). Only a materialised relationship can, which
means a relationship vector index is an index over `REL` records — the same restriction that makes
[INDEX.md §6](INDEX.md) say there is no unique constraint on a relationship property.

That is not a reason to rule it out, but it is a reason to sequence it second: the node index is the
one with a use case (embeddings hang off entities), and the relationship version is a small addition
once anything at all indexes materialised relationships
([INDEX_TASKS.md IDX-6](INDEX_TASKS.md#idx-6--a-relationship-type-index)).

---

## 4. Four ways to build it

### 4.1 Option A — brute force over a label scan

No index. `nodesByLabel(label)` gives the candidate set; for each, read the property block, read the
blob, compute the distance, keep a top-k heap.

This is not a straw man. It is exact rather than approximate, it needs no new structure, no
maintenance hook and no compaction story, and for a hundred thousand vectors it is a few hundred
milliseconds — which is fine for the "find similar assets" query nobody issues at a thousand per
second.

Its cost is entirely reads, and they are scattered: one property block plus one blob extent per
candidate, in whatever order the blob allocator handed extents out. For a million 768-dim vectors that
is roughly 3 GB fetched through two levels of indirection per vector.

**Which suggests the one structural improvement worth making before any ANN work**: give an index its
own contiguous vector storage.

### 4.2 The fixed-stride vector segment, and the constraint nobody expects

A declared index knows its dimension, so its vectors are **fixed-size records** — precisely the shape
this engine is built around. A new `Kind` (codes 8..15 are free) whose stride is the vector size gives
a scan that is sequential, and gives the addressing invariant back: a vector id decodes arithmetically
to a byte offset, so an ANN structure holding candidate ids fetches their vectors with **zero probes**
([ID_ENCODING.md](ID_ENCODING.md)).

But [GRAPH_STORAGE_CONTEXT.md §5.1](GRAPH_STORAGE_CONTEXT.md) requires that every record stride divide
`PAGE_SIZE`, and `PAGE_SIZE` is 4, 8 or 16 KiB — all powers of two. **So a vector record stride must
be a power of two.** Every existing stride obeys this (64, 32, 512) and it has never bitten anyone,
because no record size has been application-chosen before. Vectors are:

| Vectors | Raw bytes | Legal stride | Waste |
|---|---|---|---|
| 512 × f32 | 2,048 | 2,048 | none |
| 1,024 × f32 | 4,096 | 4,096 | none |
| **768 × f32** | 3,072 | 4,096 | **25%** |
| 768 × f16 | 1,536 | 2,048 | 25% |
| 768 × int8 | 768 | 1,024 | 25% |
| 384 × f32 | 1,536 | 2,048 | 25% |

768 is the most common embedding width in circulation and it is the one that does not fit. Three
honest responses: pad and accept 25 percent, quantise (int8 at 1,024 bytes is a quarter of padded f32
and is what production ANN does anyway, at a small recall cost), or pack several vectors per page with
an explicit intra-page offset — which breaks the address function and should not be done.

**Recommendation: pad, and store the true dimension in the index declaration.** Quantisation is a
second-order optimisation that can be added as a second element type once anything works at all, and
it changes the metric's behaviour, which is not a decision to bundle into a layout choice.

### 4.3 Option B — IVF: centroids as nodes, membership as edges

Cluster the vectors into `√n`-ish centroids; each vector belongs to one; a query finds the `p` nearest
centroids and scans only their posting lists.

The reason this fits *this* engine unusually well: a posting list is a **contiguous run of vectors**,
and everything in [SSD_IO.md](SSD_IO.md) and the adjacency design is built around preferring one
sequential read to many random ones. Reading 1/100th of the vectors as a few sequential runs is close
to the best case the storage layer has.

It is also the cheapest to build correctly. Clustering happens outside the write lock; the writes are
bulk appends; there is no concurrent-structure problem; and a badly-clustered index degrades to
Option A rather than to wrong answers.

Its weakness is the standard one: recall falls at cluster boundaries, and a vector near the edge of
its cluster is missed unless `p` is raised. That is tunable per query, which is the right place for it.

### 4.4 Option C — HNSW built out of the store's own primitives

The observation that makes this tempting: **HNSW is a graph**, and this is a graph store whose fastest
operation is "enumerate the neighbours of this node".

```mermaid
graph TB
    subgraph L2["layer 2 — few nodes, long links"]
        A2["v17"] --- B2["v903"]
    end
    subgraph L1["layer 1"]
        A1["v17"] --- B1["v221"] --- C1["v903"]
    end
    subgraph L0["layer 0 — every vector, M≈16 links"]
        A0["v17"] --- B0["v221"] --- C0["v88"] --- D0["v903"]
    end
    A2 -.-> A1 -.-> A0
```

Every HNSW link is a **lightweight edge** — no properties, 8 bytes per direction, no `REL` record. At
`M = 16` a node's layer-0 neighbour list is 32 directed entries, which fits in a **single 512 byte
adjacency cell** with room to spare ([RELATIONSHIP_STORAGE.md §5](RELATIONSHIP_STORAGE.md): one cell
holds 400 directed entries). The layer is the relationship type. Traversing the graph during a search
is the engine's core path, delta-compressed and already measured.

And it inherits, free, three things most ANN libraries do not have: **crash safety** (the WAL),
**snapshot isolation** (a search runs at a fixed LSN while writers commit underneath), and
**transactional maintenance** (an index update rolls back with the data update it belongs to, exactly
as the B+tree hooks do).

Three costs, none of them small:

1. **Build is write-bound and writers do not scale.** This engine serialises writers on one lock by
   design ([TRANSACTIONS.md](TRANSACTIONS.md)). An HNSW insert performs a search — hundreds of
   distance computations, each needing a vector read — *inside* the write path. Building over ten
   million vectors single-threaded is the kind of number that turns into hours, and
   [reports/SCALE.md](reports/SCALE.md) is the cautionary precedent for finding that out after
   building it.
2. **Deletion is HNSW's known weak spot.** Removing a node from the layer graph without breaking
   connectivity means tombstoning and periodic rebuild — which this engine actually has a home for,
   since compaction already rebuilds both B+trees wholesale via `resetTrees` rather than relocating
   pages.
3. **The vectors themselves still need §4.2**, so this is additive to that work, not an alternative.

### 4.5 Option D — do not build it here

Approximate nearest-neighbour search is a solved, commodity problem with mature implementations —
HNSW libraries, Lucene, and a shelf of purpose-built vector stores — all of which have had far more
tuning than anything written here would get, and none of which is difficult to keep beside a graph
store as a **derived, rebuildable cache**: the vectors live in the graph, the ANN index is populated
from them, and it can be dropped and rebuilt at any time because it was never the system of record.

That is the option to beat, and it sets the bar high. An index built here does not have to be *as
fast* as a specialist library — it has to be worth the second implementation, in a codebase whose one
measured comparison against an incumbent store concluded that a 2.4x win was not worth adopting a
second system.

**The case for building it anyway is not performance — it is the combined query.** "The twenty nearest
embeddings *that are reachable in two hops from this node and carry the tag X*" is something the
split architecture cannot do well: the external index knows nothing about the graph predicate, so it
must over-fetch and let the caller filter, and it has no way to know how far to over-fetch. A vector
index living inside the graph can apply the predicate *during* the search, because the graph traversal
and the vector search are the same walk over the same store. If that query is not on the roadmap,
Option D is the correct answer and the rest of this file is interesting rather than useful.

### 4.6 Comparison

| | A — brute force | B — IVF | C — HNSW | D — external |
|---|---|---|---|---|
| New `Kind` needed | no | yes (§4.2) | yes (§4.2) | no |
| Recall | exact | tunable, good | best | best |
| Build cost | none | one clustering pass | high, single-threaded | outside |
| Query cost at 10^6 | full scan | ~1% of vectors, sequential | ~0.1%, random | outside |
| Fits the write model | yes | yes | **poorly** | n/a |
| Graph-predicate pushdown | yes | yes | yes | **no** |
| Effort | S | M | L | none |

---

## 5. How it plugs into the engine

Whichever structure is chosen, the wiring is the same and is already specified by the existing
indexes:

- **Kind.** A `VEC` kind for the vectors (§4.2). Codes 8..15 are unused. An IVF posting list or an
  HNSW layer graph needs no new kind at all — they are `ADJ` and `INDEX` pages, or actual nodes and
  lightweight edges.
- **Declaration.** A list of declared indexes in the catalog page, alongside the unique-constraint list
  at offset 64. **Not** between offsets 24 and 32 — [INDEX.md §2.1](INDEX.md) records what that cost
  last time.
- **Maintenance.** The same four hooks on `IndexCatalog` — `onNodeCreated`, `onNodeDeleted`,
  `onPropertiesChanged`, `onLabelsChanged`. All four are already called from inside the caller's
  transaction, so an index write commits or rolls back with its data write. `onLabelsChanged` is the
  interesting one: adding `:Person` to a node that already carries an embedding must index it.
- **Snapshot isolation.** Free, and for the same reason as the B+trees: index pages are read through
  `SegmentFile.readSlice`, so a view sees them as of its own LSN ([TRANSACTIONS.md §5](TRANSACTIONS.md)).
- **Compaction.** Node ids move, so anything storing them must be rebuilt. `resetTrees()` is the
  precedent and the right pattern — an ANN structure is rebuilt, not patched up, and rebuilding is
  what an HNSW wants periodically anyway.
- **Blob interaction.** If a `VEC` kind holds the vectors, the property block should hold a `VEC` id
  rather than a blob id for indexed vectors — otherwise every vector is stored twice. That is a
  property-block format decision and belongs in [PROPERTY_STORAGE.md](PROPERTY_STORAGE.md) before any
  of this is built.

---

## 6. The query surface

There is no procedure mechanism: `CypherParser` rejects `CALL` with "this build has no procedure
mechanism", so the neat form —

```cypher
CALL vector.query('Person', 'embedding', $q, 10) YIELD node, score
```

— needs a parser feature before it needs an index. **All three of the surfaces below are being built**,
in the order they are listed, which is also the order of how much each asks of the parser.

1. **The distance functions**, built in stage 1 and useful on their own: `vector.cosine`,
   `vector.dot`, `vector.l2`, specified in [CYPHER.md §1.1](CYPHER.md) along with the parser fold that
   makes a namespaced name possible at all. They are what re-ranks a small candidate set, and they are
   the ground truth any ANN index is measured against.
2. **The Java API**, also stage 1: `nearestByScan(label, key, query, k, metric)` today, joined by an
   indexed `nearest(...)` in stage 4.
3. **A minimal `CALL … YIELD`**, in stage 6. Explicit, and honest about the access path being chosen
   by the caller rather than by a planner that cannot choose — and it buys something no other spelling
   does: a `CALL … YIELD node` binds `node` before the following `MATCH`, so the pattern matcher takes
   its *already bound* branch and verifies rather than scans. That is the graph-predicate pushdown of
   §4.5, for free, with no planner change.

**What is deliberately not built is the implicit rewrite.**
`ORDER BY vector.cosine(n.embedding, $q) LIMIT 10` is the most natural Cypher and the worst fit: the
planner is "a fixed order rather than a cost model" ([INDEX.md §7](INDEX.md)), so it cannot recognise
a top-k, and `ORDER BY` is sort-then-truncate with a comparator that recomputes its key — so the
distance is evaluated `O(n log n)` times rather than `n`. The spelling to use instead is in
[CYPHER.md §1.1](CYPHER.md): project the score once in a `WITH` and order on the alias.

---

## 7. The plan, and what is built

**The decision is HNSW** (§4.4), at a target of **10M vectors of 768 f32 dimensions**, with a declared
index, incremental maintenance through the four hooks *and* a bulk rebuild, and the query surface of
§6. IVF (§4.3) is not being built first, which is a deliberate departure from what the earlier version
of this section recommended.

Two things follow from the scale, and they changed the design rather than being absorbed by it:

- **The vectors leave the blob store.** A `BLOB` extent is page-sized, so a 3,072-byte vector costs
  8 KiB today; 10M of them is ~80 GB. They move to the `VEC` kind of §4.2, whose record
  **interleaves the vector with its HNSW links**, so a visited node costs one page read rather than
  two and 10M costs ~41 GB. The property block holds a `VEC` id, not a blob id — §9's question 3,
  answered: **one copy, not both.** Keeping both would force `onPropertiesChanged` to update two of
  them, and a partial update is a silent wrong answer, which is exactly the IDX-1 failure class.
- **The bulk build's searches run outside the writer lock.** §9's question 4, answered: an insert at
  `efConstruction = 200` is ~2,000 distance evaluations, and doing those under the single writer lock
  is the "hours" number §4.4 warns about. The search runs in a read transaction against a snapshot and
  only the resulting link sets are written.

| Stage | What | Status |
|---|---|---|
| **1** | `vector.cosine`/`dot`/`l2`, the namespaced-call parser fold, `nearestByScan`, and a bound on the vector dimension in `ValueCodec` | **built** |
| **2** | `Geometry.KINDS` and `IoCounters.KINDS` 8 → 16 via `Kind.MAX_KINDS`, and `Kind.VEC` declared but disabled | **built** |
| **3** | `VEC` as the property backing: `GraphStoreOptions.vectorDimensions`, the manifest fields, `T_VECTOR_REF`, compaction, the invariant check | **built** |
| **4** | The declared index, its catalog entry, and membership maintained by the hooks | **built** |
| **5** | HNSW links, incremental insert, entry-point repair | **built** |
| **6** | The batched resumable build, BUILDING/READY, and `CALL … YIELD` | **built** |

**What the format bump turned out to be.** Not a bump for most stores: `Manifest.open` compares with `!=`, so
writing 4 unconditionally would have refused every store on disk to describe a field they do not have. The version is
a function of the content — 3 when `vec_stride` is zero, 4 when it is not — and both are accepted, with the pair
required to agree. A store with no vectors is bit-identical to what it was.

**Three things the tests found that the design did not.**

- **Two dimensions can share a stride.** A stride is a power of two rounded up, so 4 and 8 both land on 512, and a
  check on the stride alone would have let a store created for one be opened as the other — reading every vector at
  the wrong length. The manifest compares both numbers.
- **Dropping an index left `FLAG_LINKED` set**, so the *next* index's build skipped every member as "already linked",
  ended with no entry point, and answered every query with nothing. A record that leaves an index is now unlinked.
- **Nearest-M pruning strands outliers.** Inserting one point outside a tight cluster gave it no in-links at all: every
  neighbour was full of its own cluster, and proximity pruning drops the outsider first. It was in the index, correct
  on disk, and returned by nothing. Both selection paths now use the paper's diversity heuristic — keep a candidate
  only if it is nearer to the anchor than to anything already kept.

**Stage 1 is the ground truth and had to come first.** Every measured surprise this project has had —
[reports/SCALE.md](reports/SCALE.md)'s 59-of-61 GiB, the codec that turned out 7.8x slower than the
JDK — came from building the sophisticated thing before measuring the simple one, and an approximate
index with no exact answer to compare against cannot be measured at all.

---

## 8. What it is tested against

The repository's existing culture applies directly, and one test class matters more than the rest.

**Built in stage 1:**

| | |
|---|---|
| `VectorsTest` | The three distance functions on their own, including the zero-vector case and the reason every accumulator is a `double` |
| `VectorScanTest` | `nearestByScan` end to end. The load-bearing case computes the expected answer a **second way** — by sorting every vector inside the test — for all three metrics, so the scan is never its own oracle |
| `VectorFunctionTest` | The Cypher functions, and specifically the two shapes a vector arrives in: a `float[]` parameter, and the list of doubles a *stored* vector has already become |
| `CypherParserTest` | That a namespaced call is not parsed as a property access, and that the fold is narrow enough to leave `n.a.b` alone |
| `HostileInputTest` | A vector declaring billions of dimensions. It was the last unbounded length field in the property codec |

**Still to come, with the stage that brings it:**

- **A brute-force reference model** (stage 5). `ReferenceModelTest`'s argument — "an index that agrees
  with itself proves nothing" — is even stronger for an approximate index. Exact top-k from
  `nearestByScan`, compared against the index, reported as **recall@k** rather than as pass/fail, with
  a floor the build fails below. Recall *under a filter* is a separate measurement with its own floor:
  see §4.4's note on why filtering traversal candidates collapses it.
- **`addNodeLabel` / `removeNodeLabel` in `ReferenceModelTest`'s operation mix** (stage 4). They are
  not there today, so `onLabelsChanged` — the hook a `(label, key)` index depends on most — is not
  under the differential oracle at all.
- **Dimension and metric mismatch are refused**, not silently accepted: a 512-dim write into a 768-dim
  index, and a query vector of the wrong length.
- **Maintenance completeness**: create, update the vector, relabel, delete — each followed by a query
  that must not return the stale answer. This is where the four hooks earn their place, and where
  [IDX-1](INDEX_TASKS.md#3-closed)'s dangling
  entry came from.
- **`verify(FULL)`** over a store with a declared vector index, since `NoDanglingRefs` is what catches
  an index entry pointing at a freed slot.
- **Compaction**: ids move, the index still answers — the case `resetTrees` exists for.
- **Crash**: an index update and its data update land together or not at all, in the existing fault
  matrix.

---

## 9. Questions, answered and open

**Answered, and the answers are what shaped §7:**

1. ~~Does the combined graph-plus-vector query exist as a requirement?~~ **Yes** — it is the reason
   this is being built here rather than beside the store (§4.5), and §6's `CALL … YIELD` is how it is
   expressed.
2. ~~Is 768 dimensions the target?~~ **Yes.** So the stride is 4096 with the vector padded, and §4.2's
   table is the sizing that follows.
3. ~~Where does the vector live once indexed?~~ **`VEC` only, never both** — §7.
4. ~~Does an ANN build have to happen under the write lock?~~ **No, and it must not** — the search runs
   in a read transaction, only the link sets are written (§7).

**Still open:**

- **What recall floor is worth failing a build over?** It has to be a number in
  `performance-gates.properties` before stage 5 lands, and it cannot be chosen without measuring what
  this implementation actually achieves on a realistic corpus.
- **What does the single-threaded insert rate turn out to be?** Measured on 100k vectors before stage 6
  commits to the bulk build, because it is the one number that could still say HNSW is unaffordable
  here — and finding that out after building it is the mistake [reports/SCALE.md](reports/SCALE.md)
  records.
- **Does a second index at a different dimension ever need to coexist?** One `Kind` means one stride
  means one dimension ceiling per store (§4.2). The escape hatch is several `VEC` kind codes, and it is
  not being built until something needs it.

---

_Git HEAD revision: `8f178b4`_
_Last updated: 2026-08-16 (HNSW vector search, stages 1 to 6)_
