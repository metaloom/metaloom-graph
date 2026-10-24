# Property and Attribute Storage

How attributes are stored so that reading all properties of an element costs one read, and so that
the same key string is not written a million times.

The PoC stores every property as an independent `(id, keyLen, key, valueLen, value)` record with the
key and value as raw UTF-8, and gives every element a 1024-byte array of property offsets. That is
three separate problems: no interning, no typing, and a per-element pointer array that dwarfs the
data ([CURRENT_STATE.md §2.5](CURRENT_STATE.md#25-space-cost)).

---

## 1. Model

| Concept | Storage |
|---------|---------|
| Property **key** (`"name"`, `"createdAt"`) | Interned in the key dictionary, referenced as a `u16`/`u24` `key_id` |
| Element **label** / relationship **type** | Interned in the label dictionary, referenced as `label_id` / `type_id` |
| Property **value** | Typed; small values inline, large values in the blob store |
| An element's **whole property set** | One contiguous **property block**, referenced by a single `prop_block_id` |

The unit of read and write is the **property block**, not the individual property. Elements
overwhelmingly want all their properties at once, and rewriting a small block is cheaper than
maintaining per-property records.

---

## 2. Property Block

A property block is a variable-length structure stored in `PROP` segments, allocated in units of the
32-byte **property cell**. A block of `n` cells occupies `n` consecutive cells; its id addresses the
first cell.

```
offset size field
0      2    cell_count          total cells occupied
2      2    entry_count
4      2    used_bytes          payload bytes following the header
6      2    gen                 low 12 bits must match the id
8      8    owner_id            back-pointer to the node/rel (compaction and repair)
16     N    entries             sorted ascending by key_id
```

Liveness is tracked by the free-slot bitmap rather than a flag in the block, so the header is 16
bytes with no flags field; flags will come from the spare bits of `cell_count` if ever needed.

Each entry:

```
 0   3 bytes   key_id            (16.7M distinct keys)
 3   1 byte    type_tag          see §4
 4   n bytes   value payload     length implied by type_tag, or a leading varint
```

Entries are **sorted by `key_id`**, so `get(key)` is a binary search inside a block that is already
in cache — no hashing, no probing, no allocation.

### 2.1 Sizing

A node with `name = "Wes Anderson"` and `age = 42`:

```
header                16 B
name  entry: 3 + 1 + 1 + 12 = 17 B   (SHORT_STRING, 1-byte length)
age   entry: 3 + 1 + 1      =  5 B   (INT_1, value fits a byte)
                              ----
                              38 B  ->  2 cells = 64 B
```

Against the PoC: 1080 bytes of node record plus two property records of ~32 bytes each = ~1144
bytes, versus 64 (node) + 64 (property block) = **128 bytes**.

### 2.2 Update

Property blocks are rewritten wholesale:

1. Decode the current block (or start empty).
2. Apply the change in memory.
3. If the new size fits the same cell count, write in place under the page latch.
4. Otherwise allocate a new block, write it, point the element's `prop_block_id` at it, free the old
   block. **The old block does not need to be retained for open snapshots**: an older reader resolves
   the `PROP` page as of its own LSN, which still holds the old block's bytes and the bitmap bit that
   says its cells are allocated. That falls out of versioning the page rather than the record — see
   [TRANSACTIONS.md §5.1](TRANSACTIONS.md#51-what-was-built).

Rewriting looks wasteful until you count: a typical block is 1 to 4 cells, so a rewrite is 32 to 128
bytes, which is less than the cost of the metadata that per-property mutability would require.

---

## 3. Dictionaries

Two dictionaries. Each is an append-only file of `[u32 big-endian length][UTF-8 bytes]` records under
`dict/`, loaded whole into a concurrent forward map and a dense reverse array at open (`Dictionary`);
the `INDEX`-kind B+tree once planned for them was never needed and is not built:

| Dictionary | Maps | Id width | Cardinality |
|------------|------|----------|-------------|
| `keys.dict` | property key string <-> `key_id` | 24 bit | 16.7M |
| `labels.dict` | label / relationship type string <-> `label_id` | 32 bit for labels, 14 bit for edge types in packed edges | see below |

Relationship types are constrained to **14 bits (16,384)** by the packed-edge layout
([RELATIONSHIP_STORAGE.md §3](RELATIONSHIP_STORAGE.md#3-the-packed-edge-8-bytes)). Types beyond
that are legal but force every edge of that type to be materialised: `createEdge` escalates to a
relationship record whose adjacency entry caches no type, and `deleteEdge` finds it by endpoints and
type. Labels and types share one id space, so a store that has seen 16,383 distinct names of either
kind is past the limit. Node labels use the full 32-bit `label_id` space since they live in the node
record.

Interning:

- Forward lookup (string -> id) is a `ConcurrentHashMap` probe; reverse lookup (id -> string) is a
  dense array read off a volatile reference, since ids are assigned sequentially. Neither takes a lock.
- Dictionary entries are **never deleted**. A dictionary id is stable for the life of the store,
  which is what makes it safe to embed one in a million records.
- Dictionary writes are **not** WAL-logged. An intern appends at once and is visible to every thread
  immediately, rolled back or not; the file is fsynced at commit *before* the WAL commit record, so
  a committed record never names an id that is not durable.
- **A torn tail is cut off at open.** A crash mid-append leaves a partial length or a partial body
  after the last complete record. It was never forced, so nothing committed names it; `load` stops
  there and the file is truncated to the last complete record before the next append — otherwise the
  next entry would land behind the garbage and be unreadable after the following reopen
  (`DictionaryTest`).
- **An entry is at most 1 MiB of UTF-8**, enforced by `intern` as well as by `load`. Accepting a
  longer one would write a store that refuses to open.
- **Strings are encoded strictly.** A lone UTF-16 surrogate has no UTF-8 form and the JDK's
  `getBytes` silently writes `?` for it, so `"k\uD800"` and `"k\uDBFF"` would be two ids in memory
  and one string after a reopen. `intern` refuses it with `IllegalArgumentException`, as does the
  value codec for a `STRING` property.

Space effect: a store with 10M nodes each carrying `name` and `createdAt` stores the strings
`"name"` and `"createdAt"` **once**, not 10 million times. In the PoC that is 130 MB of repeated key
text.

---

## 4. Value Encoding

`type_tag` is one byte: the low 5 bits are the type, the top 3 bits are a per-type size class.

| Type | Code | Size class | Payload | Status |
|------|------|-----------|---------|--------|
| `NULL` | 0 | — | 0 B | done — an explicitly-set null, distinct from an absent key |
| `BOOL` | 1 | 0 false, 1 true | 0 B | done — the value is the tag, so a boolean costs one byte in total |
| `INT` | 2 | 0..3 | 1, 2, 4 or 8 B | done — zig-zag, **compared unsigned**; most integers cost 1 B |
| `FLOAT` | 3 | 0 f32, 1 f64 | 4 or 8 B | done |
| `STRING` | 4 | 0 inline, 1 blob | 1 B length + UTF-8, or 4 B length + 8 B `BLOB` id | done |
| `BYTES` | 5 | 0 inline, 1 blob | same | done |
| `TIMESTAMP` | 6 | — | 8 B epoch micros, UTC | done — about ±292,000 years around 1970; an `Instant` outside that is refused, not wrapped |
| `UUID` | 7 | — | 16 B | done |
| `ELEMENT_REF` | 8 | — | 8 B `ElementId` | done — a property pointing at another element |
| `VECTOR_F32` | 9 | — | 4 B dim + 8 B `BLOB` id | done — a vector the store has no fixed record for |
| `VECTOR_REF` | 11 | — | 4 B dim + 8 B `VEC` id | done — a vector of the store's own declared dimension |
| `LIST` | 10 | — | varint count + encoded elements | done, recursive |
| `MAP` | 11 | — | varint count + (key_id, value) pairs | not implemented |
| `DURATION` | 12 | — | 8 B | not implemented |

**The zig-zag comparison must be unsigned.** `Long.MAX_VALUE` zig-zag encodes to `0xFFFFFFFFFFFFFFFE`,
which is negative as a signed `long`; a signed `<=` test classifies it as a one-byte value and
truncates it to 127. Use `Long.compareUnsigned`.

Values above 255 bytes escape to the blob store automatically.

Two decisions worth calling out:

- **Typed values, not strings.** The PoC's `Map<String,String>` forces every integer, timestamp and
  boolean through decimal text: `42` costs 2 bytes of text plus a length plus a record header, and
  every read allocates a `String` and reparses. Typed storage is smaller, faster and lossless.
**A vector has two homes, and the write path chooses.** A `float[]` whose length is exactly the store's
declared `vectorDimensions` is written as `VECTOR_REF` into a fixed-size `VEC` record; anything else —
another length, or a store created without vector storage — escapes to a blob as `VECTOR_F32`. The two
tags carry identical payloads on purpose, so every sweep that walks an id inside a payload treats them
the same: compaction remaps both through one case, and `PropertyStore.free` releases both by
dispatching on the id's kind.

Which matters because a blob extent is page sized. A 768-dimension vector costs 8 KiB as a blob and
4 KiB as a record, and the record holds its ANN links in the same read — see [VECTOR.md](VECTOR.md).
**One copy, never both**: keeping the blob as well would mean `onPropertiesChanged` had to update two
of them, and a partial update is a silent wrong answer.

- **`VECTOR_F32` is in the list from the start.** MetaLoom stores embeddings, and retrofitting a
  vector type into a format that only knows strings is exactly the migration this spec exists to
  avoid.

### 4.1 String deduplication

Optional, per-store flag. When enabled, `SHORT_STRING` values above a threshold (default 32 bytes)
are hashed (xxh3, native) and looked up in a **string intern table** in the `INDEX` kind; a hit
stores an 8-byte `STRING_REF` instead of the bytes, with a reference count.

Worth it for stores with heavy value repetition (categories, tags, enum-like fields), a net loss for
stores with unique values. Off by default; measurable with the benchmark harness.

---

## 5. Property Block Allocation

Property cells are allocated in **runs** from the `PROP` segment's free-slot bitmap. Because blocks
are variable length, the bitmap allocator needs a run-finding path:

- Sizes 1..8 cells: per-size free lists (a segregated free list held in memory, rebuilt from the FSB
  at open). This covers well over 99 percent of blocks.
- Sizes 9..2048 cells: first-fit scan over the FSB using `Long.numberOfTrailingZeros` on 64-cell
  words, from a rotating hint.
- A block never straddles a page: an allocation that would cross a page boundary starts at the next
  page instead, and the skipped cells go back on the free list. This preserves invariant I1 and keeps
  every property read to a single page.

Fragmentation is bounded by the size classes and reclaimed by segment compaction
([FILE_FORMAT.md §9](FILE_FORMAT.md#9-compaction)).

---

## 6. Blob Store

`BLOB` segments hold page-sized extents for anything that does not belong inline: long strings,
binary payloads, embedding vectors.

```
Blob header (first extent):
offset size field
0      8    total_length
8      8    next_extent_id     NULL_ID if this is the last
16     4    crc32c             over the whole blob
20     2    gen
22     1    flags              LIVE, COMPRESSED
23     1    compression        0 none, 1 lz4, 2 zstd
24     8    refcount           for deduplicated strings
32     N    payload
```

Extents chain for blobs above `PAGE_SIZE - 32`. A chain is read with a single vectored/queued I/O
batch rather than one read per extent, because the whole chain's ids are discovered as the first
extent is read; for large blobs the extents are allocated **consecutively** whenever possible, so the
read degenerates into one large sequential read.

Compression (lz4 by default above 4 KiB) is applied per blob, in native code.

---

## 7. Conventions and Gotchas

- **Never store a key as a string in a record.** If you find yourself writing UTF-8 into a node or
  relationship record, you have missed the dictionary.
- **`key_id` is 3 bytes, not 4.** Read it as `u16 | (u8 << 16)` or with an unaligned 4-byte read
  masked to 24 bits; do not accidentally define a 4-byte field.
- **An absent key and a `NULL` value are different.** `get` returns an "absent" marker for the first
  and a null value for the second. The API distinguishes them; the PoC cannot.
- **Property blocks are sorted by `key_id`.** Any writer that appends without re-sorting breaks
  binary search silently. The block encoder sorts; do not bypass it.
- **`owner_id` is not optional.** Without the back-pointer, compaction and repair cannot tell which
  element a block belongs to, and an orphaned block is undetectable.
- **Dictionary ids are never reused.** Reusing one silently rewrites the meaning of every record that
  references it.
- **Rewriting a block is the update path.** Do not add in-place mutation of a single entry; it
  reintroduces variable-length in-place edits, which is the thing that makes formats fragile.

---

## 8. Test Setup

- `PropertyBlockTest`: encode/decode round trip for every type tag, at minimum, maximum and boundary
  sizes; assert sortedness and binary-search correctness.
- `PropertyBlockFuzzTest`: property-based — a random `Map<key, typedValue>` encodes and decodes to an
  equal map; block size is within the predicted bound.
- `DictionaryTest`: intern 10^6 distinct keys, assert forward and reverse agree, assert ids are
  stable across a store reopen, assert a second intern of the same string returns the same id.
- `PageStraddleTest`: allocate blocks of every size class until a segment is full; assert no block
  crosses a page boundary (the I1 regression).
- `BlobChainTest`: store a 100 MB blob, assert the extent chain reads back byte-identical and that
  the CRC is validated; corrupt one extent and assert the read fails loudly.
- `PropertyUpdateTest`: grow a block through every size class and shrink it back, asserting no leaked
  cells (compare the FSB before and after).
- `DedupTest`: with dedup enabled, store the same 1 KB string 10^5 times; assert the blob store holds
  one copy with refcount 10^5, and that deleting all but one leaves the blob live.

---

## 9. Progress Assessment

- [x] `Catalog` with key and label dictionaries and an on-heap cache (append-only file; the B+tree
      backing arrives in Phase 7)
- [x] `PropertyStore` block encoder/decoder with entries sorted by key id
- [x] Single-property read that stops at the first key past the target
- [x] Value type tags per §4, all except `MAP` and `DURATION`
- [x] Segregated free lists for cell runs, page-straddle avoidance
- [x] `BlobStore` with extent chaining and a whole-blob CRC
- [x] `VECTOR_F32` support, and `VECTOR_REF` for a store with fixed-size vector records
- [x] Cascade: freeing an element frees its property block and every blob it references (the D12
      regression)
- [x] Tests: every value type, blob escape, type mismatch, grow-and-shrink without leaking cells
- [ ] `MAP` and `DURATION` types
- [ ] lz4 compression for blobs (Phase 6, native)
- [ ] Optional string deduplication with refcounts
- [ ] WAL-logged interning (Phase 4)

---

## 10. Key Classes Reference (target)

| Class | Package | Purpose |
|-------|---------|---------|
| `PropertyStore` | `io.metaloom.graph.core.store.prop` | Read, write, free property blocks |
| `PropertyBlock` | `io.metaloom.graph.core.store.prop` | Encode/decode, binary search by `key_id` |
| `ValueCodec` | `io.metaloom.graph.core.store.prop` | Type tags and payload encoding |
| `BlobStore` | `io.metaloom.graph.core.store.blob` | Extent allocation, chaining, compression |
| `Catalog` | `io.metaloom.graph.core.catalog` | Key and label interning |
| `Dictionary` | `io.metaloom.graph.core.catalog` | One string <-> id mapping, persistent |

---

_Git HEAD revision: `8f178b4`_
_Last updated: 2026-08-14_
