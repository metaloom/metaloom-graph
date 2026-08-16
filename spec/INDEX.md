# INDEX.md — the secondary indexes

Three B+trees: **label → node**, **(property key, value) → node**, and a **declared
(label, key, value) → node**. They exist so that `MATCH (n:Person {email: $e})` is a probe rather than
a scan, and they are the only structures in this engine whose pages may be relocated freely.

> **Do not put here**: the addressing invariant ([ID_ENCODING.md](ID_ENCODING.md)), the record layouts
> ([FILE_FORMAT.md](FILE_FORMAT.md)), how a property value is encoded
> ([PROPERTY_STORAGE.md](PROPERTY_STORAGE.md)), adjacency and traversal
> ([RELATIONSHIP_STORAGE.md](RELATIONSHIP_STORAGE.md)), or which access path a query chooses
> ([CYPHER.md §7](CYPHER.md)).

---

## 0. Status

| | |
|---|---|
| **What is indexed** | Node labels, node property values, declared `(label, key)` pairs, node unique constraints — store-wide or label-scoped |
| **What is not** | **Relationships — not by type, not by property.** Values are indexed by *hash*, so no ranges |
| **Structure** | One B+tree per index, over `INDEX`-kind pages. `IndexCatalog` + `BTree` |
| **Transactionality** | An index write is part of the same commit as the data write, through the same page overlay |
| **Maintenance** | Four hooks on `IndexCatalog`, called from `GraphStoreImpl`. The first two indexes are implicit; the composite one is maintained only for *declared* pairs |
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

## 3. The three indexes

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

### 3.3 (label, property key, value) → node — declared

```
k1 = mix64(labelId, keyId, valueHash(value))     k2 = nodeId    value = nodeId
```

`MATCH (n:Person {email: …})` is a property probe plus a label verification, and one probe would do.
This tree does it in one. Two things make it worth more than that sentence:

- **A label-scoped unique constraint becomes expressible.** `createUniqueConstraint(key)` is global
  over every node in the store — "no two nodes share an email". What an application means is "no two
  `:Person` share an email", and until this tree existed there was nothing to build it on (§6).
- **It is the honest fix for a low-selectivity property.** §7 records that a property probe beats a
  label scan *unconditionally*, which is right almost always and wrong for a property that is nearly
  constant. `{active: true}` over ten million nodes hands back five million candidates, each of which
  is then read and verified against its label. A composite probe returns the labelled subset.

**All three fields are hashed together, not packed into sub-widths.** A label id and a key id are
`int`s handed out by the dictionary, and a width chosen for them here would become a format
constraint for the life of the store: the moment one overflowed, every existing entry would be keyed
under a scheme nothing could reproduce. A hash has no width to run out of, and it is what §3.2
already does with its value. The mixing is the SplitMix64 finaliser, applied twice so the pair and
the value both spread across the whole key.

**Which makes the verification load-bearing twice over.** A composite lookup verifies the label *and*
the value against the stored record, and the value comparison must be `IndexCatalog.valueEquals` —
the partner of `valueHash`, per §3.2 — rather than `Objects.deepEquals`. Reaching for `deepEquals`
here would find the entry and then discard the node it names, which is the same silent-miss failure
IDX-1 cost.

**It is declared, not implicit.** The other two trees index everything automatically; this one indexes
only pairs someone asked for. There are `labels × keys` possible pairs, and maintaining them all would
multiply the write cost of every node by the size of its label set. So:

- the declared pairs live in the catalog page (§4), up to 64 of them, each with a unique flag;
- `createIndex(label, key)` **scans every live node and backfills**, in the declaring transaction, the
  way `addUniqueConstraint` scans before declaring. An index covering only writes made after it would
  answer confidently with a subset, which is worse than no index at all;
- the four maintenance hooks (§5) consult the declared list, so a store that has declared none pays a
  catalog-page read per hook call and no tree work at all;
- the tree's root is `NULL` until the first declaration, so a store with no composite index does not
  own a page for one.

