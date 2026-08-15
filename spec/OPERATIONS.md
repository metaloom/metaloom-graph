# Operations

How to size, tune, back up, and recover this store, and what to do when it tells you something is wrong.

This file is written for whoever is on call, not for whoever is writing the engine. Where a procedure has not been
executed, it says so — an operational runbook full of untested steps is worse than an empty one, because it invites
confidence it has not earned.

Design rationale lives in the other files; [GRAPH_STORAGE_CONTEXT.md](GRAPH_STORAGE_CONTEXT.md) routes to them.

---

## 1. What This Store Is, Operationally

| | |
|---|---|
| **Deployment** | Embedded in a JVM process is the primary shape. `graph-server` also puts two ports over a **root of databases**: HTTP on 8080 (browser UI, `/api/query`, whole results as JSON) and a binary streaming port on loopback:8081 ([WIRE.md](WIRE.md)). **Neither is authenticated** — see §9. |
| **Databases** | One directory per database under the root, each a complete independent store ([DATABASES.md](DATABASES.md)). Sizing below is **per database**. |
| **Concurrency** | One writer process, enforced by a `LOCK` file. One writer *thread*, enforced by a lock. Readers are unlimited and scale (measured to 10x at 32 threads). |
| **Durability** | A commit is on the device when the call returns, under the default sync mode. |
| **Failure domain** | The store directory. There is no replication, no failover and no backup mechanism inside the engine. |
| **Biggest operational gap** | **No online backup.** See §5. |

---

## 2. Sizing

### 2.1 Disk

Space is dominated by three things, in this order:

| Component | Cost |
|-----------|------|
| Node with two small properties | 128 bytes |
| Secondary index entries for the same node | about 133 bytes |
| Directed adjacency entry | 1.0 to 1.2 bytes |
| Relationship record (only for edges with properties) | 64 bytes |
| Property value over 255 bytes | one 8 KiB blob extent per 8160 bytes |

A hundred million nodes with two properties each and an average degree of ten is roughly `100e6 * (128 + 133) + 1e9 * 1.1`
= **27 GB**, plus the log.

Two multipliers to plan for:

- **Segment metadata is sized for the segment, not for its contents.** Each segment file carries a free slot bitmap
  and a page metadata array covering its whole slot capacity. With the 4 GiB default that is about 6 MB per segment
  file regardless of how much is in it. A store with a handful of nodes still costs tens of megabytes of *nominal*
  size — though the files are sparse, so most of it is never allocated on disk.
- **The write-ahead log** is preallocated in 64 MiB segments and trimmed at each checkpoint. Budget two segments plus
  whatever your checkpoint interval lets accumulate.

`store.stats().storageBytes()` is the engine's own accounting: per segment, the metadata regions plus everything below
the high water mark, plus the dictionaries. It is not what `du` reports, because the files are sparse and holes have
been punched in them. Use `du --apparent-size=never` for the real figure.

### 2.2 Memory

- Every segment file is memory-mapped in full. That is address space, not resident memory; the resident set is
  whatever the page cache decides to keep.
- **A transaction is bounded by heap.** The engine is no-steal: a writer holds a private copy of every page it
  touches until commit. A transaction that touches a gigabyte of pages needs a gigabyte of heap. This is the single
  most important sizing constraint and the one most likely to be hit by a bulk load. Split large loads into batches.
- Compaction obeys the same bound, via `maxRelocationsPerTransaction` (default 4096).

### 2.3 Page size

Fixed at store creation, recorded in the manifest, and not changeable without a full rewrite. 8 KiB is the default.
4 KiB suits random-heavy point lookups; 16 KiB suits scan-heavy traversal.

---

## 3. Tuning

| Setting | Default | Raise it when | Lower it when |
|---------|---------|---------------|---------------|
| `walSyncMode` | `FSYNC_ON_COMMIT` | — | Throughput matters more than the last few milliseconds of commits: `GROUP_COMMIT` trades a bounded window for roughly 20x |
| `walSyncIntervalMillis` | 20 | You can afford to lose more | You can afford to lose less |
| `segmentCapacity` | 4 GiB | — | Many small stores, where per-segment metadata dominates |
| `maxRelocationsPerTransaction` | 4096 | Compaction is too slow and you have heap | Compaction stalls foreground writers |
| `pauseBetweenBatchesMillis` | 0 | Compaction is interfering with foreground work | — |

**The one tuning decision that matters most is not a setting.** Durable commits run at roughly 750 to 1000 per second,
because each one is an fsync. Batching a load into a single transaction is nearly three orders of magnitude faster.
If ingest is slow, the answer is almost always transaction size, not configuration.

---

## 4. Monitoring

`store.metrics()` returns a `StoreMetrics` snapshot. Subtract two snapshots to cost an interval.

