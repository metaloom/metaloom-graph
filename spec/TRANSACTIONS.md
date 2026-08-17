# Transactions, ACID and Copy-on-Write

Isolation, visibility, conflicts and version lifetime. The log mechanics are in [WAL.md](WAL.md);
this file is about what a transaction *means*.

---

## 1. Guarantees

> **Implementation status.** Atomicity, durability, rollback, **snapshot isolation, the reclamation horizon and
> vacuum** are implemented. Writers are still serialised — one at a time — and **concurrent writers are not built**.
>
> The mechanism differs from what the rest of this file describes, and section 5 explains why: visibility is resolved
> per **page** from in-memory before-images, not per record from an on-disk `UNDO` segment. `xmin`, `version_ptr` and
> the undo record layout are therefore **not used**; the fields stay in the record layouts, written as zero, and the
> format is unchanged. Sections 2, 3, 4.1, 5.1 and 6 describe what was built; sections 4 and 5's record-level
> machinery is retained as the design that would be needed if the writer ever stopped being singular.

| Property | Mechanism | Notes |
|----------|-----------|-------|
| **Atomicity** | No-steal redo WAL; private page copies discarded on rollback | A transaction is all-or-nothing across all seven record kinds, the dictionaries and the indexes |
| **Consistency** | Invariant checkers plus cascade rules | The engine enforces structural invariants (bidirectional edges, degree counters, no orphan property blocks); application-level constraints are out of scope |
| **Isolation** | Serialised writers; MVCC snapshot isolation for readers | See §3 |
| **Durability** | WAL group commit with `fdatasync` | See [WAL.md §4](WAL.md#4-group-commit) |

A read on its own sees the latest commit. `store.beginRead()` opens a **snapshot** view that fixes what
every read on that thread sees until it is closed; `beginRead(READ_COMMITTED)` opts out and costs
nothing. Writes are serialised, so they are effectively serializable — see §3.

---

## 2. Timestamps

One global 64-bit counter, and it is the **LSN**.

| Concept | Value |
|---------|-------|
| `snapshot_ts` | The LSN of the newest commit **already applied to the mapping** when the view began — `appliedLsn`, not `durable_lsn`. Durability and visibility are different instants, and a view must be fixed at what it can actually see |
| `commit_ts` | The LSN of the transaction's `TXN_COMMIT` record |
| `supersededAt` on a version entry | The `commit_ts` of the commit that overwrote those bytes |
| `oldest_valid_ts` | The minimum `snapshot_ts` over open `SNAPSHOT` views — the reclamation horizon. Anything superseded at or below it is dead. With no view open it is the present, so the version store empties itself |

Using the LSN as the timestamp means commit order and log order are the same order by construction.
There is no second counter to keep in step, and recovery does not have to reconstruct one.

On a reopened store the counter starts at the resume LSN rather than at zero, so a view opened before
the session's first commit reports where the store actually is.

---

## 3. Isolation Levels

| Level | Reads see | Anomalies possible | Built |
|-------|-----------|--------------------|-------|
| `READ_COMMITTED` | The latest commit, per operation | Non-repeatable read, phantom | **Yes** |
| `SNAPSHOT` (default for `beginRead`) | Everything committed at or before `snapshot_ts` | None reachable — see below | **Yes** |
| `SERIALIZABLE` | As snapshot, plus SSI conflict tracking | None | **Not built, and not needed** |

`SnapshotIsolationTest` asserts each anomaly at each level, and asserts the read-committed ones
**actually occur** — a level that silently behaves like a stricter one is a level nobody should be
paying for.

| Anomaly | `READ_COMMITTED` | `SNAPSHOT` |
|---|---|---|
| Dirty read | prevented | prevented |
| Non-repeatable read | **possible** | prevented |
| Phantom | **possible** | prevented |
| Lost update | prevented | prevented |
| Write skew | prevented | prevented |

The last two rows are stronger than this file originally predicted, and the reason is structural
rather than clever. **Write transactions are serialised by one writer lock, and a writer reads the
current mapping rather than any view** — `SegmentFile.readSlice` returns the writer its own
uncommitted pages and otherwise the mapping, never a reconstruction, even when the same thread has a
view open. Two writers therefore never overlap, and neither can decide on stale input. That is what
removes write skew, and with it the entire reason `SERIALIZABLE` and SSI existed in this plan: SSI
detects dangerous structures between *concurrent* writers, and there are none. It is not built
because there is nothing left for it to find, which is a different thing from being unfinished.

**The writer-reads-current rule is load-bearing, not an optimisation.** A writer decides what to
write from what it reads — which slot to allocate, where to append in an adjacency block, whether an
index page must split. Reading any of that through a view fixed in the past would compute the new
state from stale input and overwrite everything committed since: a lost update, silent, with no
conflict for anything to report. `SnapshotIsolationTest.testLostUpdateIsPrevented` runs four threads
incrementing one counter with a snapshot view held open throughout, precisely to pin that down.

### 3.1 Visibility rule

Visibility is resolved per **page**, at one choke point — `SegmentFile.readSlice` — rather than per
record. For a page with a chain of before-images (§5):

```java
// null means "the mapping already is what this view should see", which is the common answer.
MemorySegment pageAsOf(long pageKey, long snapshotLsn) {
    Version head = versions.head(pageKey);
    if (head == null || head.supersededAt <= snapshotLsn) {
        return null;                       // one comparison, no copy, no allocation
    }
    ...                                    // otherwise rebuild, once, and cache for this view
}
```

**Cost**: for a reader with no view open, zero — the branch is not even reached. For a view, one
comparison per page access, and a rebuild only for pages the writer has changed *and* the reader
actually touches. A rebuilt image is cached for the life of the view and never goes stale, because a
later commit appends an entry that undoes itself back to the same bytes.

**There is no in-flight set**, and the original rule's `!inFlight.contains(xmin)` term is not needed.
It exists in ARIES because a commit LSN is published before the data lands, leaving a window where a
commit is numbered but not observable. No-steal does the opposite: a commit becomes visible at
exactly one instant — when its pages are copied into the mapping, under the apply lock, by the single
writer — so there is no such window and nothing to exclude.

---

## 4. Transaction Lifecycle

What actually happens, for both kinds of transaction:

```mermaid
sequenceDiagram
    participant App
    participant TM as TransactionManager
    participant ST as Stores
    participant W as WalManager
    participant PV as PageVersions

    App->>ST: store.begin()
    Note over ST: takes the writer lock; one writer at a time
    App->>ST: setNodeProperties(id, ...)
    ST->>ST: copy the page into this transaction's overlay on first write
    Note over ST: reads see the overlay, then the current mapping — never a view
    App->>W: commit()
    W->>W: append the changed byte runs, then TXN_COMMIT, then fsync
    Note over W: the barrier — nothing above it has touched a data file
    W->>PV: keep the bytes about to be overwritten (only if a view is open)
    W->>ST: apply the runs to the mapping, under the apply lock
    W->>PV: reclaim everything no open view still needs
```

A read view is simpler, and deliberately so:

```mermaid
sequenceDiagram
    participant App
    participant TM as TransactionManager
    participant SF as SegmentFile

    App->>TM: store.beginRead()
    Note over TM: under the apply read lock, so the LSN and the registration land together
    TM-->>App: a view fixed at appliedLsn, published to this thread
    App->>SF: any read method
    SF->>SF: head(page) == null or superseded <= snapshotLsn? return the mapping
    SF->>SF: otherwise rebuild once from the chain, and cache for this view
    App->>TM: close()
    Note over TM: leaves the active set; the horizon moves at the next commit
```

### 4.1 Rollback

Discard the overlay and reload the allocator state from the bitmap, which is the authority. There are
no undo records to walk and no CLRs to emit, because nothing was written.

`RollbackTest` asserts the strong form of that: after a transaction performing every mutation kind is
rolled back, the segment files are **byte-identical** and no flush was needed — not "the store behaves
as though nothing happened" but "nothing happened". `RollbackEqualsRecoveryTest` then performs the
same transaction in one store and crashes mid-transaction in another, and asserts both reach the same
state, with a control that proves the comparison can tell them apart.

### 4.2 The `xmin` placeholder problem, and why it does not arise

A record's `xmin` would have to be the commit timestamp, which is not known until commit. Two standard
options:

- Write `txn_id` into `xmin` with a high bit set meaning "uncommitted", and have readers consult a
  small commit table to resolve it. Cheap write, slightly costlier read.
- Rewrite every touched record at commit. Cheap read, costlier commit, and it re-dirties pages.

**Neither is needed.** The question only exists because visibility is being resolved from a field
written *before* the commit timestamp is known. Page versions are recorded **at apply time**, when the
commit LSN is already in hand, so an entry carries its real LSN from the moment it exists. There is no
placeholder, no recent-commit map and no lazy rewrite pass. The first option remains the right one if
the record-level design of §5.2 is ever built.

---

## 5. Version Storage

**Built as in-memory before-images at page granularity** (`PageVersions`), not as the on-disk `UNDO`
segment described below. The original design is kept after the new one, because it is what would be
needed if the writer ever stopped being singular.

### 5.1 What was built

One chain per page, newest first, each entry recording the bytes one commit overwrote:

```
page 7 ── (superseded at 940, offset 128, 64 bytes) ── (at 902, offset 4096, 8 bytes) ── null
```

To read page 7 as of LSN 910: start from the mapping as it is now and apply every entry above 910,
newest first. Older entries win where ranges overlap, which walking newest-first gives for free. An
entry at or below the snapshot LSN describes a change the view is entitled to see, so the walk stops.

The entries come from the commit path at no extra cost: `logCommit` already computes the exact byte
runs a commit changes, in order to log them. Recording the previous content of those same runs is one
copy, and it is **skipped entirely when no snapshot view is open**, which is the state a write-only
or read-committed workload stays in permanently.

**Why not the record-level design below.** Two reasons, and the first is the decisive one:

1. **Record-level undo cannot cover what a reader actually reads.** A traversal touches adjacency
   blocks; a lookup touches index pages; every read of anything consults the free-slot bitmap and the
   page-metadata array. None of those has an `xmin` or a `version_ptr`, and none could easily be
   given one. Versioning the page covers the whole engine through a single choke point instead of
   requiring every store, the allocator, the B+tree and the catalog each to grow its own visibility
   rule. `VisibilityTest` exercises exactly this: a view must still resolve a node whose slot has
   since been freed *and handed out again*, which is a question about the bitmap and the generation
   counter, not about the record.
2. **Undo does not need to be durable here.** Under no-steal, uncommitted data structurally cannot
   reach a data file, recovery is redo-only, and after a crash there are no live snapshots. Undo is
   needed exactly as long as the oldest open reader — a process-lifetime concern — so it lives in
   memory and costs no log record, no segment kind and no format version bump.

**The cost** is memory proportional to what the writer changed while a view was open, which is the
standard MVCC trade. It is bounded rather than hoped about: past `retainedVersionBytes` (256 MiB by
default) the oldest views are abandoned, oldest first, and an abandoned view raises
`SnapshotTooOldException` on its next operation. Giving a reader an error is the only honest
alternative to giving it the wrong answer.

**Measured** (`LongRunningReaderTest`), one view held across 60,000 property updates over 2,000 nodes:

| | |
|---|---|
| Peak retained | **200.6 MiB** |
| Entries | 990,899 over 392 pages |
| Rebuilt *by the reader* | **192 KiB** — the pages it actually read |

The gap between those last two is where the next optimisation is. Retention accumulates one entry per
overwritten byte run **per commit**, so a page that every commit touches — the segment metadata page
above all, which carries the allocator bitmap and the page-metadata array and is therefore dirtied by
every write and consulted by every read — collects one entry per commit, when only the oldest can ever
be needed. Collapsing an entry whose byte range is already covered by a newer-than-every-open-view
entry would remove most of that. **It is not done**: the rule has to be exactly right or a reader gets
a silently wrong page, and the budget already bounds the damage. What it costs today is that a default
256 MiB budget supports roughly 75,000 commits under an open reader before the reader is abandoned.

**Reclamation** runs after every commit. Everything superseded at or below the oldest open view is
dead and is cut from the chains; with no view open the horizon is the present, so the store empties
itself. Each chain also carries the LSN of its *oldest* entry, which lets vacuum decline in one
comparison — without it, a pinned view makes every commit walk the accumulated chains looking for
nothing, which is quadratic over a run and was measured as a reader that would not finish.

**Two maintenance operations are refused while a view is open**, because they reach past this
mechanism rather than through it. `reclaim()` punches holes in all-free pages, and a hole reads back
as zeros that a view cannot distinguish from data — silent. `compact()` unlinks emptied segment
files, pulling the mapping out from under a reader. Both are rare and deliberate, and compaction
already invalidates every id a caller holds, so refusing costs nothing.

### 5.2 The record-level design, retained

Not built. It is what would be needed if concurrent writers were added, because two writers cannot
share page-granular versions: their private copies of one page would compose by last-writer-wins and
lose each other's records.

Undo records would live in `UNDO` segments, 64 bytes each:

```
offset size field
0      8    txn_id
8      8    begin_ts           xmin of the version this record supersedes
16     8    end_ts             commit_ts of the superseding txn; the version is valid in [begin, end)
24     8    prev_version       UNDO id of the next-older version, or NULL_ID
32     8    target_id          the element this version belongs to
40     2    kind
42     2    payload_len
44     4    flags              INLINE, BLOB, DELETE_MARK
48     16   payload            before-image, inline, or a BLOB id + length
```

The chain would be doubly useful: rollback and recovery undo read it backwards from the newest, and
snapshot reads read it backwards until `begin_ts <= snapshot_ts`. One structure, two consumers.

Its vacuum would additionally reclaim dead home records — freeing the slot, the property block, the
adjacency blocks and any uniquely-owned blobs — and mirror adjacency entries left behind by a lazily
deleted node ([RELATIONSHIP_STORAGE.md §6.2](RELATIONSHIP_STORAGE.md#62-delete-an-edge)). None of
that is needed by the page-level scheme, where a deleted record's old content is simply part of its
page's history.

## 6. Conflict Detection

**There is nothing to detect, because writers are serialised.** `store.begin()` takes one writer lock;
a second writer waits rather than conflicting. No `LockTable`, no write intents, no
`TransactionConflictException` — a striped intent table whose every lookup misses would be machinery
that documents an ambition rather than doing work, and `ConflictTest` against it would assert a
conflict that cannot arise.

What is built instead is the retry seam the spec asks for in a different item:
`store.inTransaction(work, attempts)` runs a unit of work in a transaction and retries it, refusing to
retry the failures that repeating cannot fix — a constraint violation is about data that has not
changed, and `SnapshotTooOldException` is about a reader that no longer exists. If concurrent writers
are ever built, that is where eager per-element detection would surface, and the design below is what
it should be.

### 6.1 The design, if writers ever go concurrent

**Write intents, detected eagerly.** A striped table mapping `ElementId -> txn_id` for elements
currently being written. Acquiring an intent already held by a live transaction throws immediately
rather than at commit: it fails fast, keeps the abort cheap, and makes the failure attributable to a
specific statement rather than to a commit.

The table would be memory-only and its loss harmless — after a crash there are no live transactions.
**Granularity** per element, not per page, so two transactions writing different nodes in one page
never conflict logically. **Deadlock** impossible, because intents are never waited on.

**The harder half is not the table.** Page overlays are what make concurrent writers difficult here:
two writers each take a private copy of a page, and applying both at commit resolves by
last-writer-wins across the whole page, losing the other's records. That needs either record-granular
overlays or per-writer allocation arenas plus commit-time validation, and it is a rewrite of the write
path rather than an addition to it — which is the honest reason concurrent writers are not in this
phase.

## 7. Where Copy-on-Write Actually Applies

The requirement list asks for CoW. CoW in its usual sense — never overwrite; write the new version
elsewhere and flip a pointer — is **incompatible** with "an id decodes to a fixed offset", because
its whole mechanism is relocation. That tension has to be resolved explicitly rather than papered
over.

**Resolution: keep the home location canonical; use CoW everywhere it costs no lookup.**

| Where | CoW used? | How |
|-------|-----------|-----|
| Element home records | **No** | In-place, made atomic and crash-safe by the WAL. This is what preserves the addressing invariant. |
| Segment superblocks | **Yes** | A/B double buffer, atomic flip by `sb_sequence` + CRC. Immune to torn writes with no log record. |
| Store manifest | **Yes** | Same A/B mechanism; the metadata commit point. |
| Dirty pages in the cache | **Yes** | A dirty page is a shadow copy until write-back; the copy is never the addressed location. |
| Old row versions | **Yes** | Copied to `UNDO` before overwrite. This *is* CoW — the copy is of the old version, and it is what serves snapshot reads. |
| Property blocks that change size | **Yes** | New block allocated, old retained until no snapshot needs it. |
| Adjacency block sealing | **Yes** | The sorted/compressed block is written to a new block; the old one is freed after the flip. |
| Index pages (Phase 7) | **Yes** | A CoW B+tree: page splits write new pages and update the parent, which the `INDEX` kind's id indirection makes free (index ids are internal and are never held by applications). |
| Snapshots and backups | **Yes** | `FICLONE` reflink clones a segment file in O(1) on btrfs/XFS; a whole-store snapshot is a reflink of `seg/` plus a WAL position. |

So: the durability properties CoW is usually reached for (torn-write immunity, atomic metadata
commit, consistent snapshots, readers never blocked by writers) are all present. The one place it is
*not* used is the one place where it would cost an indirection on every read forever.

### 7.1 If a pure CoW store is ever required

There is a coherent variant: make the id name a **logical** slot and keep a per-segment page table.
It buys relocation-without-reference-rewriting (so no compaction id churn) and costs one extra
lookup per read. It is the classic trade.

Should that ever be needed, it can be added as a **second `kind` family** rather than a rewrite —
the kind field already selects the addressing policy. Do not do this speculatively.

---

## 8. Conventions and Gotchas

- **A writer reads the current mapping, never a view** — even on a thread that has one open. This is
  the rule that prevents a lost update, not a convenience. See §3.
- **A transaction sees its own writes.** The overlay is consulted before anything else, so
  read-your-writes outranks both the mapping and any view.
- **A Cypher write query is one transaction**, opened and committed by `Cypher.stream`, or joined when
  the caller already holds one. It opens **no read view**: a writer reads the current mapping anyway,
  and a view would only add a liveness check that an abandoned view can fail. A write inside an open
  read view is refused rather than silently run outside it ([CYPHER.md §4.1](CYPHER.md)).
- **`begin()` refuses to nest**, as `beginRead()` does. It used not to, and nesting was silent: the
  inner commit cleared the published overlay, after which the outer transaction's writes went straight
  to the mapping — unlogged, and past any rollback. `hasWriteTransaction()` exists so a library can ask
  rather than find out. `NestedTransactionTest` covers the refusal and, more to the point, that the
  transaction already open is unharmed by it and still rollback-able.
- **Never interrupt a thread that is committing.** `FileChannel` is an `InterruptibleChannel`, so
  reaching one with the interrupt flag set makes the JDK close the channel rather than fail the call —
  and the WAL channel is shared, so one cancelled task used to leave every later commit *on every
  thread* failing with `ClosedChannelException`, `close()` included. `commit()`, `checkpoint()` and
  `close()` now hold the interrupt back across their durable section and restore it on the way out, so
  cancellation is delivered but not in the middle of making a transaction durable. The residual window
  is an interrupt arriving from another thread while the force is already in flight; closing that one
  needs the log writes moved onto a thread nobody else can interrupt.
- **`verify()` takes the writer lock**, not just the apply latch, because it both reads state a writer
  is mid-way through changing and mutates the dirty-page set on its way through. See §9.
- **Close read views.** An open view holds every version committed since it began. A forgotten one is
  a memory leak with a name: `snapshotStats()` reports its age and what it is holding, and past
  `retainedVersionBytes` the store abandons it rather than growing.
- **`SnapshotTooOldException` is a normal outcome**, not a bug. Retry with a fresh view, or raise the
  budget if the workload genuinely needs a reader open that long.
- **A view is thread-confined.** It is published to the thread that opened it, and using the handle
  from another is refused — the reconstruction cache under it is unsynchronised by design. Open one
  per thread.
- **Nothing is retained while no view is open**, so a write-only or read-committed workload never
  pays for any of this. It also means the first thing to check when retention is unexpectedly high is
  which reader is open, not how much the writer wrote.
- **`reclaim()` and `compact()` are refused while a view is open.** Both reach past the versioning
  mechanism — a punched hole reads back as zeros, and a retired segment file takes its mapping with
  it — and a refusal is much better than either failure.
- **Reclamation runs at the end of a commit**, not on a timer. A store that has stopped committing
  never releases what its last reader was holding until the next commit.

---

## 9. Test Setup

Built, in `io.metaloom.graph.core.txn`:

| Class | Cases | What it holds down |
|---|---|---|
| `VisibilityTest` | 13 | Create, update, delete and edge changes invisible to an earlier view; adjacency blocks and both indexes read as of the view; a freed **and reused** slot still resolving; read-your-writes outranking the view; thread confinement |
| `SnapshotIsolationTest` | 7 | The anomaly matrix of §3, including the read-committed anomalies actually occurring; lost update and write skew under real concurrency |
| `VacuumTest` | 8 | Nothing retained with no view open; read-committed views retaining nothing; the oldest view setting the horizon; a pinned view holding what a later one does not need; the budget abandoning a view rather than serving it wrongly |
| `ConcurrentReaderTest` | 4 | Sixteen views at sixteen instants against a continuous writer; reconstruction proportional to what is read; maintenance refused while a view is open; no view left behind on a pooled thread |
| `RollbackTest` | 7 | Every mutation type rolled back to **byte-identical** segment files, with no flush; repeated rollbacks not ratcheting the allocator |
| `RollbackEqualsRecoveryTest` | 2 | The same transaction rolled back and crashed reaching the same state, plus a control proving the comparison can tell them apart |
| `LongRunningReaderTest` | 2 | `@Tag("slow")`. A view held across 60,000 commits; and the same run against a budget too small, abandoned cleanly with the store unharmed |
| `SnapshotLinearizabilityTest` | 2 | The general form of the anomaly matrix: a view at LSN `L` must show **exactly** the committed state at `L`, whole-graph, checked against a model history keyed by commit LSN. Around 5,000 views per run |
| `NestedTransactionTest` | 5 | `begin()` refusing to nest; the open transaction surviving the refusal; writes after it still rollback-able; the writer lock not leaked |

And in `io.metaloom.graph.core.concurrency`:

| Class | Cases | What it holds down |
|---|---|---|
| `VerifyUnderLoadTest` | 4 | Verification concurrent with writers, with a checkpointer, and with a second verifier; the throttled form making progress without hanging. The regression test for the accounting-mismatch false positive |
| `ParallelConsistencyTest` | 3 | A conserved total across ~840,000 snapshot audits while writers move it; `READ_COMMITTED` *observing* a torn total, so the weaker level is proven weaker; structural invariants checked through an open view, which `verify(FULL)` cannot do |
| `WriterLockLivenessTest` | 7 | The writer lock released after a runtime exception, an `Error`, an unclosed transaction, a rollback, a non-retryable failure inside `inTransaction`, an interrupt, and a storm of failing writers |
| `CloseUnderLoadTest` | 2 | `close()` under load failing only in the engine's own vocabulary; `abandon()` under load left recoverable, verifying clean on reopen |
| `HammerTest` | 1 | The whole public API concurrently — both levels, cursors, traversals, retries, checkpoints, refused maintenance — with self-checksumming property values, which is the only oracle available for the optimistic read path |
| `CursorLeaseLivenessTest` | 3 | An open cursor blocking every writer, and releasing them on close; double-close safe; many short-lived cursors not wedging the store |
| `MultiStoreIsolationTest` | 2 | Four stores written concurrently with no cross-talk — the one place two writers genuinely overlap; a second handle on one store refused |
| `AutoCommitAtomicityTest` | 1 | A single `createRelationship` writes a REL, two adjacency entries and two degree counters; no view may catch that half done |
| `SnapshotBudgetUnderLoadTest` | 1 | Six views of different ages against a budget too small: abandonment follows age, and the survivors are still *right* rather than merely alive |
| `CatalogDurabilityWindowTest` | 2 | The dictionary is forced before the commit that introduced it, so a rolled-back or crashed label outlives its transaction — pinned as harmless and reusable |

And at the layers above the engine:

| Class | Module | What it holds down |
|---|---|---|
| `CypherConcurrencyTest` | `graph-cypher` | A conserved total read through Cypher while writers move it — a query is dozens of storage calls and the view is what stops it spanning two states; concurrent write queries each committing exactly once |
| `WireHammerTest` | `graph-server` | The same total over a real socket from many clients; clients hanging up mid-stream leaving no read view behind; no leaked event loops |

Not built, with reasons: `ConflictTest` (§6 — no conflict can arise), `SnapshotTooOldTest` (folded
into `VacuumTest` and `LongRunningReaderTest`, which reach the condition through real workloads rather
than by construction), jcstress harnesses (jcstress is not in the offline repository).

---

## 10. Progress Assessment

- [x] `Transaction` with begin/commit/rollback, read-your-writes, and auto-commit for single operations
- [x] Single-writer lock plus a reader-excluding latch held only for the commit apply step
- [x] Rollback by discarding the page overlay, with allocator state restored from the bitmap
- [x] `TransactionManager`: LSN timestamps, the active set, the horizon, the retention budget
- [x] Visibility check and version walk — **per page**, at one choke point (§3.1, §5.1)
- [x] `PageVersions` in place of `UndoStore`, with the reasoning in §5.1
- [x] `READ_COMMITTED` and `SNAPSHOT` levels
- [x] `Vacuum`: reclamation after every commit, declining in O(1) when nothing is cuttable
- [x] `SnapshotTooOldException` and the retention horizon
- [x] `store.inTransaction` with retry
- [x] Optimistic lock-free reads via the `StampedLock`; a view forces the locked path only to rebuild a page
- [x] Tests per §9
- [ ] **Concurrent writers.** The remaining half of the phase, and the one that needs the write path
      rewritten rather than extended — §6.1
- [ ] `xmin` placeholder scheme, the recent-commit map, the lazy rewrite pass — **not needed** by the
      page-level scheme; part of §5.2, which concurrent writers would require
- [ ] `UndoStore` and the 64-byte undo record — as above
- [ ] `LockTable` with eager conflict detection — **nothing to detect** while writers are serialised (§6)
- [ ] `SERIALIZABLE` via SSI — **nothing to detect** (§3): write skew is already unreachable
- [ ] jcstress harnesses — jcstress is not in the offline repository

## 11. Key Classes Reference

| Class | Package | Purpose |
|-------|---------|---------|
| `TransactionManager` | `io.metaloom.graph.core.txn` | Active views, the reclamation horizon, the retention budget |
| `Snapshot` | `io.metaloom.graph.core.txn` | One view: its LSN, its visibility rule, its reconstruction cache |
| `SnapshotHolder` | `io.metaloom.graph.core.txn` | Thread-local publication of a view to the segment layer |
| `PageVersions` | `io.metaloom.graph.core.txn` | Per-page before-image chains, reconstruction and reclamation |
| `Transaction` | `io.metaloom.graph.core.api` | Per-transaction write state |
| `ReadTransaction` | `io.metaloom.graph.core.api` | The public view handle; `Snapshot` implements it |
| `IsolationLevel`, `SnapshotStats`, `SnapshotTooOldException` | `io.metaloom.graph.core.api` | The level, what a view costs, and what happens when it costs too much |

Not built, and named here so nobody looks for them: `LockTable`, `UndoStore`, `Vacuum` as a separate
worker (reclamation is a call at the end of commit, not a thread).

---

_Git HEAD revision: `e4601d1`_
_Last updated: 2026-08-15 (Cypher writes)_