**The answer does not depend on the declaration; only the cost does.**
`GraphStore.nodesByLabelAndProperty` answers with the composite probe when the pair is declared and
with a property probe plus a label filter when it is not. That is what makes the declaration safe to
add or omit — and it is also why a test asserting the *answer* proves nothing about the index, which
§10 comes back to.

**`dropIndex(label, key)` removes a declaration and its entries**, which is the backfill run backwards:
a scan of the labelled nodes, removing one entry each. Dropping the *last* declaration skips that
entirely — no other pair is left in the tree, so every page is freed and the root goes back to `NULL`,
which is where a store with no composite index starts. Refused, rather than quietly successful, when
the pair was never declared: "there was nothing to drop" is a different outcome from "dropped", and a
typo in a label should not read as success.

**The declaration list compacts, and the vector list does not.** Dropping shifts the entries after it
down, so declarations keep the order they were made in and a UI can list them stably. The vector list
keeps its holes instead, because there an ordinal *is* an identity — every `VEC` record names one, so
moving a declaration would silently reassign its members. Nothing names a composite index by position.

---

## 4. The catalog page, and why the roots are not in the manifest

Every root pointer lives in **slot 0 of `INDEX` segment 0** — a page that is itself an index page, and
therefore transactional.

```
   0  8  magic
   8  8  label_root
  16  8  prop_root
  64  4  unique_count
  68  N  unique key ids, 4 bytes each, up to 256          → ends at 1092
1152  4  vector_count
1156  N  vector index entries, 64 bytes each, up to 16    → ends at 2180
2304  8  composite_root                                   NULL until the first declaration
2312  4  composite_count
2320  N  composite entries, 16 bytes each, up to 64:      → ends at 3344
         0 4 label_id | 4 4 key_id | 8 1 flags (bit 0 = unique)
```

**Each list starts past the last one rather than against it**, and the whole region ends inside 4 KiB,
which is the smallest page this engine allows. A field that moves is a format change, so every list is
given room to grow — and none of them goes anywhere near the gap at 32..63, for the reason §2.1 gives.

**A zeroed region means "nothing declared"**, which is exactly what an older store's catalog page
holds and exactly the right answer for one. That is the same convention the vector list uses and the
same one `NULL_ID == 0` establishes everywhere else.

Putting the roots in the manifest would have been the obvious choice and would have been wrong: the
manifest is written at a **checkpoint**, not at every commit, so a crash between the two would leave
the manifest naming a root from before the last commits and the index quietly inconsistent with the
data. In an index page, a root change is committed or discarded with everything else it belongs to.

**`catalogPageId` is a hint, not an authority.** `ensureInitialised()` allocates the page inside a
transaction, and that transaction can roll back — which frees the page and leaves the field naming a
slot that is no longer allocated. So it is re-derived whenever it does not resolve, turning "the index
was created by a transaction that was discarded" back into "there is no index yet" rather than a
`StaleIdException` on the next lookup. `UsageExampleTest` found this.

**Every list on this page inherits that**, and none of them may add a parallel mechanism for it. A
declaration made by a transaction that rolls back has to read back as "not declared" rather than as
corruption, and it does, because `composites()` and `uniqueKeys()` both go through the same
`initialised()` re-derivation before they read a byte.

**The index is created lazily**, on the first write that needs it. A store that has never been written
to has no index pages at all.

---

## 5. Maintenance

Four hooks on `IndexCatalog`, and every one of them is called from inside the caller's transaction, so
an index update rolls back with the data update it belongs to.

| Hook | Called from | What it does |
|---|---|---|
| `onNodeCreated(id, labelIds, properties)` | `createNode`, `Compactor` | One label entry per label, one property entry per key, one composite entry per declared pair the node satisfies |
| `onNodeDeleted(id, labelIds, properties)` | `deleteNode`, `Compactor` | Removes every one of them. Missing one leaves a dangling entry |
| `onPropertiesChanged(id, labelIds, before, after)` | `setNodeProperties` | Removes entries whose value changed, adds the new ones. Only the difference |
| `onLabelsChanged(id, before, after, properties)` | `addNodeLabel`, `removeNodeLabel` | Same, for labels — plus a composite entry per declared key the node already carries |