| Signal | Healthy | Investigate when |
|--------|---------|------------------|
| `walLagBytes` | 0 under `FSYNC_ON_COMMIT` | Nonzero under `FSYNC_ON_COMMIT` — that would mean a commit returned without an fsync |
| `walBytesSinceCheckpoint` | Below a few hundred MB | Growing without bound: nothing is checkpointing, and recovery time grows with it |
| `unflushedPages` | Small and oscillating | Monotonically rising: writes are outrunning the flush |
| `averageCheckpointMillis` | Tens of milliseconds | Seconds: the store has grown past what a checkpoint can force quickly |
| `io.walSyncs` per commit | 1 | Much more than 1: something is forcing more often than it needs to |
| `io.recordReads(NODE)` per operation | 1 for a read by id | More: something has been added to the read path. `ReadBudgetTest` gates this |
| `writeAmplification` | 2.0 to 2.5 | Above 3: the log is writing far more than the payload |
| Liveness (`CompactionReport`) | Above 0.5 | Below 0.5 and staying there: compaction is not keeping up |

Run `store.verify(VerifyLevel.FULL, VerifyOptions.throttled())` on a schedule. Throttled, it drops the read latch
every 4096 elements, so it does not stall a live store. It is the only thing that will tell you about a structural
problem before a query does.

---

## 5. Backup and Restore

**The engine has no backup mechanism.** This is the largest operational gap and it is stated plainly rather than
worked around.

What works today:

**Cold copy.** Close the store, copy the directory, reopen. Correct, complete, and requires downtime.

```bash
# with the writing process stopped
cp -a --sparse=always /data/mygraph /backup/mygraph-$(date +%Y%m%dT%H%M%S)
```

`--sparse=always` matters: hole punching leaves large sparse regions and a naive copy materialises them.

**Restore** is the reverse: stop the process, replace the directory, start. Delete the `LOCK` file only if you are
certain no process is using it.

What does **not** work:

- **Copying a live store is not safe.** There is no snapshot isolation and no way to quiesce writers from outside the
  process. A copy taken during a write can catch a half-applied commit. The `snapshot()` API in
  [API.md](API.md) is designed but not implemented.
- **Copying only the segment files is not enough.** The `MANIFEST`, the `dict/` directory and the `wal/` directory are
  all part of the store. The dictionary in particular maps the label and key ids embedded in every record; without it
  the records are unreadable.
- There is **no point-in-time recovery** and **no incremental backup**. The log is trimmed at each checkpoint, so it
  cannot be replayed against an older copy.

If you need any of this, put the system of record somewhere else and treat this store as a rebuildable index. That is
the recommendation in [reports/PHASE_STATUS.md](reports/PHASE_STATUS.md) §5 and it has not changed.

---

## 6. Disaster Recovery

### 6.1 The process died

Reopen it. Recovery is automatic: the log is replayed at open, and every commit that returned to the caller is
present. This is tested by `RecoveryTest`, `CompactionCrashTest`, and the `kill -9` loop below.

Recovery time is proportional to `walBytesSinceCheckpoint`. If it is slow, checkpoint more often.

### 6.2 The store will not open

| Message | Meaning | What to do |
|---------|---------|------------|
| `Store at ... is locked by another process` | Another JVM holds the `LOCK` file | Find it. Do not delete the lock while it runs |
| `Store at ... is already open in this JVM` | The same process opened it twice | A bug in the calling code |
| `Both superblock copies of X segment N are invalid` | A segment header is destroyed. The A/B pair means a torn write cannot do this; this is real damage | Restore from backup. The segment is unreadable |
| `Segment X-N has <field> A but this store expects B` | Geometry mismatch: the store was created with different options | Open it with the options it was created with. Page size and segment capacity are not changeable |
| `Segment format version N, expected M` | Written by a different version of the engine | Use that version. There is no in-place upgrade |
| `Recovery references X segment N, which does not exist` | The log names a segment file that is gone | The store directory is incomplete. Restore it whole |

### 6.3 The store opens but `verify` fails

`verify(FULL)` reports **every** violation, not the first, because the pattern is more diagnostic than any single
instance. Read the whole list before acting.

| Violation | Likely cause |
|-----------|--------------|
| `Checksum mismatch in ... page N` | Media damage or a write that did not land. The page contents are not trustworthy |
| `... has degree_out N but M outgoing entries` | A counter and the adjacency disagree. Usually a bug, not media damage |
| `expects N mirror entries on ...` | One direction of an edge is missing |
| `... is orphaned` | Leaked space. Not a correctness problem; the elements are fine and the space is lost |
| `Property block ... names a different owner` | A back-pointer is wrong. Compaction relies on these |
| `Adjacency block ... does not terminate` | A cyclic chain pointer, which the read paths now reject rather than following forever |

There is **no repair tool**. Orphans are harmless and can be left; anything else means restoring from backup.

### 6.4 Running out of space

`ENOSPC` during a commit surfaces as an `IOException` from `commit()`. The transaction does not apply — the log write
fails before any data file is touched — so the store stays consistent. Free space and retry.

To give space back, in increasing order of cost:

1. `store.checkpoint()` — trims the log.
2. `store.reclaim()` — punches all-free pages. Moves nothing, breaks no ids.
3. `store.compact()` — relocates records to close holes inside pages. **This invalidates ids.**

---

## 7. The `kill -9` Loop

```bash
mvn -o test-compile
tools/kill9-loop.sh 20 3      # 20 iterations, 3 seconds of load each
```

