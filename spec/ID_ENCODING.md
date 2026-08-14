# Element Id Encoding

The defining constraint of this storage engine: **an id decodes arithmetically into the exact file
and byte offset of its record**. No index, no probe, no mapping table, no seek beyond the read
itself.

This file is normative for the `ElementId` type. Everything else references it.

---

## 1. Requirements

| # | Requirement |
|---|-------------|
| R1 | Given an id and nothing else, compute the segment file and the byte offset in O(1) integer arithmetic. |
| R2 | Fit in 64 bits, so that ids are a `long`, fit a register, and cost 8 bytes wherever they are stored (adjacency entries, endpoint fields, index keys). |
| R3 | Address multiple files per record kind, so a store is not limited by one file. |
| R4 | Detect a dangling id — one that points at a slot which has since been freed and reused. |
| R5 | Distinguish kinds, so a node id can never be mistaken for a relationship id or a property id. |
| R6 | Have a well-defined NULL that is byte-zero, so a zeroed page reads as empty rather than as a reference to record 0. |
| R7 | Remain valid for the lifetime of the element, including across updates, compaction being the only exception (which relocates and rewrites references). |

The PoC satisfies R1 only, and only for a single file per kind. See
[CURRENT_STATE.md §3](CURRENT_STATE.md#3-the-id-scheme-today).

---

## 2. Bit Layout

```
 63    60 59                    36 35                    12 11        0
+--------+------------------------+------------------------+-----------+
| kind 4 |      segment  24       |        slot  24        |  gen  12  |
+--------+------------------------+------------------------+-----------+
```

| Field | Bits | Range | Meaning |
|-------|------|-------|---------|
| `kind` | 63..60 | 0..15 | Record family. Selects the directory, the stride and the `dataStart`. |
| `segment` | 59..36 | 0..16,777,215 | Segment file number within the kind. File name `<kind>-%06d.seg`. |
| `slot` | 35..12 | 0..16,777,215 | Record index inside the segment's data region. |
| `gen` | 11..0 | 0..4095 | Generation counter for this slot. Incremented every time the slot is reused. |

### 2.1 Kinds

| Value | Name | Stride | Slots/segment | Segment data size | Spec |
|-------|------|--------|---------------|-------------------|------|
| 0 | `NULL` | — | — | — | Reserved. `NULL_ID == 0L`. |
| 1 | `NODE` | 64 B | 2^24 | 1 GiB | [FILE_FORMAT.md §5](FILE_FORMAT.md#5-node-record) |
| 2 | `REL` | 64 B | 2^24 | 1 GiB | [FILE_FORMAT.md §6](FILE_FORMAT.md#6-relationship-record) |
| 3 | `ADJ` | 512 B | 8,388,608 | 4 GiB | [RELATIONSHIP_STORAGE.md](RELATIONSHIP_STORAGE.md) — a block is a run of cells, so an `ADJ` id names a run, not a page |
| 4 | `PROP` | 32 B | 2^24 | 512 MiB | [PROPERTY_STORAGE.md](PROPERTY_STORAGE.md) |
| 5 | `BLOB` | `PAGE_SIZE` | `4 GiB / PAGE_SIZE` | 4 GiB | [PROPERTY_STORAGE.md §6](PROPERTY_STORAGE.md#6-blob-store) |
| 6 | `INDEX` | `PAGE_SIZE` | `4 GiB / PAGE_SIZE` | 4 GiB | Phase 7 |
| 7 | `UNDO` | 64 B | 2^24 | 1 GiB | **Reserved and unused.** Snapshot isolation keeps before-images per page in memory instead, because undo does not need to be durable under no-steal — [TRANSACTIONS.md §5.1](TRANSACTIONS.md#51-what-was-built). The code is held for the record-level design in [§5.2](TRANSACTIONS.md#52-the-record-level-design-retained), which concurrent writers would need |
| 8..15 | reserved | — | — | — | Do not use without bumping the format version |

Slots per segment is `min(2^24, SEGMENT_CAPACITY / stride)` with `SEGMENT_CAPACITY = 4 GiB`. For
kinds with a large stride the slot field simply does not use its top bits; decoders **must** reject
an id whose slot exceeds the kind's capacity.

### 2.2 Why these widths

- **4 bits of kind** gives 15 usable families with room for six future ones. Kind lives in the top
  bits so that an id sorts by kind, which makes debugging dumps readable and lets an index reject a
  wrong-kind key with a single shift.
- **24 bits of segment** times 1 GiB per node segment is 16 PiB of node records — 2^48 nodes. The
  limit is not the interesting one.
- **24 bits of slot** is exactly 1 GiB at a 64-byte stride, which is why the node segment data size
  is 1 GiB. The stride and the slot width are chosen together.
- **12 bits of generation** is the space that was left. It is a *detector*, not a guarantee — see §4.

### 2.3 Java representation

Ids are plain `long` values everywhere. `ElementId` holds only the encode and decode arithmetic, so
the shift constants live in one place and no wrapper object is ever allocated — an id is stored in
adjacency entries, endpoint fields and index keys, and it must cost 8 bytes and no allocation.

```java
public final class ElementId {

    public static final long NULL = 0L;

    public static long encode(Kind kind, int segment, int slot, int gen) { ... }

    public static int  kindCode(long id) { return (int) (id >>> 60); }
    public static int  segment(long id)  { return (int) ((id >>> 36) & SEG_MASK); }
    public static int  slot(long id)     { return (int) ((id >>> 12) & SLOT_MASK); }
    public static int  gen(long id)      { return (int) (id & GEN_MASK); }
    public static boolean isNull(long id) { return id == 0L; }

    /** The segment:slot pair without kind or generation, as carried by a packed adjacency entry. */
    public static long location(long id);
    public static long fromLocation(Kind kind, long location);
    public static long withGen(long id, int gen);
    public static void expect(long id, Kind kind);
}
```

Every field extraction is one shift and one mask. Compare with the PoC's 64-iteration bit-interleave
loop per operation.

**Do not** reintroduce `java.util.UUID`. If an external, opaque, textual id is needed at an API
boundary, render the `long` as 16 lowercase hex characters — it is shorter, it round-trips exactly,
and it does not imply RFC 4122 semantics that this id does not have.

---

## 3. The Address Function

```java
long byteOffset(long id) {
    int kind = (int) (id >>> 60);
    int slot = (int) ((id >>> 12) & SLOT_MASK);
    return DATA_START[kind] + (long) slot * STRIDE[kind];
}

SegmentFile segmentFile(long id) {
    return open[(int) (id >>> 60)][(int) ((id >>> 36) & SEG_MASK)];
}
```

`DATA_START[kind]` and `STRIDE[kind]` are compile-time constants per format version, validated
against the segment superblock at open time. `open[kind][segment]` is a flat array of already-mapped
segments — an array index, not a hash lookup.

Reading a record is therefore:

```java
MemorySegment rec = segmentFile(id).mapped().asSlice(byteOffset(id), STRIDE[kind]);
```

`asSlice` on an already-mapped `MemorySegment` allocates nothing and performs no I/O. The only I/O
is the page fault on first touch, which is one 8 KiB read from the SSD at a 4 KiB-aligned offset.

### 3.1 Page derivation

The page containing a record is also pure arithmetic, which matters for latching, checksums and WAL:

```java
int pageIndex(long id)  { return (int) ((byteOffset(id) - DATA_START[kind]) / PAGE_SIZE); }
int slotInPage(long id) { return slot(id) % (PAGE_SIZE / STRIDE[kind]); }
```

Because every stride divides `PAGE_SIZE` (32, 64 or `PAGE_SIZE` itself), a record never straddles a
page and `pageIndex` is exact. This invariant is load-bearing — see
[FILE_FORMAT.md §2](FILE_FORMAT.md#2-invariants).

---

## 4. Generations and Dangling Ids

Every record carries a 16-bit `gen` field at a **fixed per-kind offset**, declared by
`Kind.genOffset()`, so the slot allocator can maintain it without knowing the record layout:

| Kind | `genOffset` |
|------|-------------|
| NODE | 4 |
| REL | 20 |
| PROP | 6 |
| ADJ | 30 |
| BLOB | 20 |
| INDEX | 30 |
| UNDO | 44 |

The low 12 bits of that field must equal the id's `gen`. On every read:

```java
if (record.gen() != id.gen() || !record.isLive()) throw new StaleIdException(id);
```

Rules:

- A **fresh** slot starts at `gen = 1`. Generation 0 is reserved so that a zeroed page (`gen == 0`)
  can never match a live id (which always has `gen >= 1`).
- **Freeing** a slot increments its generation and writes the record's `LIVE` flag to 0. The
  incremented generation is persisted with the free, under the WAL.
- **Reuse** hands out `encode(kind, seg, slot, gen)` with the already-incremented value.
- The counter **wraps** from 4095 to 1. A wrap makes an id from 4095 reuse cycles ago
  indistinguishable from a current one. Two mitigations, both cheap:
  - The free-slot allocator is **FIFO, not LIFO** — a freed slot goes to the tail of the segment's
    free queue, so slots rotate through the whole segment before any one of them is reused, and
    4095 reuses of one slot implies on the order of 4095 x slots-per-segment intervening allocations.
  - The record also carries a 64-bit `xmin` commit timestamp. A caller holding an id from before the
    store's `oldest_valid_ts` is already outside snapshot retention and gets an error on that basis.

This is strictly stronger than the PoC's 64 random bits, which are generated but never compared
(D6 in [CURRENT_STATE.md](CURRENT_STATE.md#4-defect-register)).

### 4.1 Why not more generation bits

Widening `gen` costs either segment reach or slot reach. 12 bits plus FIFO reuse plus a snapshot
horizon is a better trade than 20 bits with LIFO reuse. If a deployment needs a hard guarantee
rather than a detector, run with **slot retirement**: configure the segment to never reuse a slot,
so a dangling id is always detectable and compaction is the only thing that reclaims the space. This
is a per-store flag in the manifest, not a format change.

---

## 5. Id Stability

| Event | Id stable? | Notes |
|-------|------------|-------|
| Property update | Yes | The property block is rewritten or reallocated; the element record's own slot never moves. |
| Adding or removing edges | Yes | Adjacency blocks change; the node record's slot never moves. |
| Update under MVCC | Yes | The *home* record stays put; older versions live in undo. See §6. |
| Delete | Id becomes stale | Detected by the generation check. |
| Checkpoint, WAL rotation, backup, restore | Yes | None of these relocate records. |
| **Compaction** | **No** | Compaction relocates records to close holes and therefore rewrites every reference. It is an explicit, offline-or-online-but-tracked operation. See [FILE_FORMAT.md §9](FILE_FORMAT.md#9-compaction). |

Compaction is the only id-breaking operation, and it is the price of the design. It is worth it: the
alternative is paying an indirection on every single read forever.

---

## 6. Why Not an Indirection Table

The obvious way to make ids survive relocation is a page table or a slot directory: the id names a
logical slot, and a mapping structure resolves it to a physical location. Classic shadow paging and
most Copy-on-Write B-trees work this way.

**It is rejected for the hot path**, because it costs exactly what this project is built to avoid:

- One extra dependent memory access, and on a cold cache one extra **I/O**, per element read. For a
  traversal that touches a million neighbours that is a million extra probes.
- The mapping is itself state that must be durable, checkpointed, recovered and cached, which is a
  second storage engine bolted to the side of the first.
- It makes the cost of a read data-dependent, which destroys the cost model that makes traversal
  planning tractable.

### 6.1 Reconciling this with Copy-on-Write

The requirement list asks for CoW. CoW and "the id names the physical location" are in direct
tension: CoW's whole mechanism is writing the new version somewhere else.

The resolution, specified in full in
[TRANSACTIONS.md §7](TRANSACTIONS.md#7-where-copy-on-write-actually-applies):

- **The home location is canonical and never moves.** Committed data is written in place at
  `dataStart + slot * stride`, made atomic and crash-safe by the **WAL**, not by relocation.
- **CoW is used where it does not cost a lookup**:
  1. **Segment superblocks and the store manifest** are double-buffered (A/B) and flipped
     atomically — genuine CoW, zero read cost, torn-write immunity for metadata.
  2. **Dirty pages in the page cache** are shadow copies until commit; the copy never becomes the
     addressed location.
  3. **Old row versions** are copied to undo segments, so a reader on an old snapshot follows a
     version chain — a cost paid only by readers who actually need history, never by a reader of
     current data.
  4. **Snapshots and backups** use filesystem-level CoW (`FICLONE` reflink on btrfs/XFS) to clone
     segment files in O(1) — see [NATIVE_FFM.md §2](NATIVE_FFM.md#2-the-native-surface).

This gives the durability and isolation properties CoW is usually reached for, without giving up R1.

---

## 7. Conventions and Gotchas

- **Never construct an id by hand outside `ElementId`.** Shift constants belong in one place.
- **Always check the kind before dereferencing.** A `long` is a `long`; the kind check is what turns
  a bug into an exception. `ElementId.expect(KIND_NODE)` should be in every store's entry point.
- **`NULL_ID == 0` means kind 0 is unusable.** That is deliberate: it makes zero-filled pages, freshly
  `fallocate`d space, and unset fields all read as "no reference".
- **Ids are not dense and not sequential.** Do not use an id as an array index or assume ordering
  reflects creation order — the generation bits are in the low position precisely so that
  consecutive slots produce ids that differ by 4096, not 1.
- **Do not log raw ids as decimal.** `Long.toHexString` with zero padding; the fields are only
  readable in hex.
- **The slot field can exceed a kind's capacity** if an id is corrupt or hand-built. Validate on
  decode, in a single branch, in the debug/assertion path at minimum.

---

## 8. Test Setup

Covered by `ElementIdTest` (pure unit, no I/O):

- Round trip every field at 0, 1, max and max-1 for all four fields, for all defined kinds.
- `encode`/decode is total: for 10^7 random `long` values, decoding and re-encoding the fields
  reproduces the input exactly.
- `NULL` is `0L`, `isNull()` agrees, and no non-null id encodes to 0 (guaranteed by `gen >= 1` and
  `kind >= 1`).
- Address function: for each kind, `byteOffset(encode(kind, s, slot, g))` equals
  `DATA_START[kind] + slot * STRIDE[kind]` and is independent of `segment` and `gen`.
- Page invariant: for each kind, `STRIDE[kind]` divides `PAGE_SIZE` for every supported page size
  (4/8/16 KiB), and `byteOffset % PAGE_SIZE + stride <= PAGE_SIZE`.
- Capacity: an id whose slot exceeds `slotsPerSegment(kind)` is rejected.
- Generation: a JUnit test that creates, frees and recreates a node in the same slot asserts that the
  old id throws `StaleIdException` rather than returning the new record.

Property-based tests belong here; see [TESTING.md §4](TESTING.md#4-property-based-and-invariant-tests).

---

## 9. Progress Assessment

- [x] `ElementId` with encode/decode and kind constants
- [x] `Kind` enum carrying stride spec, directory name and generation offset
- [x] `Geometry` carrying stride, slots-per-segment and `dataStart`, derived from the page size
- [x] Address function and page derivation with cached per-kind constants
- [x] Generation field in the node, relationship, property, adjacency and blob records
- [x] `StaleIdException` and the read-path generation check
- [x] FIFO free-slot allocator (see [FILE_FORMAT.md §8](FILE_FORMAT.md#8-free-space-management))
- [x] `ElementIdTest` per §8, including the 200,000-sample totality property
- [x] PoC format declared unsupported; `GraphUUID` deleted
- [ ] `slot retirement` store flag
- [ ] Generation field in the undo record (Phase 5)

---

## 10. Key Classes Reference (target)

| Class | Package | Purpose |
|-------|---------|---------|
| `ElementId` | `io.metaloom.graph.core.id` | The 64-bit id record: encode, decode, NULL, kind checks |
| `Kind` | `io.metaloom.graph.core.id` | Per-kind constants: stride, slots per segment, data start, directory |
| `StaleIdException` | `io.metaloom.graph.core.id` | Thrown when the generation check fails |
| `IdAllocator` | `io.metaloom.graph.core.segment` | Hands out fresh ids from the free-slot bitmap, FIFO |

---

_Git HEAD revision: `364b5f7`_
_Last updated: 2026-08-14_
