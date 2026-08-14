# Current State — What the PoC Actually Does

As-is analysis of the repository at git revision `c016a41`. This file documents the **existing**
implementation and its defects. The target design lives in the other spec files.

Read this before changing anything: several tests are green for reasons unrelated to correctness,
and one test is red before you touch it.

---

## 1. Inventory

| Area | Size | State |
|------|------|-------|
| Main Java | 24 files, ~1180 lines | Working proof of concept |
| Test Java | 20 files, ~1250 lines | Mixed: unit tests, FFM experiments, unbounded benchmarks |
| Rust native lib | 1 file, 19 lines | `add_numbers`, `create_int_pointer`, `free_int_pointer` — an FFM smoke test |
| C native lib | 1 file, 60 lines | Standalone `mmap` demo, not called from Java |
| Build | `pom.xml`, parent `io.metaloom:maven-parent:2.1.0` | Java release 23; toolchain present is Temurin 25 |

There is **no** module split, no benchmark harness, no integration tests, and no CI configuration in
the repository.

### 1.1 What works

- Creating and reading a node by id, within one process and across a reopen.
- Creating a relationship from two in-memory `Node` objects and reading it back, including both
  endpoint nodes and all three property sets.
- Deleting a node or relationship (flag flip only).
- The adjacency chain store in isolation: append four links, delete from the head and the tail, read
  the chain back.
- Property store round trip for a handful of keys within one process.
- The FFM mechanics themselves: `MemoryLayout` struct definitions, `VarHandle` access,
  `FileChannel.map` into a `MemorySegment`, `SymbolLookup` + `Linker` downcalls into the Rust
  library, pointer reinterpretation.

### 1.2 What does not exist

Transactions, durability, crash recovery, concurrency control, compaction, space reclamation,
secondary indexes, typed properties, bidirectional traversal, working traversal of any kind, an id
that survives a round trip through a relationship record, and any form of I/O batching.

---

## 2. On-Disk Layout Today

All four stores share the same 528-byte header (`FileHeader.HEADER_LAYOUT`):

```
offset  size  field
0       4     magic_bytes   ("node" | "rels" | "nore" | "prop")
4       4     version       (1)
8       8     count         (element count)
16      512   padding
        ----
        528   total
```

`528` is not a power of two and not 4 KiB aligned, so the first record already begins mid-page.

### 2.1 Node record — 1080 bytes (`NodeStorageImpl.LAYOUT`)

```
offset  size  field
0       8     random_uuid_part
8       8     node_rel_start_offset
16      1     free
17      7     padding
24      32    label            (UTF-8, NUL padded, max 32 bytes)
56      1024  props            (128 x long property offsets)
        ----
        1080  total
```

### 2.2 Relationship record — 1088 bytes (`RelationshipStorageImpl.LAYOUT`)

```
offset  size  field
0       8     node_a_offset
8       8     rel_uuid_part
16      8     node_b_offset
24      1     free
25      7     padding
32      32    label
64      1024  props            (128 x long property offsets)
        ----
        1088  total
```

### 2.3 Node-relationship link — 32 bytes (`NodeRelationshipStorageImpl.LAYOUT`)

```
offset  size  field
0       8     node_offset
8       8     next             (-1 == end of chain)
16      1     free
17      7     padding
24      8     rel_offset
        ----
        32    total
```

A singly linked list per node, allocated append-only in `node_rels.bin`.

### 2.4 Property record — variable (`PropertyStorageImpl`)

```
8 bytes   id (always written as 1L)
4 bytes   key length
n bytes   key (UTF-8)
pad       to 8-byte alignment
4 bytes   value length
n bytes   value (UTF-8)
pad       to 8-byte alignment
```

Keys and values are stored verbatim per property occurrence. There is no dictionary, no dedup and no
typing — every value is a `String`.

### 2.5 Space cost

A node with a single `name` property costs **1080 bytes** for the node record plus roughly **32
bytes** for the property record. The payload is a 6-byte label and a 15-byte value. Over 98 percent
of the node record is the 1024-byte property-offset array, of which at most 32 entries can be used
(see defect D4).