`onLabelsChanged` is the newest and was added with Cypher's `SET n:Label`
([CYPHER.md §5](CYPHER.md)). Before it there was no hook for a changed label set at all, because the
engine had no way to change one: a label set could only be given at creation.

**The composite index is what made the last two hooks take a fourth argument**, and the reason is
asymmetric. A property change has to know the node's labels to tell which declared pairs it belongs
to. A *label* change has to know the node's properties — because adding `:Person` to a node that
already holds an email creates a `(Person, email)` entry although no value changed. That is the one
case where relabelling does real index work rather than moving a label entry, and it is the case
`onLabelsChanged` had never had to answer before.

**The properties come in as a supplier**, evaluated only when something is declared. Nothing needs
them otherwise, and a relabelling in a store with no composite index must not start reading property
blocks to discover that.

**Only the difference is rewritten.** Removing and reinserting every entry would give the same answer,
but every dirtied index page is a byte run in the commit and an entry in every open reader's version
chain — so the cost of relabelling a node would depend on how many labels it already had.

**A duplicate label is dropped before it reaches the index.** `:Person` stored twice would put two
entries on one node, and the delete would remove one and leave the other.

---

## 6. Unique constraints

Two forms, **on node properties only**:

| | Declared by | Scope | Where it lives |
|---|---|---|---|
| Store-wide | `createUniqueConstraint(key)` | every node in the store | the key id list in the catalog page (§4), up to 256 |
| Label-scoped | `createUniqueConstraint(label, key)` | the nodes carrying that label | a declared composite index (§3.3) with its unique flag set |

Declaring either **scans the live data first** and refuses if it already breaks the constraint, rather
than declaring something that is already false. The label-scoped scan covers only the nodes carrying
the label, which is the whole difference between the two.

Enforcement is `checkUnique`, which probes the index, verifies each candidate against the stored value,
and raises `ConstraintViolationException` naming the key, the value and the node already holding it —
and the label as well, when there is one. The label-scoped half probes the composite tree, so its
candidates already carry the label; it still verifies the label per candidate, because the key is a
hash of the triple and a collision must not refuse a legitimate write.

**A label-scoped constraint is also an index**, necessarily: it is a declared pair plus a flag, so
declaring it makes `hasIndex(label, key)` true and gives the planner the probe. Declaring uniqueness
over a pair that is already indexed upgrades the declaration in place rather than adding a second one
— the entries a unique check probes are the entries that are already there.

**Which is why `dropIndex` drops the constraint too**, and says so. They are one declaration and not
an index with a constraint on top, so the alternative to dropping both is a constraint that can never
be removed — worse than one that states plainly what removing it does. The browser UI spells it out in
the confirmation ([CYPHER.md §8.2](CYPHER.md)); a store-wide constraint still cannot be removed at
all.

**Three ordering facts worth knowing before relying on it:**

- `createNode` checks **before** allocating. A violation there leaves nothing behind.
- `addNodeLabel` checks **before** writing the label, and only a label-scoped constraint can be broken
  this way: two nodes may legitimately share an email until the second is told it is a `:Person`.
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
2. a **declared composite index** over one of the pattern's labels and one of its literal-valued
   inline properties;
3. an **indexed property equality** from the pattern's inline map;
4. a **label scan**;
5. a full scan — `forEachNode`, and with a `LIMIT`, `forEachNodeWhile`, which stops.

Step 2 is only ever taken when the pair is declared, and asking costs a catalog read rather than a
scan — an undeclared pair falls through to exactly the property probe at step 3, which is what the
planner did before composite indexes existed.

Two known costs, stated rather than discovered:

- **Only the first label of a pattern drives the scan.** `MATCH (n:Person:Admin)` starts from
  `Person` even when `Admin` is a thousand times rarer, and verifies the rest per candidate. The right
  choice needs per-label counts and **the store does not expose any statistics at all** — there is no
  cardinality estimate anywhere in this engine, and the planner is a fixed order rather than a
  cost model.