The workload records every id the store acknowledged as committed into a separately fsynced file, and the checker
asserts each one is present with the right value afterwards. Without that file the test could not tell *losing a
commit* from *never having made one*, which is the only failure worth catching.

Some commits are always present but unacknowledged — the process died between the commit returning and the ack being
written. That window is expected and the checker reports its size rather than failing on it.

**Executed here: 8 iterations, clean.** The specification asks for nightly runs over a week; that has not been done,
and there is no CI schedule running it.

---

## 8. The Manual Power-Loss Test

This cannot be automated and **has not been performed**. It is the only test that validates the assumption every
other durability claim rests on: that `fsync` means what the device says it means.

Procedure, to be run before any release that claims durability:

1. **Prepare the machine.** A physical machine, or a VM whose host passes flushes through. Confirm the disk write
   cache is either disabled or honestly flushed:
   ```bash
   hdparm -W /dev/nvme0n1          # write cache state
   cat /sys/block/nvme0n1/queue/write_cache
   ```
   Record the answer. A device that lies about flushes will pass every other test in this repository and lose data.
2. **Confirm the filesystem does not lie either.** `ext4` with `barrier=1` (the default); note the mount options.
3. **Start the workload**, writing to a store on that device:
   ```bash
   java -cp ... io.metaloom.graph.core.crash.CrashWorkload write /data/powerloss 600
   ```
4. **Wait for at least one checkpoint**, then **cut the power at the wall.** Not a shutdown, not a reset button —
   the point is to remove power from the device with its cache populated.
5. **Reboot and check:**
   ```bash
   java -cp ... io.metaloom.graph.core.crash.CrashWorkload check /data/powerloss
   ```
6. **Record**: the device, its cache setting, the filesystem and mount options, the number of acknowledged commits,
   and the result. Repeat at least five times.

The post-condition is the same as every other crash test: the store opens, `verify(FULL)` passes, and every
acknowledged commit is present.

---

## 9. Security Posture

**A store directory is untrusted input.** The format parsers are hardened against a corrupt or hostile file:
`HostileInputTest` asserts that reading one either returns correct data or throws one of the engine's own exceptions,
never reads outside the mapping, never allocates from an unvalidated length field, and always terminates. Seven
defects of exactly those kinds were found and fixed writing those tests.

What that does **not** cover:

- **There is no authentication or authorisation.** Anything that can open the directory has full access.
- **There is no encryption**, at rest or otherwise.
- **A malicious store can still make you do work.** Bounds are enforced, but a store can legitimately declare a large
  blob and make you read it. Do not open untrusted stores in a process that cannot afford the time.
- **The dictionary files are not checksummed.** A corrupt dictionary produces wrong labels, not a detected error.

### 9.1 The network, which has the same posture and a wider blast radius

`graph-server` opens two ports over one store, and **neither has authentication or transport security**:

| | | |
|---|---|---|
| HTTP | 8080, **all interfaces** | The browser UI and `/api/query`. Predates the note below, which is why it binds everything |
| Binary | 8081, **loopback only** | Streaming Cypher ([WIRE.md](WIRE.md)). Loopback because a new unauthenticated port should not default to every interface |

Both are **read-only**: the query language has no write clauses, so neither port can modify a store however it is
addressed. Both can read **every byte** of one — and of **every database under the root**, because there is no
per-database authentication either. Treat exposing either as equivalent to handing out the root directory, because that
is what it is.

The one exception to read-only is `DELETE /api/databases/{name}`, which deletes a database and everything in it. It
returns 403 unless the server was started with `--allow-drop`, and off is the default: it is the only route that
destroys data, and it sits beside a query language that cannot delete a single node.

- `--no-wire` turns the binary port off; `--wire-port=<n>` moves it. There is no flag to bind the binary port to
  another interface, deliberately — putting it somewhere reachable should require thinking about what is in front of
  it.
- **A frame arriving on the binary port is untrusted input on the same terms a store directory is**, and is hardened
  on the same terms: a non-differential oracle (`WireDecoderHostileInputTest`) asserts that decoding either returns a
  value or throws the protocol's own exception, never runs out of memory on a length field, never overflows the
  stack, and allocates in proportion to its input. It found three real defects the first time it ran.
- **A single client can occupy the server without authenticating.** `maxConcurrentStreams` (default 8) is the only
  rate limit there is; past it queries are refused rather than queued. A deadline and a retention watchdog bound how
  long any one of them can hold a read view — see [WIRE.md §4](WIRE.md).

---

## 10. What Is Not Here

Stated plainly, because an operations document that implies capabilities the system lacks is a liability:

- No online backup, no incremental backup, no point-in-time recovery (§5).
- No repair tool for a store that fails `verify` (§6.3).
- No metrics exporter. `StoreMetrics` is a Java object; wiring it to anything is the embedder's job.
- No background maintenance. Checkpoint, reclaim and compact are calls the embedder has to schedule.
- No multi-writer concurrency, no replication, no failover.
- The power-loss procedure in §8 is written and untested.

---

_Git HEAD revision: `c016a41` (plus the Phase 0 to 9 working tree)_
_Last updated: 2026-08-13_
