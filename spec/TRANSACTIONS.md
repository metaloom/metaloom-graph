# Transactions, ACID and Copy-on-Write

Isolation, visibility, conflicts and version lifetime. The log mechanics are in [WAL.md](WAL.md);
this file is about what a transaction *means*.

---

## 1. Guarantees

> **Implementation status.** Atomicity, durability, rollback, snapshot isolation, the reclamation horizon, vacuum
> **and concurrent writers** are implemented. `SERIALIZABLE` is implemented, by read-set validation rather than by SSI.
>
> The mechanism differs from what the rest of this file describes, and section 5 explains why: visibility is resolved
> per **page** from in-memory before-images, not per record from an on-disk `UNDO` segment. `xmin`, `version_ptr` and
> the undo record layout are therefore **not used**; the fields stay in the record layouts, written as zero, and the
> format is unchanged. Section 5.2's record-level machinery was predicted to be the price of concurrent writers and
> turned out not to be — §6 explains what was built instead — so it is retained only as the design a record-granular
> engine would need.

| Property | Mechanism | Notes |
|----------|-----------|-------|
| **Atomicity** | No-steal redo WAL; private page copies discarded on rollback | A transaction is all-or-nothing across all seven record kinds, the dictionaries and the indexes |
| **Consistency** | Invariant checkers plus cascade rules | The engine enforces structural invariants (bidirectional edges, degree counters, no orphan property blocks); application-level constraints are out of scope |
| **Isolation** | MVCC snapshot isolation, for readers and writers alike; first-committer-wins between writers | See §3 |
| **Durability** | WAL group commit with `fdatasync` | See [WAL.md §4](WAL.md#4-group-commit) |

A read on its own sees the latest commit. `store.beginRead()` opens a **snapshot** view that fixes what
every read on that thread sees until it is closed; `beginRead(READ_COMMITTED)` opts out and costs
nothing. `store.begin()` opens a write transaction, which reads through a view of its own and is
validated against everything committed since when it commits — see §3 and §6.

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
| `READ_COMMITTED` | The latest commit, per operation | Non-repeatable read, phantom | **Yes** — read views only |
| `SNAPSHOT` (default for both `beginRead` and `begin`) | Everything committed at or before `snapshot_ts` | Write skew | **Yes** |
| `SERIALIZABLE` | As snapshot, plus validation of what the transaction read | None | **Yes** — write transactions only |

**A write transaction cannot run at `READ_COMMITTED`**, and asking for it raises rather than being
upgraded. A writer decides what to write from what it reads; reading the live mapping would mean
deciding from a state that has already moved, which is the lost update the whole design exists to
make impossible.

`SnapshotIsolationTest` asserts each anomaly at each level, and asserts the read-committed ones
**actually occur** — a level that silently behaves like a stricter one is a level nobody should be
paying for.

| Anomaly | `READ_COMMITTED` | `SNAPSHOT` | `SERIALIZABLE` |
|---|---|---|---|
| Dirty read | prevented | prevented | prevented |
| Non-repeatable read | **possible** | prevented | prevented |
| Phantom | **possible** | prevented | prevented |
| Lost update | prevented | prevented | prevented |
| Write skew | — | **possible** | prevented |

**The write-skew row used to say "prevented", and the change is the honest cost of concurrent
writers.** It said so because writers were serialised and read the current mapping, so the second
transaction saw the first's commit and declined — safety by scheduling rather than by isolation
level. Writers now overlap and each reads a fixed view, so two of them can each read what the other
is about to change and both commit. That is textbook snapshot isolation, and `SERIALIZABLE` is the
level that rules it out.

**Lost update is still prevented, by a different mechanism.** A transaction's writes are validated
at commit against everything committed since its snapshot; overlapping bytes mean the second
committer is refused with `TransactionConflictException` and retries against the new value. First
committer wins. `SnapshotIsolationTest.testLostUpdateIsPrevented` runs four threads incrementing one
counter with a snapshot view held open throughout, and now also pins down that the retry seam is what
carries it.

**Write skew is hard to observe here, and that is layout rather than isolation.** Two transactions
updating different nodes usually still collide, because both insert into the same property index and
rewrite the same bytes of it — a write-write conflict, reported before anything about reads is
considered. `SnapshotIsolationTest` therefore asserts the mechanism instead of the outcome: at
`SNAPSHOT` a transaction is **never** refused for something it only read
(`TransactionConflictException.isReadConflict()`), and at `SERIALIZABLE` one of the pair always is
refused. Relying on the index collision for safety would be relying on an accident.

**What the engine itself decides from is guarded at every level.** Write skew between transactions'
*data* is `SNAPSHOT`'s documented cost; write skew between two *engine operations* on one node is a
corruption, and it happened: `deleteNode` removes the index entries of the labels its snapshot held and
frees the slot — the generation and the allocation bitmap — while a concurrent `addNodeLabel` writes the
label bytes and the index. Disjoint bytes, so both committed, and the index kept an entry for a node that
was gone (`IsolationAnomalyStressTest`, about once per hundred seconds of load, and only when the two
index entries sat on different leaves — on one leaf they collide by accident). Node mutations —
`addNodeLabel`, `removeNodeLabel`, `setNodeProperties`, `deleteNode`, and both endpoints of
`createEdge`/`createRelationship` — therefore register the node's record range
(`WriteContext#guard`), and the commit refuses them if any byte of it was overwritten since the
snapshot, as a write conflict. The range is the record, not the page: two writers on neighbouring
nodes still commit side by side. `DeleteRaceTest` pins each case in both commit orders.

### 3.1 Visibility rule

Visibility is resolved per **page**, at one choke point — `SegmentFile.readSlice` — rather than per
record. For a page with a chain of before-images (§5):

The rule is the same for a writer, and that is the change concurrent writers made. A writer used to
read the live mapping; now it reads through a view of its own, and its overlay in front of that. What
used to be true by scheduling — a writer could not be shown a state that had moved under it, because
nothing else could move it — is now true by construction.

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
    Note over ST: opens a view at appliedLsn; writers do not exclude each other
    App->>ST: setNodeProperties(id, ...)
    ST->>ST: copy the page into this transaction's overlay, from its own view
    Note over ST: reads see the overlay, then that view — never the live mapping
    App->>W: commit()
    W->>W: diff each overlay page against the image it was copied from
    W->>W: refuse if anything else changed those bytes since the snapshot
    W->>W: fold the metadata journal onto the live bitmap and counters
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

The commit steps are in that order for reasons that are not interchangeable. **Validation comes before
the fold**, because the fold moves shared counters: a conflict raised after it left the live count
advanced over a bitmap whose bits were then discarded with the overlay, and `verify()` reported an
accounting mismatch on a store where nothing had gone wrong except a transaction losing a race.
**Validation comes before the log**, so a refused transaction has written no log record and touched no
data file and is over the instant it is refused. **The fold comes before the diff**, because until it
runs the transaction's allocations are operations rather than bytes and there is nothing to log.

### 4.1 Rollback

Discard the overlay and the metadata journal, and hand back the slots the transaction reserved out of
the allocator. There are no undo records to walk and no CLRs to emit, because nothing was written.

Rollback used to also reload every superblock from the mapping and empty the allocator's free rings.
That was right when a rollback could only be undoing the one transaction there was, and is destructive
with several: those are shared, and another writer's uncommitted work is not in them to be restored.
The one piece that cannot simply be discarded is the **high water mark**, which is shared and moves the
moment a slot is reserved — two writers each bumping a private copy would both claim the same slot. It
is put back, but only when this transaction is the only one in the store: the mark bounds every scan,
so lowering it under a concurrent view hides records that view is entitled to see, and lowering it
under a free-ring entry naming the same slot hands that slot out twice. Alone, neither can happen, and
alone is the case the property is about — `RollbackEqualsRecoveryTest` requires a rolled-back store and
a store crashed mid-transaction to agree down to `storageBytes`.

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

**Why not the record-level design below.** Two reasons, and the first is the decisive one. (§5.2
predicted a third — that page-granular versions could not survive concurrent writers — and it was
wrong; §6.1 explains what actually made them survive.)

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

**"Next operation" is not enough, and the page is where it is checked.** Vacuum runs after a commit
and takes no lock a reader holds, so a view can be abandoned *in the middle of* an operation — a scan or
a cursor holding the apply latch does not keep it out. The chains it needed are then cut under it, and a
page whose whole chain went has no head at all: the view's next read of it would be the mapping, newer
than its snapshot. `read()` re-checks the view after each operation, but a scan's callback or a cursor's
`next()` has handed rows out by then, and `AbandonedViewScanTest` scheduled a cursor that returned
10,840 edges where its snapshot had 12,000. So `Snapshot.pageAsOf` refuses at the page: a page with no
chain, read by a view below the horizon, raises `SnapshotTooOldException`, and a rebuild re-checks the
horizon before it is used or cached, since vacuum may have truncated the chain under the walk. Vacuum
raises the horizon before it cuts anything, so a chain seen missing is a horizon seen raised. The cost
is one volatile read on the path that is about to return the mapping to a view.

**Below the horizon is not the same as abandoned, so the view is told directly.** The horizon is the
oldest *remaining* view's LSN, and a write transaction that began before the abandoned view keeps it at
or below the abandoned one's. Out of the active set, the abandoned view has also stopped being a reason
for a commit to keep before-images: when that older writer commits it is the only one registered, so it
keeps nothing, and until a later vacuum raised the horizon every check passed and the view read the
writer's commit from the mapping — with `readsMapping()` taking it there on the fast path, since a commit
that records nothing never raises the newest-superseded mark either. The load test hit that window in
about one run in three on a busy machine and in none on a quiet one. So vacuum now calls
`Snapshot.abandon()` before the view leaves the active set, and every check — `checkAlive`, `pageAsOf`,
the rebuild re-check, and `readsMapping()`, which answers false for an abandoned view — asks
"abandoned, or below the horizon". `AbandonedViewScanTest#testAViewAbandonedBehindAnOlderWriterIsStillToldSo`
schedules it: an older writer, a view abandoned behind it, the writer's commit with vacuum held off.

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

Not built, and **no longer expected to be needed**. This section used to say it was the price of
concurrent writers, on the grounds that two writers cannot share page-granular versions because their
private copies of one page compose by last-writer-wins. Concurrent writers were built without it: the
overlay keeps the image each page was copied from, so a commit writes only the bytes it actually
changed and two writers on one page compose by union. §6.1 has the detail. What is below is retained
as the design a record-granular engine would need, not as unfinished work.

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

**Built, as backward validation at commit.** A transaction reads a fixed view, keeps its changes in a
private overlay, and at commit is checked against everything committed since it began. Overlapping
writes mean the second committer is refused with `TransactionConflictException`; first committer
wins. There is no lock table, no write intent and no waiting — a conflict is discovered rather than
prevented, and discovering it costs one walk of a chain the engine was already keeping.

### 6.1 What made it possible

§6.1 used to say that page overlays were the blocker: *"two writers each take a private copy of a
page, and applying both at commit resolves by last-writer-wins across the whole page, losing the
other's records"*. That was true of the overlay as it was, and the fix turned out to be smaller than
the record-granular rewrite it seemed to demand. **The overlay keeps the image each page was copied
from.** The commit then diffs `copy` against that base rather than against the live mapping, so the
runs it writes are exactly the bytes *this* transaction changed and nothing else. Two writers on one
page compose by union. Diffing against the mapping is what would have reverted the other writer, and
it was never load-bearing — it was just the cheapest way to find the diff when only one writer could
exist.

The same runs are the transaction's write set, so conflict detection comes out of it for free:
`PageVersions` already records, per page, which byte ranges each commit overwrote and at which LSN.
Asking "did anything change these bytes after my snapshot" is one walk of that chain, stopping at the
first entry the transaction is entitled to see.

### 6.1.1 What the diff costs, and what keeps it cheap

The base is a second page per touched page, and the diff is a pass over it, so a small commit — a node
with two properties touches about thirteen pages, nine of them metadata — was spending most of its CPU
copying and comparing pages it changed eight bytes of. Three rules keep that down; none changes what is
logged or what conflict detection sees.

- **The diff is confined to the written window.** Every write into an overlay copy goes through
  `PageOverlay.Page.writable(offset, length)`, which widens the page's window `[writtenFrom, writtenTo)`.
  Nothing outside the window can differ from the base, so the commit diffs only inside it, widened to
  whole 8-byte chunks — the result is the whole-page diff exactly. With assertions on (every test run)
  `diffPages` also runs the whole-page diff and fails the commit if the two disagree, which is what
  would catch a write that bypassed `writable`. `ChangedRunsTest` holds the contract from both sides.
- **A rebased page's base is the mapping itself.** Metadata pages are rebased under the commit lock and
  diffed straight after the fold, before anything is applied, so for the whole time the base is read
  nothing can change the mapped page. Copying it was eight KiB per metadata page per commit.
- **Buffers no caller can hold are recycled.** A data page's base and a rebased page's copy are read by
  nothing but the commit, so they come from a small per-thread pool (32 pages) and go back when the
  transaction ends. A data page's *copy* is never recycled: reads inside the transaction are slices of
  it, and nothing bounds how long a caller keeps one. The pool matters because a freshly allocated page
  is cold — measured at ~1.4 µs to allocate, fill and clone one — and a reused one is still in cache.

Measured in [GRAPH_BENCHMARK.md §11](GRAPH_BENCHMARK.md#11-profiling-round-2026-10-25): a small
auto-commit `createNode` went from 29.4 to 23.4 µs of CPU under `ASYNC`.

### 6.2 The part that is not byte-merged

Records compose; **the metadata does not**. Sixty-four slots share one bitmap word, the superblock
holds counters every writer moves, and the page metadata array holds a live count per page. Two
writers allocating adjacent slots write the same eight bytes from different starting images, and no
byte-level merge of those is correct — one of them is always describing a world in which the other's
allocation never happened. Treating it as a conflict instead would be sound and useless: a bump
allocator hands out consecutive slots, so nearly every pair of concurrent inserts would abort.

So metadata changes are recorded as **operations** in a per-transaction `MetaJournal` — set this bit,
add this delta, raise this floor — and folded onto the live state at commit, under the commit lock,
onto pages rebased from the mapping as it stands at that instant. The metadata is not contended data,
it is a resource ledger, and a ledger merges by replaying entries. Those pages are exempt from
conflict detection, because by the time they reach the overlay they are already resolved against
every writer that committed since.

**Slot allocation is the one thing that is still serialised**, per `SegmentSet`, and it has to be.
Every other collision between two writers is detectable afterwards — they wrote the same bytes, so
one aborts — but two writers handed the same slot have already put two records at one id, and no
bitmap, checksum or invariant can tell them apart later. The lock is held for a bitmap scan and a
record fill, not for a transaction. Alongside it sits a set of slots reserved by transactions that
have not committed: the bitmap in the mapping only knows about slots that *have*, so on its own it
would hand the same slot to two writers in flight at once.

Three reads of a bitmap word therefore have three different right answers, and confusing them is
where the sharp edges are:

| Question | Source | Asked by |
|---|---|---|
| What does this transaction see? | its view, plus its own journal | `isAllocated`, every ordinary read |
| What is actually taken? | the live mapping, plus the reservation set | the allocator, and only the allocator |
| What is durable? | the live mapping | the commit's journal fold |

The **generation counter** is allocator state by the same argument, and reading it through the view
was a real defect: a transaction reusing a slot freed after its snapshot wrote back the generation
from before the delete, handing out an id byte-for-byte identical to one already given away — and
because the copy then matched its base at those bytes, the diff did not carry them and the record
landed under a generation on disk its own id did not match. A reader was told, one read later, that a
property block it had just resolved was stale. It is read from the mapping now, except for a slot the
transaction freed itself, where the advanced value lives only in the overlay.

**A transaction may reuse a slot it freed itself, and only if the ids of that kind never leave the
engine.** A freed slot does not reach the shared allocator until the delete commits — offering it
earlier would let a rollback restore a record somebody else now owns — but a transaction that frees a
structure and rebuilds it inside one transaction is the ordinary case, not an exotic one, and without
reuse the rebuild takes fresh slots and the high water mark ratchets up once per rebuild for the life
of the store (`IndexSpaceReuseTest`). Reuse is nevertheless restricted to `INDEX`. While the delete is
uncommitted the generation counter is advanced in the overlay and not in the mapping, so the two
disagree for as long as the transaction runs — and every other kind is reachable through an id held
somewhere else while that is true. Enabling it for `PROP` was measured breaking the conserved total of
`ParallelConsistencyTest` under four writers. The index is the case that needs it and the case where
it holds: `resetTrees()` frees and rebuilds the whole structure in one transaction and no id survives
that rebuild, which is the same argument `SegmentSet.packFreeRing()` is limited by.

### 6.3 Conflicts a caller never sees

**An auto-commit mutation retries its own conflicts**, and so does a Cypher write query. This is the
difference between an explicit transaction and a single statement: a caller who opened a transaction
has a unit of work to reconsider, and a caller who called `setNodeProperties` has nothing to
reconsider — re-running the one statement against a newer state is what they meant. Raising it would
make every mutation on a store with two writers a checked failure, and the commonest conflict is not
even between the callers' data, it is two inserts landing in one B+tree leaf.

The retry **backs off**, which is not decoration. A conflict against a transaction that is still
running is resolved by that transaction finishing, and a durable commit takes milliseconds where a
failed attempt takes microseconds. Four writers racing to create the one index catalog page burned
all sixty-four of their attempts inside the winner's first commit and then raised a conflict on a
store where the only thing wrong was that somebody else was ahead of them. The delay is **jittered**,
because the losers of one round are otherwise scheduled to collide again: a winner's commit
invalidates every snapshot older than it at once, so an unjittered delay hands every loser the same
wake-up time.

**Retrying optimistically is not on its own a guarantee of progress**, and the argument that said it
was is recorded here because it was wrong. It ran: a conflict has a winner, so each attempt faces one
fewer competitor. That describes a queue that drains, and writers in a loop are not one — the winner
commits and comes straight back with its next write, so the number of competitors is constant and a
loser's chance never improves. A conflict is decided in the window between a transaction's snapshot
and its turn at the commit lock, and every writer contending for one hot B+tree leaf invalidates every
snapshot older than its commit. Six writers on one index leaf starve one of their number past
sixty-four attempts often enough to measure, and past two hundred and fifty-six as well. It was not a
bound set too low; it was an unbounded wait wearing a bound.

Making the commit lock **fair** makes it strictly worse, which is worth stating because it is the
obvious first idea: FIFO order guarantees that everybody ahead of the loser commits before it does, so
the loser's snapshot is stale every time it reaches the front. Measured, it took the case from half of
runs failing to all of them.

So an auto-commit write that exhausts its optimistic attempts **takes the write gate exclusively and
runs once more** (`GraphStoreImpl.runExclusively`). With no other writer in flight there is no window
for a conflict to be decided in, so the attempt cannot lose one, and progress is guaranteed rather than
hoped for. The gate is then re-taken shared by the `begin()` inside it — the downgrade
`ReentrantReadWriteLock` is there for and the same sequence compaction runs — so no new lock ordering
is introduced. It is deliberately the exception: excluding every other writer for one statement is the
throughput of a single-writer engine, which is what the optimistic attempts exist to avoid paying. The
alternative for a caller who wrote one statement is not a slower write but a failed one.
`GraphStoreImpl.autoCommitEscalations()` counts how often it happens, which is the number that
separates "the retry loop absorbed contention" from "the engine serialised writers to finish a
statement".

**Two counters, because they answer different questions**, and conflating them cost a flaky test.
`autoCommitRetries()` counts every attempt that lost a conflict and was re-run; `autoCommitEscalations()`
counts only the statements that lost `AUTO_COMMIT_ATTEMPTS` of them *in a row*. A retry is what
contention looks like and a busy leaf produces hundreds of them; an escalation is starvation, which is
rare by design and **emergent** — whether any writer meets it in a given run is decided by how the host
happened to interleave its threads. So an escalation count is the right thing for an operator to alert
on and the wrong thing for a test to require. `ConcurrentWriterTest.testAutoCommitAbsorbsIndexLeafContention`
required it and failed with a count of zero on a machine where nothing was wrong: every conflict had
been absorbed, which is the promise being kept. It now asserts `autoCommitRetries() > 0` — that the
writers did in fact collide, which is what stops the pass being vacuous — and the fallback itself is
proven separately by `testAutoCommitEscalatesWhenEveryOptimisticAttemptLoses`, which **schedules** the
starvation: a helper thread commits over the statement's record once per attempt, so every optimistic
attempt loses and the escalation is reached by construction rather than by luck. The helper stands
down when `autoCommitEscalations()` moves, because the engine increments it before taking the gate and
a helper that kept going would block on that gate waiting for the statement waiting for it. Removing
the fallback fails that test in under a second, where it used to reproduce one run in ten.

**One mechanism, both statement paths.** A Cypher write query had its own copy of the optimistic loop
in `Cypher.write`, and the copy carried the defect with it — the same bounded attempts, the same
unjittered backoff, and the same raised conflict at the end, which `CypherConcurrencyTest` then
refused a query over. Both now go through `GraphStore.inStatement(work)`, which is this contract given
a name: the engine absorbs the conflicts, the work may run more than once, and it cannot end in a
`TransactionConflictException`. `inStatement` refuses to run inside an open transaction, because
absorbing a conflict there would re-run the caller's work inside their unit of work and commit it out
from under them. `USING PERIODIC COMMIT` is deliberately not covered: it has already committed earlier
batches by the time a later one loses, so re-running the statement is not available to it.

### 6.4 `SERIALIZABLE`

`begin(SERIALIZABLE)` additionally records the pages the transaction **read** and validates them the
same way. That is backward validation rather than SSI, and it gives the same guarantee: a transaction
that decided something from a page another transaction has since committed over did not run at any
single instant, so it is aborted.

**SSI is not built and is not the obvious next step.** It exists to abort *less* — it looks for the
dangerous structure of an in-conflict and an out-conflict meeting at a pivot, rather than aborting on
any stale read. Buying that here would mean an in/out flag per transaction and a second pass to find
the pivot, against a read set the engine already computes for free. The cost of the simpler rule is
paid in granularity: reads are tracked per page, not per record, so two transactions touching
unrelated records that share a page are a conflict. Sound, never a wrong answer, and a level to ask
for where it is needed rather than to default to.

**A dictionary miss is a read that touches no page**, and it is validated separately. `nodesByLabel`,
`nodeHasLabel`, `nodeProperty`, the composite lookup and the type filters all return on the spot when the
label, type or key is not in the dictionary, so a page read set records nothing — and a concurrent
transaction that introduced the name and committed was invisible to validation.
`SerializablePhantomTest` committed both halves of a write skew that way: "no doctor exists, so record
the decision" against "the decision is not recorded, so create the first doctor". A `SERIALIZABLE`
writer now remembers each name it looked up and did not find (`Catalog.MissObserver` into
`WriteContext.missed`), and the commit refuses it as a read conflict if any of them resolves by then.
The dictionary is not versioned and does not say who introduced a name, so this is coarse in one
direction: a transaction that missed a name and then introduced it itself — create-if-absent on a new
label — is refused **once**. Its retry finds the name, which outlives the rollback, and validates
normally. It is never a wrong answer, and a name the transaction already knew costs nothing.

**`LockTable` is not built and will not be.** Eager per-element intents fail fast and make the abort
cheap, which is a real advantage; what they cost is a striped table on the write path of every
statement, and a decision about lock granularity that the byte-ranged validation does not need to
make. Validation at commit costs nothing until a commit happens. If aborts ever become the
bottleneck, the intent table of the original design is where to go, and it is described below.

### 6.5 The eager design, retained

**Write intents, detected eagerly.** A striped table mapping `ElementId -> txn_id` for elements
currently being written. Acquiring an intent already held by a live transaction throws immediately
rather than at commit: it fails fast, keeps the abort cheap, and makes the failure attributable to a
specific statement rather than to a commit.

The table would be memory-only and its loss harmless — after a crash there are no live transactions.
**Granularity** per element, not per page, so two transactions writing different nodes in one page
never conflict logically. **Deadlock** impossible, because intents are never waited on.

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

- **A writer reads through a view of its own**, not the live mapping. It used to be the opposite, and
  the reversal is the heart of the concurrent-writer change: with one writer at a time the mapping
  could not move underneath a transaction, and now it can. What prevents a lost update is no longer
  the read but the commit-time validation of the write set. See §3 and §6.
- **The allocator is the exception, and only the allocator.** It reads the live mapping plus the set
  of slots reserved by transactions in flight, because "is this slot taken" is not a question about
  data and a slot somebody committed since is taken however invisible its contents are. Everything
  else in the engine must read what its transaction is entitled to see. The generation counter is
  allocator state by the same argument — §6.2.
- **A writer holds the apply latch for the length of one operation.** Not for the transaction: a
  commit waits for one statement, not for a unit of work. The single writer lock used to provide this
  by accident and it was load-bearing — without it a writer checks a page's version chain, finds
  nothing newer than its snapshot, and reads the mapping, while in between a commit publishes its
  before-image and copies its bytes in.
- **`TransactionConflictException` is a normal outcome**, like `SnapshotTooOldException`. Retry it;
  `inTransaction` does. Auto-commit mutations and Cypher write queries retry it internally, so it only
  reaches a caller who opened the transaction themselves. **Progress is guaranteed only on the statement
  path** (`inStatement`, auto-commit, Cypher), by the exclusive fallback of §6.3. `inTransaction` is a
  bounded optimistic retry and can run out: the "one fewer competitor" argument this line used to make
  is the one §6.3 records as wrong. A batch of index inserts beside a stream of short transfers lost a
  thousand attempts in a row in `EverythingAtOnceStressTest`, and backing off between attempts did not
  change the rate — the losers queue on the commit lock behind the winner with a snapshot that is
  already stale. Work that must finish and is safe to repeat belongs in `inStatement`.
- **A thread holds the apply latch shared at most once.** `StampedLock` read holds are not reentrant: a
  second `readLock()` from a thread that already has one is an ordinary acquisition, and when its fast
  path loses a race with another reader it queues behind a waiting writer — a commit, waiting for the
  hold this same thread still has, with every other writer queued behind the commit lock. Nesting is
  ordinary: a mutation calling the store's own public reads (`setNodeProperties` on a vector-indexed node
  asks `nodeLabels`), a read inside a scan callback, a cursor opened inside another, `begin()` with a
  cursor open. Every shared acquisition therefore goes through a per-thread hold
  (`GraphStoreImpl.ApplyHold`) that takes the latch for the outermost scope only.
  `NestedApplyLatchTest` wedged the store in every run before it.
- **Long read holds stand aside for a waiting apply.** `StampedLock` has no writer preference either, and
  a scan or cursor holds the latch for its whole length, so a few scanning threads whose holds overlap
  kept every commit out: 127 commits a second alone, 3 beside four scanners, single commits waiting
  900 ms (`ScanWriterStarvationTest`). A commit announces itself before it waits for the latch
  (`applyWaiters`), and a lease about to take a new outermost hold waits — bounded, 50 ms at most — for
  that apply first. Short reads do not wait: they are optimistic or brief, and are not what starved it.
- **Vacuum is serialised.** It runs outside every other lock, which was safe while one writer meant
  one commit meant one vacuum. Two of them cutting the same chains at different horizons truncate a
  tail the other is still walking, and what that looks like from outside is a page whose newest
  before-image is older than it should be — so a view entitled to reconstruct the page decides it does
  not need to, and reads bytes from the future.
- **A transaction sees its own writes.** The overlay is consulted before anything else, so
  read-your-writes outranks both the mapping and any view.
- **A Cypher write query is one transaction**, opened and committed by `Cypher.stream`, or joined when
  the caller already holds one. It opens no read view of its own — the write transaction already has
  one, and a second would only add a liveness check that an abandoned view can fail. A write inside an
  open read view is refused rather than silently run outside it ([CYPHER.md §4.1](CYPHER.md)). When it
  owns the transaction it **retries its own conflicts**, because a query is a single statement with
  nothing for the caller to reconsider; when it joined the caller's, it neither commits nor retries.
- **A statement that fails inside a transaction leaves it either untouched or rollback-only.** An
  auto-commit statement is its own transaction, so a failure rolls all of it back. Inside a caller's
  transaction there is no statement boundary to roll back to — the overlay holds one copy of each page,
  not one per statement — so the engine sorts failures by whether the statement had already changed the
  transaction. A refusal checked before anything is written (a unique-constraint violation, a stale id,
  a null label) leaves the transaction exactly as it was, and the caller may catch it and carry on,
  which is the ordinary way to use a constraint. A failure discovered partway — an unsupported value
  type or an oversized property block after a blob was already written for an earlier value — marks
  the transaction **rollback-only**: `commit()` rolls it back and raises `GraphStoreException` naming
  the first failure, because committing would make half a statement durable. Both used to commit the
  half: `setNodeProperties` wrote the new block before checking the unique constraint, so a caught
  violation committed the refused value with the indexes still describing the old one; and a
  `createNode` whose second property could not be encoded committed a node with no index entries and
  an orphaned blob. Whether a statement "changed" the transaction is a counter in `WriteContext` that
  moves on every page slice taken for writing, every slot reserved or freed and every journal
  operation; the unique check's own leaf claims are deliberately not counted, since committing them is
  harmless. `StatementAtomicityTest` holds down both halves; `StorageModelTest` found it. Work made of several
  statements — a Cypher query joined to the caller's transaction — gets the same boundary through
  `GraphStore.inOpenTransaction`.
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
- **`verify()`, `checkpoint()`, `reclaim()`, compaction and `close()` exclude writers outright.** They
  reach past the transaction mechanism rather than through it — relocating a record, punching a page,
  unmapping a segment file — so they take the write gate exclusively while an ordinary transaction
  holds it shared. Compaction takes it one batch at a time, so a foreground writer waits for a batch
  rather than for a whole segment.
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
  never releases what its last reader was holding until the next commit. It is also *skipped* when
  another commit is already reclaiming, which is safe for the same reason it is serialised: the vacuum
  in flight is cutting everything this one could, at a horizon no older.
- **A run of changed bytes can be wider than the bytes that changed.** The commit merges changed runs
  within 64 bytes of each other into one log record, because a record header costs more than a few
  unchanged bytes — and conflict detection reads the same runs. So two transactions writing
  *neighbouring* records can be reported as conflicting when neither touched the other's bytes. It is
  sound, it only ever costs a retry, and it is why a test that wants to prove two writers compose has
  to put their records more than a merge gap apart.

---

## 9. Test Setup

Built, in `io.metaloom.graph.core.txn`:

| Class | Cases | What it holds down |
|---|---|---|
| `VisibilityTest` | 13 | Create, update, delete and edge changes invisible to an earlier view; adjacency blocks and both indexes read as of the view; a freed **and reused** slot still resolving; read-your-writes outranking the view; thread confinement |
| `SnapshotIsolationTest` | 8 | The anomaly matrix of §3, including the read-committed anomalies actually occurring; lost update surviving four concurrent incrementers through the retry seam; and the two halves of write skew — that `SNAPSHOT` never refuses a transaction for what it *read*, and that `SERIALIZABLE` does |
| `VacuumTest` | 8 | Nothing retained with no view open; read-committed views retaining nothing; the oldest view setting the horizon; a pinned view holding what a later one does not need; the budget abandoning a view rather than serving it wrongly |
| `ConcurrentReaderTest` | 4 | Sixteen views at sixteen instants against a continuous writer; reconstruction proportional to what is read; maintenance refused while a view is open; no view left behind on a pooled thread |
| `RollbackTest` | 7 | Every mutation type rolled back to **byte-identical** segment files, with no flush; repeated rollbacks not ratcheting the allocator |
| `RollbackEqualsRecoveryTest` | 2 | The same transaction rolled back and crashed reaching the same state, plus a control proving the comparison can tell them apart. Also the regression test for the high water mark: the two have to agree down to `storageBytes`, which is what §4.1's restore rule exists for |
| `LongRunningReaderTest` | 2 | `@Tag("slow")`. A view held across 60,000 commits; and the same run against a budget too small, abandoned cleanly with the store unharmed |
| `SnapshotLinearizabilityTest` | 2 | The general form of the anomaly matrix: a view at LSN `L` must show **exactly** the committed state at `L`, whole-graph, checked against a model history keyed by commit LSN. Around 5,000 views per run |
| `NestedTransactionTest` | 5 | `begin()` refusing to nest; the open transaction surviving the refusal; writes after it still rollback-able; the write gate not leaked |
| `SerializablePhantomTest` | 9 | Write skew through predicate and point reads at `SERIALIZABLE`, with the two transactions writing disjoint kinds so only the read set can refuse either: unknown labels, keys and composite lookups, a known label, a full scan, a read from the overlay after a blind write; and the one-retry cost of create-if-absent on a new name |
| `AbandonedViewScanTest` | 3 | A view abandoned by the budget mid-scan and mid-cursor either raises or answers as of its snapshot: scheduled deterministically, and under load against a model keyed by commit LSN; and a view abandoned behind an older writer, whose commit kept no before-images, is still told |
| `ViewCatalogReadTest` | 2 | `nearest()` and `buildVectorIndex()` inside a view whose index catalog page has changed |

And in `io.metaloom.graph.core.concurrency`:

| Class | Cases | What it holds down |
|---|---|---|
| `ConcurrentWriterTest` | 4 | The four statements the feature makes, each unable to pass against a single writer lock: two transactions open at once; two writers on **one page** both surviving; two writers on **one record** ending with one refused; and a refused transaction giving its slot back. Seen to fail against the pre-concurrency diff |
| `VerifyUnderLoadTest` | 4 | Verification concurrent with writers, with a checkpointer, and with a second verifier; the throttled form making progress without hanging. The regression test for the accounting-mismatch false positive |
| `ParallelConsistencyTest` | 3 | A conserved total across ~840,000 snapshot audits while writers move it; `READ_COMMITTED` *observing* a torn total, so the weaker level is proven weaker; structural invariants checked through an open view, which `verify(FULL)` cannot do. The transfers now retry — a transfer that loses a conflict is the ordinary case — so the invariant is asserted across however many attempts it takes |
| `WriterLockLivenessTest` | 7 | The write gate released after a runtime exception, an `Error`, an unclosed transaction, a rollback, a non-retryable failure inside `inTransaction`, an interrupt, and a storm of failing writers |
| `CloseUnderLoadTest` | 2 | `close()` under load failing only in the engine's own vocabulary; `abandon()` under load left recoverable, verifying clean on reopen |
| `HammerTest` | 1 | The whole public API concurrently — both levels, cursors, traversals, retries, checkpoints, refused maintenance — with self-checksumming property values, which is the only oracle available for the optimistic read path |
| `CursorLeaseLivenessTest` | 3 | An open cursor blocking every commit, and releasing them on close; double-close safe; many short-lived cursors not wedging the store |
| `MultiStoreIsolationTest` | 2 | Four stores written concurrently with no cross-talk; a second handle on one store refused. It used to be described as "the one place two writers genuinely overlap", which is no longer true of anything |
| `AutoCommitAtomicityTest` | 1 | A single `createRelationship` writes a REL, two adjacency entries and two degree counters; no view may catch that half done |
| `SnapshotBudgetUnderLoadTest` | 1 | Six views of different ages against a budget too small: abandonment follows age, and the survivors are still *right* rather than merely alive |
| `CatalogDurabilityWindowTest` | 2 | The dictionary is forced before the commit that introduced it, so a rolled-back or crashed label outlives its transaction — pinned as harmless and reusable |
| `IsolationAnomalyStressTest` | 5 | Under load on platform and virtual threads: an on-call invariant only `SERIALIZABLE` keeps; every index equal to a full scan in every snapshot; a supernode's degree counters, adjacency, cursor and every leaf agreeing in every snapshot, and with the writers' model at the end; vector searches scoring each result as its snapshot does; monotonic reads and read-your-writes |
| `NestedApplyLatchTest` | 1 | Nested shared holds of the apply latch — a mutation's own public reads, reads inside scan callbacks, cursors inside cursors — beside committing writers, never wedging |
| `ScanWriterStarvationTest` | 1 | Commits keep at least a quarter of their solo rate beside four threads scanning and walking cursors back to back |
| `EverythingAtOnceStressTest` | 1 | `@Tag("slow")`. Transfers at both levels, churn, vectors, audits, a paced backup-shaped scan, and checkpoint, reclaim, compaction and verification, together; then the model, `verify(FULL)` and a final compaction |

And at the layers above the engine:

| Class | Module | What it holds down |
|---|---|---|
| `CypherConcurrencyTest` | `graph-cypher` | A conserved total read through Cypher while writers move it — a query is dozens of storage calls and the view is what stops it spanning two states; concurrent write queries each committing exactly once, which now means each absorbing its own conflicts rather than each being alone |
| `WireHammerTest` | `graph-server` | The same total over a real socket from many clients; clients hanging up mid-stream leaving no read view behind; no leaked event loops |

Not built, with reasons: `SnapshotTooOldTest` (folded into `VacuumTest` and `LongRunningReaderTest`,
which reach the condition through real workloads rather than by construction), jcstress harnesses
(jcstress is not in the offline repository). `ConflictTest` was listed here as impossible — "no
conflict can arise" — and conflicts are now the ordinary case; they are asserted where they occur
rather than in a test of their own, which is `SnapshotIsolationTest` for the levels,
`ParallelConsistencyTest` and `HammerTest` for the retry seam under load.

**What the concurrent-writer work found, by test:**

| Defect | Found by |
|---|---|
| A conflict raised after the metadata fold left the live count advanced over a discarded bitmap | `AutoCommitAtomicityTest`, `VerifyUnderLoadTest` — as an accounting mismatch on a healthy store |
| The allocator handing out a slot still live in the caller's own view | `VirtualThreadCallerTest` — "Double allocation of PROP segment 0 slot 0" |
| A reused slot taking its generation from the view rather than the mapping | `ParallelConsistencyTest` — a reader told its property block was stale one read later |
| A writer reading the mapping without the apply latch | `ParallelConsistencyTest` — same symptom, different cause, found after the first was fixed |
| Two vacuums cutting the same version chains at once | `ParallelConsistencyTest` — a conserved total that was not conserved |
| The high water mark restored on rollback under a stale free-ring entry | `ReferenceModelTest`, single-threaded — the same slot handed out twice |
| A retry loop spinning through all its attempts inside the winner's first commit | `MultiStoreIsolationTest` — four writers racing to create one index catalog page |
| The index catalog page cached in a field shared across threads | `MultiStoreIsolationTest` — `Stale id NULL` mid-lookup |
| A thread re-acquiring the apply latch it already held shared, queued behind a waiting commit | `EverythingAtOnceStressTest` — a hang; minimised in `NestedApplyLatchTest` |
| Scans and cursors starving every commit, the latch having no writer preference | `ScanWriterStarvationTest` — 3 commits a second instead of 127 |
| A view abandoned mid-scan reading uncut-and-untouched pages from the mapping | `AbandonedViewScanTest` — a cursor short by the edges deleted after its snapshot |
| A `SERIALIZABLE` dictionary miss recorded nowhere | `SerializablePhantomTest` — both halves of a write skew committed |
| `nearest()` reading the catalog page outside the read wrapper | `IsolationAnomalyStressTest` — `NeedsExclusionException` reaching the caller |

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
- [x] **Concurrent writers.** Writers overlap; the commit point is what is serialised, and only for
      validate, log and apply. The write path was rewritten to the extent §6.1 describes and no further
- [x] Write-write conflict detection, byte-ranged, at commit — `TransactionConflictException`
- [x] `SERIALIZABLE`, by read-set validation rather than SSI — §6.4
- [x] The metadata journal: bitmap, superblock counters and page metadata merged as operations — §6.2
- [ ] `xmin` placeholder scheme, the recent-commit map, the lazy rewrite pass — **not needed** by the
      page-level scheme, and no longer expected to be: §5.2 predicted concurrent writers would require
      it and they did not
- [ ] `UndoStore` and the 64-byte undo record — as above
- [ ] `LockTable` with eager conflict detection — **not needed**, and a deliberate choice rather than a
      gap: validation at commit costs nothing until a commit happens, where intents cost a striped table
      on every statement. §6.4 and §6.5
- [ ] SSI — it aborts *less* than the validation that is built, which is a performance question rather
      than a correctness one (§6.4)
- [ ] jcstress harnesses — jcstress is not in the offline repository

## 11. Key Classes Reference

| Class | Package | Purpose |
|-------|---------|---------|
| `TransactionManager` | `io.metaloom.graph.core.txn` | Active views, the reclamation horizon, the retention budget |
| `Snapshot` | `io.metaloom.graph.core.txn` | One view: its LSN, its visibility rule, its reconstruction cache |
| `SnapshotHolder` | `io.metaloom.graph.core.txn` | Thread-local publication of a view to the segment layer, behind a count of published views so a read made while none is open skips the thread-local |
| `PageVersions` | `io.metaloom.graph.core.txn` | Per-page before-image chains, reconstruction and reclamation |
| `Transaction` | `io.metaloom.graph.core.api` | Per-transaction write state |
| `WriteContext`, `WriteContextHolder` | `io.metaloom.graph.core.txn` | Everything one in-flight writer owns — overlay, view, journal, reservations — published to its own thread, behind the same published-count fast path as `SnapshotHolder` |
| `MetaJournal` | `io.metaloom.graph.core.txn` | Metadata changes as operations rather than bytes, folded onto the live state at commit (§6.2) |
| `PageOverlay.Page` | `io.metaloom.graph.core.page` | A private page copy **and the image it was copied from**, which is what makes two writers on one page compose (§6.1), plus the written window the commit diffs inside (§6.1.1) |
| `TransactionConflictException` | `io.metaloom.graph.core.api` | The loser of a conflict, with the page and byte range it lost on |
| `ReadTransaction` | `io.metaloom.graph.core.api` | The public view handle; `Snapshot` implements it |
| `IsolationLevel`, `SnapshotStats`, `SnapshotTooOldException` | `io.metaloom.graph.core.api` | The level, what a view costs, and what happens when it costs too much |

Not built, and named here so nobody looks for them: `LockTable`, `UndoStore`, `Vacuum` as a separate
worker (reclamation is a call at the end of commit, not a thread). Gone: the single `writerLock`,
replaced by a shared/exclusive write gate plus a commit lock; and `OverlayHolder`, replaced by
`WriteContextHolder`.

---

_Git HEAD revision: `f2b6d5f`_
_Last updated: 2026-10-24 (dictionary misses at SERIALIZABLE, mid-operation abandonment, per-thread apply hold, lease yield)_