- **A property probe wins over a label scan unconditionally**, which is right almost always and wrong
  for a property that is nearly constant. §3.3 is the fix that is available today, and it is a
  *declaration* rather than a decision the planner makes: without statistics the engine cannot tell a
  constant property from a selective one, so it is told.

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
  `Compactor` does not do that entry by entry: it calls `resetTrees()`, which frees every page of every
  tree, and re-inserts every node. The composite *declarations* survive that, because they live on the
  catalog page rather than in the tree — so the same `onNodeCreated` that refills the other two refills
  them. Rebuilding is both simpler and denser than fixing up a parent
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
| `IndexTest` | The two implicit trees end to end: lookup, update, delete, a rebuild, and the lazy first allocation |
| `CompositeIndexTest` | The declared tree: the backfill, all four hooks, dropping one of several and the last one, a rolled-back declaration and a rolled-back drop, a reopen, a compaction — and the **candidate counts**, because every assertion about the answer would pass with the index ignored |
| `IndexRoutesTest` | The HTTP surface (`/api/indexes`), including that a declaration made over HTTP is the one the engine enforces on the next write, and that a path segment carrying a space or a slash addresses the index it names |
| `CompositeIndexPlanTest` | Which access path the Cypher planner takes, recorded through a proxy around `GraphStore`. The same reason: the answer does not distinguish the plans |
| `UniqueConstraintTest` | Both forms: declaration against data that already breaks it, enforcement on create, on update and on a label change, and durability across a reopen |
| `MultiLabelTest` | An entry per label rather than per node, spill in and out of the overflow block, and `addNodeLabel`/`removeNodeLabel` leaving the index exactly right |
| `CompactionTest` | Ids move and both trees still answer — the case `resetTrees` exists for |
| `ReferenceModelTest` | The differential that matters: every indexed lookup must equal what an in-memory reference model says, over a randomised workload. Two composite indexes are declared **partway through** it, so the backfill and the hooks are both on trial — and the undeclared pairs check that the fallback path gives the same answer |
| `CypherWriteVersusApiTest` | Write through Cypher, then verify with the API **and** `verify(FULL)`, whose `NoDanglingRefs` checker is what catches an index entry pointing at a freed slot |

The load-bearing one is `ReferenceModelTest`: an index that agrees with itself proves nothing, and an
index that disagrees with a model built independently of it is the only defect class this layer can
have.

---

## 11. Not built

Each of these is a task with a verdict, an effort estimate and a definition of done in
[INDEX_TASKS.md](INDEX_TASKS.md).

- **Range queries.** §3.2 — the key holds a hash, and a hash is not order-preserving.
- **Removing a store-wide unique constraint.** §6 — the key-id list only grows. A label-scoped one is
  removable, because it is a composite declaration and `dropIndex` takes it.
- **Composite indexes over more than one property**, or over a relationship. §3.3 keys one label and
  one key; `(label, k1, k2)` would be the same tree with a different mix, and nothing has asked.
- **Cypher `CREATE INDEX`.** A composite index is declared through the Java API only. The planner
  *uses* one ([CYPHER.md §7](CYPHER.md)), but the query language has no DDL for one.
- **Relationship indexes.** §8, and [IDX-6](INDEX_TASKS.md#idx-6--a-relationship-type-index) for why
  the verdict is "not yet" rather than "eventually".
- **Statistics.** No per-label counts, no cardinality estimates, no histograms — which is why §7's
  planner is a fixed order rather than a cost model, and why §3.3 has to be declared rather than
  chosen ([IDX-8](INDEX_TASKS.md#idx-8--statistics-enough-to-choose-a-label)).
- **Full text, spatial, vector.** The store can hold a `float[]` and the property tree answers an
  *exact-equality* lookup on one (§3.2), which is all it will ever answer: nothing does nearest
  neighbour. Vectors are explored in [VECTOR.md](VECTOR.md).
- **Index rebalancing on delete.** §2 — deferred to compaction.

---

_Git HEAD revision: `0970e6b`_
_Last updated: 2026-08-16 (IDX-7: the declared composite index, its drop, and the label-scoped unique constraint)_