The target design puts the same node in **64 bytes** plus a shared property block — see
[FILE_FORMAT.md §5](FILE_FORMAT.md#5-node-record).

---

## 3. The Id Scheme Today

`GraphUUID` builds a 128-bit `java.util.UUID` by interleaving a 64-bit **byte offset** with 64
**random** bits: offset bits land on odd positions, random bits on even positions.

```java
GraphUUID uuid = GraphUUID.uuid(offset);   // random part generated fresh
long offset  = uuid.offset();              // extract odd bits
long randomV = uuid.randomValue();         // extract even bits
```

The random half is written into the record (`random_uuid_part` / `rel_uuid_part`) so a read can
reconstruct the full id from the offset alone.

The **idea is sound and is kept** in the target design. The implementation is not:

- 128 bits where 64 suffice.
- Interleave and extract are 64-iteration bit loops, executed on every id creation and every id
  decode. A Morton encode/decode is a handful of shift-mask steps, and the target design does not
  need interleaving at all.
- The offset is a **byte** offset, so it spends bits on positions that are always zero for aligned
  records, and it hard-codes a single file per kind — there is no room for a segment number.
- No `equals`, no `hashCode`. `Set<GraphUUID>` and `Map<GraphUUID, ?>` therefore behave by identity.
  `DataStorageTest.testRelationship` asserts `uuids.size() == 4` and passes only because the four
  instances are distinct objects.
- The random half is a collision-detection device with no defined check: nothing ever compares the
  id's random half against the stored `random_uuid_part`, so a dangling id silently reads whatever
  now occupies the slot.

---

## 4. Defect Register

Evidence-backed list. Each entry was a work item for [PLAN.md](PLAN.md).

**Status after Phases 1 to 3**: D1, D2 (partially — flush on checkpoint and close, but no log yet),
D3, D4, D5, D6, D7, D8, D9, D10, D11, D12, D13, D14, D15, D17, D18, D19, D20, D21 and most of D22
are resolved by the rewrite, which deleted the classes they lived in. **D16 (no concurrency control)
remains open** and is Phase 5. Durability is only as good as the last `checkpoint()` until Phase 4.

| # | Severity | Defect |
|---|----------|--------|
| D1 | Critical | **A `FileChannel` is opened and a `MemorySegment` mapped per operation.** Every `create`, `read`, `delete`, `getNodeRelOffset` and `setNodeRelOffset` call opens the file, maps a record-sized region with `Arena.ofAuto()`, and never unmaps. `GraphStorageTest` documents the consequence in a class comment: `sysctl -w vm.max_map_count=13107290`. This is a syscall and VMA storm, and it is the single biggest reason the PoC is slow. |
| D2 | Critical | **No durability.** Every `fc.force(false)` call is commented out; no `MemorySegment.force()` is ever called; `AbstractMMapFileStorage.close()` has an empty body. The element count in the header is written *before* the record it accounts for. A crash leaves the store in an arbitrary state with no way to detect it. |
| D3 | Critical | **`PropertyStorageImpl` writes over its own header.** `nextFreeOffset` is initialised to `0` and `store()` writes the first record at offset 0, clobbering the 528-byte header (magic, version, count). `nextFreeOffset` is also never persisted, so after reopening the store the next write restarts at offset 0 and overwrites live property data. |
| D4 | Critical | **`MAX_PROP_IDS` is 128 but the write path hard-codes 32.** `AbstractGraphStorage.writePropIds` pads with the `-1` terminator only up to index 32, while `readPropIds` scans up to 128. An element with more than 32 properties writes no terminator, so the reader returns up to 128 entries of adjacent garbage; with 128 or more properties the `VarHandle` throws. |
| D5 | Critical | **`PropertyStorageImpl.store` maps the whole file on every call.** It computes `fileChannel.size() + dataSize` and maps from offset 0 each time, so the mapping grows linearly with the store and the cost of writing *n* properties is quadratic. The `+ 512` slack in `dataSize` also inflates the file by 512 bytes per property. |
| D6 | High | **Ids are not stable across a relationship read.** `RelationshipStorageImpl.read` rebuilds endpoint ids with `GraphUUID.uuid(fromId)`, which generates a *fresh* random half instead of reading the endpoint record's stored `random_uuid_part`. The id you get back from `readRelationship(...).from().uuid()` is not the id you created the node with. `GraphStorageImpl.traverse` does the same for relationship ids. |
| D7 | High | **Traversal is not implemented.** `RelationshipStorageImpl.loadRelationshipIds` returns `null`; `GraphStorageImpl.traverse` immediately dereferences it. The `nodeRelStorage` field injected into `RelationshipStorageImpl` is never read. |
| D8 | High | **`GraphStorageTest.testTraverse` fails.** Confirmed by running the suite: `NullPointerException: Cannot invoke "Node.uuid()" because "nodeA" is null` at `GraphStorageImpl.create(Relationship)`. The test builds relationships with `RelationshipImpl(GraphUUID, String, GraphUUID)`, which leaves `from` and `to` null, but `create(Relationship)` only handles the `Node`-based constructor. |
| D9 | High | **Only one direction is linked.** `GraphStorageImpl.create(Relationship)` appends an adjacency link for `nodeA` only. `nodeB` has no incoming-edge entry, so even a working traversal could never walk backwards. |
| D10 | High | **Off-by-one in adjacency allocation.** `NodeRelationshipStorageImpl.allocateSegment` does `long count = elementCount.getAndIncrement(); header.setCount(fc, count);` — it persists the value *before* the increment. After a reopen the store allocates the last-used offset again and overwrites a live chain link. |
| D11 | High | **Free lists are in-memory only.** `OffsetProvider.freeOffsets` is an `ArrayDeque` populated by `delete()` and never persisted. Every restart loses all reclaimable space; deleted records become permanent holes. `NodeRelationshipStorageImpl` has its own separate, equally volatile, `freeOffsets`. |
| D12 | High | **Delete is a flag flip with no cascade.** `AbstractGraphStorage.delete` sets `free = true`. It does not free the element's property records, does not unlink the element from any adjacency chain, and does not delete the relationships attached to a deleted node. Dangling references are guaranteed. |
| D13 | Medium | **Properties are stored twice on create.** `GraphStorageImpl.create(Node)` calls `data.prop().store(node.props())` twice and keeps the first result; `create(Relationship)` repeats the bug for node A. Every node write leaks a duplicate set of property records. |
| D14 | Medium | **`create(Node)` does not set the id on the passed node.** The `node.setUuid(...)` call is commented out, so the caller's object is left without an id even though one was assigned. |
| D15 | Medium | **Records straddle pages.** With a 528-byte header and 1080/1088-byte strides, records are neither 8 KiB nor 4 KiB aligned; reading one node frequently touches two pages. See [SSD_IO.md](SSD_IO.md). |
| D16 | Medium | **No concurrency control of any kind.** No latches, no locks, no atomics beyond the element counters. Two threads writing the same store corrupt it. |
| D17 | Medium | **`VarHandle`s are resolved per access.** Every accessor calls `LAYOUT.varHandle(MemoryLayout.PathElement.groupElement(...))`, allocating a path-element array and walking the layout on each read and write. |
| D18 | Medium | **Labels are capped at 32 bytes and stored per record.** `writeLabel` rejects longer labels at runtime; `readLabel` decodes a fixed 32-byte array and relies on `String.trim()` to strip NUL padding, which corrupts a label truncated mid-codepoint. Labels are also duplicated in every single record instead of being interned. |
| D19 | Low | **`NodeRelationshipStorageImpl` never checks file capacity.** Unlike `OffsetProvider`, it has no `ensureFileCapacity`; it relies on `MapMode.READ_WRITE` implicitly extending the file. `load(NO_NEXT_SEGMENT)` maps at offset `-1` and throws. |
| D20 | Low | **Debug output via `System.out.println` in the hot path.** `NodeRelationshipStorageImpl.load` and `followChain` print a line per chain hop. |
| D21 | Low | **`LongBinaryTree` is dead code.** An unbalanced, in-memory, off-heap BST that nothing uses and that would degenerate to a linked list on sequential keys. |
| D22 | Low | **Build hygiene.** `pom.xml` targets release 23 on a Java 25 toolchain; `logback.default.xml` sits in `src/main/resources` under a name Logback does not auto-load; the native libraries are not built, tested or shipped by Maven, and `FFMLibraryTest` fails unless you have run `cargo build --release` by hand. |

---

## 5. Test Reality Check

Result of running the non-bulk tests at `c016a41`:

```
Tests run: 24, Failures: 0, Errors: 1, Skipped: 0
GraphStorageTest.testTraverse ... NullPointerException (D8)
```

Two green tests deserve scepticism:

- **`PropertyStorageTest.testBasics`** passes because the first property lands at offset 0 (D3) and
  the test only ever reads back ids it was handed in the same process.
- **`GraphStorageTest.testReadRelationship`** asserts `props().size() == 1` for each element. The
  property-id array read back for a single-property element is `[0]`, and offset 0 happens to hold
  the first property written. Change the write order or the number of properties and the assertion
  stops describing reality.

Two tests are effectively unbounded benchmarks living in the unit-test suite:

- `NodeStorageTest.testBulk` — 1,000,000 create+read round trips.
- `GraphStorageTest.testBulkRelationship` — 200,000 relationships, each with two nodes and three
  property sets, each read back and asserted.
- `PropertyStorageTest.testBulk` — 1,000,000 property writes, quadratic because of D5.

`TESTING.md` specifies where these belong.

---

## 6. What Carries Forward

Not everything here is scaffolding to throw away. These decisions are **kept** in the target design:

1. **Ids encode their own location.** This is the project's thesis and it survives intact — it is
   generalised in [ID_ENCODING.md](ID_ENCODING.md) from "byte offset in one file" to
   "kind + segment + slot + generation".
2. **The random/validation half of the id.** Reworked into a 12-bit generation counter that is
   actually checked, which is what the random half was reaching for.
3. **Fixed-stride records addressed by arithmetic.** Kept, with the strides fixed so that records
   never straddle pages.
4. **`MemoryLayout` + `VarHandle` struct access over `ByteBuffer`.** Kept, with the handles hoisted
   into constants.
5. **Separate files per concern.** Kept and extended into per-kind segment families.
6. **Adjacency stored apart from the relationship record.** Kept, with the linked list replaced by
   inline edges plus compressed blocks.

---

## 7. Progress Assessment

- [x] Inventory of existing classes and layouts
- [x] Defect register with evidence
- [x] Test-suite reality check at `c016a41`
- [ ] D1–D5 (critical) scheduled — see [PLAN.md](PLAN.md) Phase 1 and Phase 2
- [ ] Regression tests written for D3, D4, D6, D8, D10 before the rewrite, so the rewrite is provably
      an improvement rather than a different set of bugs

---

## 8. Key Classes Reference

See [GRAPH_STORAGE_CONTEXT.md §7](GRAPH_STORAGE_CONTEXT.md#7-key-classes-reference).

---

_Git HEAD revision: `c016a41`_
_Last updated: 2026-08-13 (synced with the Phase 0 to 3 implementation)_
