# INDEX.md — the secondary indexes

Two B+trees: **label → node** and **(property key, value) → node**. They exist so that
`MATCH (n:Person {email: $e})` is a probe rather than a scan, and they are the only structures in this
engine whose pages may be relocated freely.

> **Do not put here**: the addressing invariant ([ID_ENCODING.md](ID_ENCODING.md)), the record layouts
> ([FILE_FORMAT.md](FILE_FORMAT.md)), how a property value is encoded
> ([PROPERTY_STORAGE.md](PROPERTY_STORAGE.md)), adjacency and traversal
> ([RELATIONSHIP_STORAGE.md](RELATIONSHIP_STORAGE.md)), or which access path a query chooses
> ([CYPHER.md §7](CYPHER.md)).

---

## 0. Status

| | |
|---|---|
| **What is indexed** | Node labels, node property values, node unique constraints |
| **What is not** | **Relationships — not by type, not by property.** Values are indexed by *hash*, so no ranges |
| **Structure** | One B+tree per index, over `INDEX`-kind pages. `IndexCatalog` + `BTree` |
| **Transactionality** | An index write is part of the same commit as the data write, through the same page overlay |
| **Maintenance** | Four hooks on `IndexCatalog`, called from `GraphStoreImpl` |
| **Rebuild** | `resetTrees()` — compaction rebuilds rather than relocating page by page |

---

## 1. Why an index at all, given the addressing invariant

Reading an element **by id** costs no index probe: the id decodes arithmetically into a file and a byte
offset ([ID_ENCODING.md](ID_ENCODING.md)). That is the whole point of the id encoding, and it is why
there is no "primary index" here — there is nothing for one to do.

An index answers the other question: *which* ids. `MATCH (n:Person)` and
`MATCH (n {email: 'a@b.c'})` have no id to start from, and without an index the only honest answer is a
full scan of every live node. So these two indexes exist for exactly the two starting points the query
language can use, and nothing else.

---

## 2. The B+tree

`BTree`, over `INDEX`-kind pages. One node is exactly one page, addressed by an `INDEX` id — so a tree
node lookup is a slice at a computed offset, the same arithmetic as everything else, rather than a
lookup in some other structure.

```
Node header, 40 bytes:
 0  4  entry_count
 4  1  leaf
 5  3  reserved
 8  8  next_leaf        leaf chaining, for a prefix scan that spans pages
16 14  reserved
30  2  gen              maintained by the slot allocator — see the warning below
32  8  leftmost_child   internal nodes only
40  N  entries          24 bytes each: (k1, k2, value)
```

The key is **128 bits** (`k1`, `k2`) and the value is 8 bytes. A leaf entry is `(k1, k2, elementId)`;
an internal entry is `(k1, k2, childId)` meaning "keys at or above this separator live in that child".

**Fanout** is `(stride − 40) / 24`. `INDEX` is page-sized, so at the default 8 KiB page that is
**339 entries per node** — a tree over ten million entries is three levels deep, and its upper two
levels are a few hundred kilobytes that stay in page cache.

**Deletion does not rebalance.** An entry is removed and an underfull node is left underfull. That is
correct — it is still a valid tree — and merging is deferred to compaction, which rebuilds the whole
tree anyway. The cost is a tree that degrades under a delete-heavy workload until something compacts.

### 2.1 The offset-30 trap, which cost a defect

`Kind.INDEX` keeps its **generation at offset 30**, written and read by the slot allocator and
invisible to `BTree` and `IndexCatalog`. Anything written between offsets 24 and 32 destroys it, and
every id naming that page becomes stale on the next read.

`IndexCatalog`'s unique-constraint list originally sat at offset 28 and did exactly that. It now starts
at **64**, with a comment saying why. Anyone adding a field to an index page should put it past 64 and
not reason about the gap.

---

## 3. The two indexes

### 3.1 Label → node

```
k1 = labelId    k2 = nodeId    value = nodeId
```

**One entry per label, not per node.** A node with three labels has three entries, which is what makes
`MATCH (n:Second)` find a node whose second label it is. An index carrying only the first label would
silently miss it — that is a wrong answer, not a slow one.

`nodesByLabel` is `scanPrefix(labelRoot, labelId)`: it walks leaves from the first matching key,
following `next_leaf`, and stops at the first entry with a different `k1`. Because `k2` is the node id,
**results come back in id order**, which is stable but is not an ordering a caller should rely on —
compaction changes ids.

### 3.2 (property key, value) → node

```
k1 = (keyId << 32) | valueHash(value)     k2 = nodeId    value = nodeId
```

**The value is hashed, not stored.** A property value can be a 64 KiB string; a key is 128 bits. So the
tree holds a hash and the caller **verifies every candidate** against the stored property:

```java
// GraphStoreImpl.nodesByProperty
for (long nodeId : indexes.nodesByProperty(key, value)) {
    Object stored = properties.read(nodes.propBlockId(nodeId), keyId);
    if (IndexCatalog.valueEquals(stored, normalise(value))) { confirmed.add(nodeId); }
}
```

**This never returns a wrong answer, and that is the property that matters.** A hash collision costs a
wasted read; skipping the verification would return a node whose property merely hashed the same. The
same verification is what `checkUnique` does before refusing a write — refusing a legitimate write on a
collision would be as bad as accepting an illegitimate one.

**`valueHash` normalises across the numeric types**, so `Byte`, `Short`, `Integer` and `Long` holding
the same number hash the same, and `Float` and `Double` likewise. Without that, a property written as
`1` and searched for as `1L` would miss — and would miss *silently*, which is the failure mode this
whole layer exists to avoid.

**`valueHash` depends on content and never on identity**, which is a separate statement and the one
that costs something to keep true. `Object::hashCode` on an array is the identity hash, so a
`float[]` property — the one value type that is always a fresh instance on the way in and another
fresh instance on the way back out — was indexed under a key nothing could reproduce: the lookup
missed the node, the delete missed the entry and left it dangling for `verify(FULL)` to find, and a
unique constraint on the key never fired. So `float[]` hashes by content, a list hashes by mixing its
elements' `valueHash` rather than by `List::hashCode`, and any other array shape falls through to
`Arrays.deepHashCode` rather than to `Object::hashCode`. That last branch is the one that matters
next time: it is what stops the next array-shaped value type from reintroducing the defect
([IDX-1](INDEX_TASKS.md#3-closed)).

**The verification has to agree with the hash**, which is why it is `IndexCatalog.valueEquals` rather
than `Objects.deepEquals` directly. `deepEquals` is deep for an array and shallow for everything
else, so a list holding a `float[]` compares its elements by identity and discards the very candidate
the tree just found. `valueEquals` is `deepEquals` plus an element-wise walk of a list, and it is the
partner of `valueHash`: hash and equality are one decision made in two places, and a change to either
that is not made to the other turns a found candidate into a silently dropped answer.

**Hashing a vector is exact-equality only.** `nodesByProperty("embedding", v)` finds the node whose
embedding is byte-identical to `v`, and index maintenance on that node is sound. Through Cypher it
holds only when the parameter is itself a `float[]`: a vector *read back* through Cypher is rendered
as a list of doubles, and searching with that list is searching for a different value
([VECTOR.md §1.2](VECTOR.md)). None of this is nearest-neighbour search, and it does not become it by
being tuned — that is a different structure, explored in [VECTOR.md](VECTOR.md).

**Hashing is why there are no range queries.** `n.age > 30` cannot use this index: the tree is ordered
by hash, and a hash is deliberately not order-preserving. A range index would need the value's ordered
bytes in the key, which is a different index and is not built.

---

## 4. The catalog page, and why the roots are not in the manifest

Both root pointers live in **slot 0 of `INDEX` segment 0** — a page that is itself an index page, and
therefore transactional.

```
 0  8  magic
 8  8  label_root
16  8  prop_root
64  4  unique_count
68  N  unique key ids, 4 bytes each, up to 256
```

Putting the roots in the manifest would have been the obvious choice and would have been wrong: the
manifest is written at a **checkpoint**, not at every commit, so a crash between the two would leave
the manifest naming a root from before the last commits and the index quietly inconsistent with the
data. In an index page, a root change is committed or discarded with everything else it belongs to.

**`catalogPageId` is a hint, not an authority.** `ensureInitialised()` allocates the page inside a
transaction, and that transaction can roll back — which frees the page and leaves the field naming a
slot that is no longer allocated. So it is re-derived whenever it does not resolve, turning "the index
was created by a transaction that was discarded" back into "there is no index yet" rather than a
`StaleIdException` on the next lookup. `UsageExampleTest` found this.

**The index is created lazily**, on the first write that needs it. A store that has never been written
to has no index pages at all.

---

## 5. Maintenance

Four hooks on `IndexCatalog`, and every one of them is called from inside the caller's transaction, so
an index update rolls back with the data update it belongs to.

| Hook | Called from | What it does |
|---|---|---|
| `onNodeCreated(id, labelIds, properties)` | `createNode`, `Compactor` | One label entry per label, one property entry per key |
| `onNodeDeleted(id, labelIds, properties)` | `deleteNode`, `Compactor` | Removes every one of them. Missing one leaves a dangling entry |
| `onPropertiesChanged(id, before, after)` | `setNodeProperties` | Removes entries whose value changed, adds the new ones. Only the difference |
| `onLabelsChanged(id, before, after)` | `addNodeLabel`, `removeNodeLabel` | Same, for labels |

`onLabelsChanged` is the newest and was added with Cypher's `SET n:Label`
([CYPHER.md §5](CYPHER.md)). Before it there was no hook for a changed label set at all, because the
engine had no way to change one: a label set could only be given at creation.

**Only the difference is rewritten.** Removing and reinserting every entry would give the same answer,
but every dirtied index page is a byte run in the commit and an entry in every open reader's version
chain — so the cost of relabelling a node would depend on how many labels it already had.

**A duplicate label is dropped before it reaches the index.** `:Person` stored twice would put two
entries on one node, and the delete would remove one and leave the other.

---

## 6. Unique constraints

`createUniqueConstraint(key)` — **on node properties only**. The key ids live in the catalog page
(§4), up to 256 of them.

Declaring one **scans every live node first** and refuses if the data already breaks it, rather than
declaring something that is already false.

Enforcement is `checkUnique`, which probes the index, verifies each candidate against the stored value,
and raises `ConstraintViolationException` naming the key, the value and the node already holding it.

**Two ordering facts worth knowing before relying on it:**

- `createNode` checks **before** allocating. A violation there leaves nothing behind.
- `setNodeProperties` checks **after** writing the new property block. A violation there leaves a
  partially applied write, so the transaction must be rolled back rather than caught and continued —
  which is exactly what the Cypher write path does ([CYPHER.md §5.2](CYPHER.md)).
- `inTransaction` **does not retry** a constraint violation. Repeating it repeats the same failure; it
  is about data that has not changed, not about contention.

**There is no unique constraint on a relationship property**, because there is no relationship property
index for one to be built on.

---

## 7. What a query does with all this

The access paths, most selective first ([CYPHER.md §7](CYPHER.md)):

1. a variable already bound by an earlier clause — verify, do not search;
2. an **indexed property equality** from the pattern's inline map;
3. a **label scan**;
4. a full scan — `forEachNode`, and with a `LIMIT`, `forEachNodeWhile`, which stops.

Two known costs, stated rather than discovered:

- **Only the first label of a pattern drives the scan.** `MATCH (n:Person:Admin)` starts from
  `Person` even when `Admin` is a thousand times rarer, and verifies the rest per candidate. The right
  choice needs per-label counts and **the store does not expose any statistics at all** — there is no
  cardinality estimate anywhere in this engine, and the planner is a fixed order rather than a
  cost model.
- **A property probe wins over a label scan unconditionally**, which is right almost always and wrong
  for a property that is nearly constant.

---

## 8. Relationships are not indexed

Not by type, not by property. `MATCH ()-[r:KNOWS {since: 2019}]->()` is always a scan-and-expand:
every relationship is found by walking a node's adjacency, and `IndexCatalog` has no relationship path
at all.

This is a real gap rather than an oversight to be tidied away. It is survivable because the adjacency
list is itself the index for the common question — *the relationships of this node* — which
[RELATIONSHIP_STORAGE.md](RELATIONSHIP_STORAGE.md) covers, and it is the shape of query the engine is
built for. What has no answer is "every `KNOWS` in the store", which costs a full scan.

**It is not the reason a high-degree node is slow**, which is the natural next assumption and a wrong
one: a supernode query starts from a node id and its cost is entirely in how its adjacency is
enumerated — unskipped blocks and a fully materialised edge list. (Reading the relationship record
*before* the type filter could reject it was the third of those costs, and
[IDX-2](INDEX_TASKS.md#3-closed) has since
removed it: the adjacency entry caches the type.) [INDEX_TASKS.md §1](INDEX_TASKS.md) works that out
with the numbers and routes what remains to two adjacency tasks, neither of which is an index.

---

## 9. Interaction with the rest of the engine

- **Compaction** relocates node ids, so every index entry naming one must be rewritten.
  `Compactor` does not do that entry by entry: it calls `resetTrees()`, which frees every page of both
  trees, and re-inserts every node. Rebuilding is both simpler and denser than fixing up a parent
  pointer, a leaf sibling pointer and possibly a root pointer per page — and it is only affordable
  because **index ids are internal and never handed out**, which is the same fact that lets these pages
  relocate at all.
- **Snapshot isolation** costs the index nothing extra. Index pages are `INDEX`-kind records read
  through `SegmentFile.readSlice` like everything else, so a view reads them as of its own LSN through
  the same page-version machinery ([TRANSACTIONS.md §5](TRANSACTIONS.md)).
- **The dictionary** is where label and key names become ids (`Catalog`). It is shared between node
  labels and relationship types and **is not transactional** — interning writes and flushes
  immediately, so a rolled-back `CREATE (:Typo)` leaves the id allocated forever. Harmless: an id never
  changes meaning, and a label no node carries matches nothing.
- **`/api/schema` does not use the index**, and the reason is that dictionary: it can say `KNOWS`
  exists but not whether it is a label or a relationship type, and it still holds labels whose last node
  was deleted. So the sidebar's counts come from a scan of the data ([CYPHER.md §8.1](CYPHER.md)).

---

## 10. Tests

| | |
|---|---|
| `IndexTest` | The two trees end to end: lookup, update, delete, a rebuild, and the lazy first allocation |
| `UniqueConstraintTest` | Declaration against data that already breaks it, enforcement on create and on update, and durability across a reopen |
| `MultiLabelTest` | An entry per label rather than per node, spill in and out of the overflow block, and `addNodeLabel`/`removeNodeLabel` leaving the index exactly right |
| `CompactionTest` | Ids move and both trees still answer — the case `resetTrees` exists for |
| `ReferenceModelTest` | The differential that matters: every indexed lookup must equal what an in-memory reference model says, over a randomised workload |
| `CypherWriteVersusApiTest` | Write through Cypher, then verify with the API **and** `verify(FULL)`, whose `NoDanglingRefs` checker is what catches an index entry pointing at a freed slot |

The load-bearing one is `ReferenceModelTest`: an index that agrees with itself proves nothing, and an
index that disagrees with a model built independently of it is the only defect class this layer can
have.

---

## 11. Not built

Each of these is a task with a verdict, an effort estimate and a definition of done in
[INDEX_TASKS.md](INDEX_TASKS.md).

- **Range queries.** §3.2 — the key holds a hash, and a hash is not order-preserving.
- **Composite indexes.** `(label, property)` as one key would make `MATCH (n:Person {email: …})` a
  single probe instead of a probe plus a label verification. Nothing needs it yet — but it is also the
  only way to express a *label-scoped* unique constraint
  ([INDEX_TASKS.md IDX-7](INDEX_TASKS.md#idx-7--composite-label-property-indexes)).
- **Relationship indexes.** §8, and [IDX-6](INDEX_TASKS.md#idx-6--a-relationship-type-index) for why
  the verdict is "not yet" rather than "eventually".
- **Statistics.** No per-label counts, no cardinality estimates, no histograms — which is why §7's
  planner is a fixed order rather than a cost model.
- **Full text, spatial, vector.** The store can hold a `float[]` and the property tree answers an
  *exact-equality* lookup on one (§3.2), which is all it will ever answer: nothing does nearest
  neighbour. Vectors are explored in [VECTOR.md](VECTOR.md).
- **Index rebalancing on delete.** §2 — deferred to compaction.

---

_Git HEAD revision: `3a05a2a`_
_Last updated: 2026-08-16_
