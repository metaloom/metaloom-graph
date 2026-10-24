# Operations

How to size, tune, back up, and recover this store, and what to do when it tells you something is wrong.

This file is written for whoever is on call, not for whoever is writing the engine. Where a procedure has not been
executed, it says so — an operational runbook full of untested steps is worse than an empty one, because it invites
confidence it has not earned.

Design rationale lives in the other files; [CONTEXT.md](CONTEXT.md) routes to them.

---

## 1. What This Store Is, Operationally

| | |
|---|---|
| **Deployment** | Embedded in a JVM process is the primary shape. `graph-server` also puts up to five ports over a **root of databases**: HTTP on 8080 (browser UI, `/api/query`, whole results as JSON), a binary streaming port on loopback:8081 ([WIRE.md](WIRE.md)), **the same port over TLS on 8084 once a certificate is given**, monitoring on 8082 ([METRICS.md](METRICS.md)) and MCP on loopback:8083, off by default ([MCP.md](MCP.md)). **All but the monitoring port require a login; that one deliberately does not** — see §9 and [AUTH.md](AUTH.md). |
| **Databases** | One directory per database under the root, each a complete independent store ([DATABASES.md](DATABASES.md)). Sizing below is **per database**. |
| **Concurrency** | One writer process, enforced by a `LOCK` file. One writer *thread*, enforced by a lock. Readers are unlimited and scale (measured to 10x at 32 threads). |
| **Durability** | A commit is on the device when the call returns, under the default sync mode. |
| **Failure domain** | The store directory. There is no replication and no failover. Backup is `metaloom-graph-cli`, outside the engine — [BACKUP.md](BACKUP.md). |
| **Biggest operational gap** | **No transport security on the HTTP and MCP ports.** The binary port encrypts itself with `--wire-tls-cert` ([WIRE.md §7.1](WIRE.md)); the other two are HTTP and need a proxy in front of them, or a password crosses in cleartext. §9.1 |

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

### 3.1 Configuring `graph-server`

Three sources, and **a command line flag beats an environment variable beats a default**. The flag wins so that a shell
which exported `GRAPH_ALLOW_WRITE` for one server cannot silently change what a second one started in the same shell
does. The environment exists because a container image cannot be reconfigured without rebuilding its `CMD`, and a Helm
chart has environment variables and not an argument vector.

`GRAPH_ROOT` is the one that matters structurally: it is what makes the positional root argument optional, and therefore
what lets the image's `CMD` carry no arguments at all.

| Variable | Flag | Default |
|----------|------|---------|
| `GRAPH_ROOT` | *(first positional)* | — required |
| `GRAPH_HTTP_PORT` | *(second positional)* | 8080 |
| `GRAPH_DATABASE` | `--database=` | `default` |
| `GRAPH_ALLOW_WRITE` | `--allow-write` | false |
| `GRAPH_ALLOW_DROP` | `--allow-drop` | false |
| `GRAPH_ALLOW_BACKUP` | `--allow-backup` | **false** — see §5 |
| `GRAPH_ALLOW_LOAD_CSV` | `--allow-load-csv` | **false** — see §9.1 |
| `GRAPH_IMPORT_DIR` | `--import-dir=` | none; required by the flag above |
| `GRAPH_DEMO` | `--demo` | false |
| `GRAPH_DEMO_SIZE` | `--demo-size=` | small |
| `GRAPH_QUERY_TIMEOUT_MILLIS` | `--query-timeout=` | 30000 |
| `GRAPH_SLOW_QUERY_MILLIS` | `--slow-query-millis=` | 1000 — see §4.2 |
| `GRAPH_WIRE_ENABLED` | `--no-wire` | true |
| `GRAPH_WIRE_PORT` | `--wire-port=` | 8081 |
| `GRAPH_WIRE_HOST` | `--wire-host=` | 127.0.0.1 |
| `GRAPH_WIRE_TLS_CERT` | `--wire-tls-cert=` | none; **no TLS without it** — see §9.1 |
| `GRAPH_WIRE_TLS_KEY` | `--wire-tls-key=` | none; required by the one above |
| `GRAPH_WIRE_TLS_PORT` | `--wire-tls-port=` | 8084 |
| `GRAPH_WIRE_TLS_HOST` | `--wire-tls-host=` | whatever `GRAPH_WIRE_HOST` is |
| `GRAPH_WIRE_TLS_ONLY` | `--wire-tls-only` | false — the cleartext port stays open |
| `GRAPH_MAX_STREAMS` | `--max-streams=` | 8 |
| `GRAPH_METRICS_ENABLED` | `--no-metrics` | true |
| `GRAPH_METRICS_PORT` | `--metrics-port=` | 8082 |
| `GRAPH_MCP_ENABLED` | `--mcp` | **false** |
| `GRAPH_MCP_PORT` | `--mcp-port=` | 8083 |
| `GRAPH_MCP_HOST` | `--mcp-host=` | 127.0.0.1 |

The list is declared once, in `GraphServerOptions.VARIABLES`, and both the reading and the `--help` output are generated
from it. A variable that is read and not documented, or documented and not read, is the failure that shape rules out;
`GraphServerOptionsTest` asserts both directions.

Three behaviours that are decisions rather than accidents:

- **An empty variable is unset, not empty.** `GRAPH_DATABASE=""` is exactly how a Helm template renders a value nobody
  set, and treating it as a real setting would turn an omitted chart value into an empty database name — refused, at
  start-up, in production.
- **A boolean typo is refused.** `Boolean.parseBoolean` maps every typo to `false`, so `GRAPH_ALLOW_WRITE=ture` would
  give a read-only server and no reason for it. `true/false`, `1/0`, `yes/no` and `on/off` are accepted; anything else
  is a start-up error naming the variable.
- **Two fixed ports on one number are refused at parse time**, across all five listeners. Otherwise it surfaces as
  "address already in use" from whichever bound second, which names the port but not the two settings that chose it.
  Port `0` means "any free one" and is excluded, as is a disabled front end. Note that the monitoring port defaults to
  8082, so serving HTTP on 8082 is now a start-up error rather than a race.

**`--allow-load-csv` and `--import-dir` are refused apart**, in both directions and at parse time. The flag without a
directory would accept every load and then refuse it, which reads as a bug in the queries; a directory without the flag
reads as enabled and is not, which is the more dangerous of the two because it is the one an operator believes.

**`--wire-tls-cert` and `--wire-tls-key` are refused apart** for the same reason and a sharper one: an operator who set
one of two variables and got a server that quietly stayed in cleartext has the worst outcome available, because they
tried and they believe it worked. `--wire-tls-only` without a certificate is refused too, naming `--no-wire` — closing
the binary port entirely is a thing somebody might mean, and a server with no binary listener at all is not.

**`GRAPH_WIRE_HOST` deserves its own line.** The binary port binds loopback by default, which inside a pod means it is
unreachable from outside that pod. That is the correct default and the reason the chart has to widen it deliberately:
defaulting it to `0.0.0.0` in the image would silently undo a security decision for everyone who ever runs the image.

---

## 4. Monitoring

`store.metrics()` returns a `StoreMetrics` snapshot. Subtract two snapshots to cost an interval.

| Signal | Healthy | Investigate when |
|--------|---------|------------------|
| `walLagBytes` | 0 under `FSYNC_ON_COMMIT`; up to an interval's worth of log under `GROUP_COMMIT`; grows until the next checkpoint under `ASYNC` | Nonzero under `FSYNC_ON_COMMIT` — that would mean a commit returned without an fsync. Under the other modes it is what a power cut would lose ([WAL.md §4](WAL.md#4-group-commit)) |
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

### 4.1 Prometheus

Every signal in the table above is also a meter on the monitoring port, so those thresholds can be alert rules rather
than something a human reads during an incident. [METRICS.md](METRICS.md) is the catalogue, and its §6 maps each row
above to its meter name.

```bash
curl localhost:8082/metrics        # the scrape
curl localhost:8082/health         # liveness  — 200 while the process is up
curl localhost:8082/ready          # readiness — 503 once shutdown has begun
```

Two things worth knowing before you read a dashboard:

- **The size gauges are up to 15 seconds old**, and publish their own age as `graph_store_stats_age_seconds`.
  `store.stats()` lists a directory and stats every file in it, and a Prometheus scrape collects gauges on an event
  loop; doing that per series per scrape would make the server slower the more closely it is watched. Everything else
  — the log, the readers, the I/O totals — is read live.
- **`/health` and `/ready` are on the monitoring port and not the query port**, so that a query load heavy enough to
  saturate HTTP cannot make a liveness probe time out and get a merely-busy server killed.

### 4.2 The slow query log

The histogram says how often and how bad. It cannot say *which query* — a query string cannot be a Prometheus label
without producing one time series per distinct query. That is what this is for, and the two are meant to be read
together.

`--slow-query-millis=<ms>` / `GRAPH_SLOW_QUERY_MILLIS`, default **1000**, `0` to log nothing. A query at or over the
threshold produces one `WARN` line and increments `graph_queries_slow`:

```
Slow query: 1420 ms, 12 rows, outcome ok, http front end, database 'default', params [city, since] — MATCH (p:Person)...
```

Four decisions in that line:

- **Parameter values are never logged.** `MATCH (u:User {token: $t})` puts the secret in the parameter map rather than
  in the text, and this is the file most likely to be shipped whole to an aggregator that a different set of people can
  read. The parameter *names* are logged, because they are schema rather than data.
- **The text is collapsed to one line and truncated at 500 characters**, so a generated query cannot fill a disk.
- **Failed and abandoned queries are logged too**, with their outcome. A query that hit its timeout is the most
  interesting line in this log: it cost the most and returned nothing.
- **Set it below `--query-timeout`**, or nothing can ever be logged — the query is abandoned first. The server prints a
  warning at start-up if the two are configured that way, rather than refusing, because `--query-timeout=0` is legal.

It applies to every front end, tagged `http`, `wire` or `mcp`.

---

## 5. Backup and Restore

**There is an online logical backup.** `metaloom-graph-cli` writes a database out as a portable dump while it is being
used: writers are not stopped, and the dump is a single point in time. The design is [BACKUP.md](BACKUP.md); this is
the procedure.

```bash
# Against a running server — needs --allow-backup on it
metaloom-graph-cli database backup mydb --server=http://localhost:8080 --out=mydb-$(date +%Y%m%dT%H%M%S).dump

# Or directly, with the server stopped
metaloom-graph-cli database backup mydb --path=/data/graphs --out=mydb.dump

# Restore. Offline only, and it creates the target from the dump's own geometry
metaloom-graph-cli database restore mydb --path=/data/graphs --in=mydb.dump --force

# What is in a dump, without opening a store
metaloom-graph-cli database inspect --in=mydb.dump
```

**In a container it is already there**, as `metaloom-graph-cli` on the `PATH` of both images — including the native
one, which has no JVM and therefore no other way to do this. `GRAPH_ROOT=/data` is set in the image and read by both
programs, so use `--server` while the server is running: a bare `--path` opens the store the server has exclusively
locked and is refused. [DEPLOYMENT.md §2.3](DEPLOYMENT.md) has both directions spelled out.

**`--force` is about data, not existence.** A restore replaces a database whole, so it refuses one that holds
anything unless told twice — but a target that is merely *there* and empty is replaced without a flag. That matters
because opening a root that does not exist creates an empty `default` inside it, which is exactly the state a machine
is in when a dump is being put onto it. The replacement is built beside the target and swapped in only after the dump
has been read to its end and verified, so a truncated or corrupt dump leaves the original untouched even with
`--force`. Against a server with authentication on, `--server` needs `GRAPH_TOKEN` (a token from `POST /api/login`).

**What it costs while it runs.** Writers wait one batch at a time rather than for the whole walk; `reclaim()` and
`compact()` are refused for the duration, because the backup holds a read view; and retention grows with write volume ×
backup duration. Past `retainedVersionBytes` (256 MiB) the engine abandons the oldest view — usually the backup's — and
the CLI reports that with what to do about it. On a store under heavy write load, back up offline or raise the budget.

**Element ids are not preserved.** A restored node has the same labels, properties and relationships and a different
id. Anything outside the store that recorded an id is stale after a restore. That is the price of a portable dump; a
physical copy would have preserved them and is not built.

**Cold copy still works**, and is the only thing that preserves ids and index structures byte for byte:

```bash
# with the writing process stopped
cp -a --sparse=always /data/mygraph /backup/mygraph-$(date +%Y%m%dT%H%M%S)
```

`--sparse=always` matters: hole punching leaves large sparse regions and a naive copy materialises them. Restore is the
reverse — stop the process, replace the directory, start. Delete the `LOCK` file only if you are certain no process is
using it.

What still does **not** work:

- **Copying a live store is not safe.** There is no filesystem-level snapshot and no way to quiesce writers from
  outside the process. A copy taken during a write can catch a half-applied commit. Use the logical backup above, which
  gets its atomicity from the engine's MVCC rather than from the filesystem.
- **Copying only the segment files is not enough.** The `MANIFEST`, the `dict/` directory and the `wal/` directory are
  all part of the store. The dictionary in particular maps the label and key ids embedded in every record; without it
  the records are unreadable.
- There is **no point-in-time recovery** and **no incremental backup**. The log is trimmed at each checkpoint, so it
  cannot be replayed against an older copy or an older dump.

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
| `WAL segment .../wal-....log is missing: recovery starts at checkpoint LSN ...` | A log segment between the checkpoint and the newest segment is gone — a partial copy, or a manifest older than the trim that removed it | Restore the missing segment, or the store from a backup. Opening anyway would recover to a wrong point |

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
fails before any data file is touched — so the store stays consistent. **Any** failure of the log (a failed write, a
failed fsync, `ENOSPC`, `EIO`) also stops the store taking commits: every later commit and `checkpoint()` fails with
`The write-ahead log failed earlier ...` until the store is closed and reopened. Free space, close the store (the close
reports the failure too), and reopen it; recovery decides from the log whether the failed transaction is there. A
failed fsync cannot be retried safely, because Linux reports a failed writeback once and then forgets it
([WAL.md §5.1](WAL.md#51-a-log-failure-is-permanent-until-reopen)).

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

## 8.9 Shutting down

`SIGTERM`, or a `close()`, runs an ordered shutdown rather than stopping where it is:

1. new queries are refused — **503** on HTTP, `OVERLOADED` on the wire — so the set of in-flight ones
   can only shrink;
2. the listeners close;
3. running queries are given **5 seconds** to finish;
4. whatever is left is **abandoned** through the same abort signal a query deadline uses, so each one
   unwinds, closes its own read view or rolls back its own transaction, and tells its client why;
5. two more seconds for those to unwind, and then the stores are closed — checkpointed, log trimmed,
   `LOCK` released.

**The order is a safety property, not politeness.** Segment files are memory-mapped, so closing a store
unmaps them; a worker still inside a query at that instant is reading an address the process no longer
owns. Refusing, draining and aborting is what makes that impossible.

**Step 4 is why a shutdown always completes.** A query with `--query-timeout=0`, or one whose client
stopped reading, would otherwise hold the process open indefinitely — and a shutdown that does not
finish is indistinguishable from a hang, which is what gets a server `kill -9`'d and loses the clean
close entirely. `graph-server-shutdown` is the hook's thread name, so a thread dump during a slow one
says what is holding it.

**Durability does not depend on any of this.** The log is fsynced before any data file is touched, so a
write interrupted at any instant — graceful, killed, or power-cut — either committed or did not. What a
clean shutdown buys is a checkpoint written and the log trimmed, so the next start does less recovery
work, and clients told what happened instead of a socket closing under them.

`GraphStore.close()` takes the writer lock, so it can never run beside a half-applied commit even when
a caller closes a store directly.

---

## 9. Security Posture

**A store directory is untrusted input.** The format parsers are hardened against a corrupt or hostile file:
`HostileInputTest` asserts that reading one either returns correct data or throws one of the engine's own exceptions,
never reads outside the mapping, never allocates from an unvalidated length field, and always terminates. Seven
defects of exactly those kinds were found and fixed writing those tests.

What that does **not** cover:

- **There is no authentication or authorisation *at the directory level*.** Anything that can open the directory has
  full access, and that has not changed: the identity in [AUTH.md](AUTH.md) is enforced by the servers, on the ports,
  not by the storage engine. An embedded caller holding a `GraphStore` is unaffected — the same line
  `--allow-write` has always drawn.
- **There is no encryption at rest.** In transit, the binary port can encrypt itself — §9.1 — and the store's own
  files never are.
- **A malicious store can still make you do work.** Bounds are enforced, but a store can legitimately declare a large
  blob and make you read it. Do not open untrusted stores in a process that cannot afford the time.
- **The dictionary files are not checksummed.** A corrupt dictionary produces wrong labels, not a detected error.

### 9.1 The network, which has the same posture and a wider blast radius

`graph-server` opens up to five ports over one root. Four of them require a login unless `--no-auth` was given; **one
of them can encrypt itself**:

| | | Authenticated | Encrypted |
|---|---|---|---|
| HTTP | 8080, **all interfaces** | **Yes.** Session cookie or `Authorization: Bearer`; the browser UI and `/api/query`. `/api/login`, `/api/logout`, `/api/whoami` and the static bundle are outside the filter, the last because the login page is part of it | **No.** Put an ingress or a reverse proxy in front of it |
| Binary | 8081, **loopback only** | **Yes.** An `AUTH` frame during the handshake ([WIRE.md](WIRE.md)) | **No.** Still loopback by default: authentication is not transport security |
| Binary over TLS | 8084, **off until a certificate is given**, then the same address as 8081 | **Yes**, the same way | **Yes.** `--wire-tls-cert` / `--wire-tls-key`, PEM. A second listener over the same server, not a mode on the first — [WIRE.md §7.1](WIRE.md) |
| Monitoring | 8082, **all interfaces**, on by default | **No, deliberately.** `/metrics`, `/health`, `/ready` ([METRICS.md](METRICS.md)) — a kubelet probe and a Prometheus scrape carry no credential, and a probe failing over a misconfigured secret is a restart loop. **Do not publish it**: a scrape carries database names, node counts and store sizes | No |
| MCP | 8083, **loopback only**, **off by default** | **Yes.** `Authorization: Bearer` on all three transports ([MCP.md](MCP.md)). Still off by default: it is a remote code path an LLM drives, and a credential is not a reason to widen that | **No.** Same remedy as HTTP |

`GRAPH_WIRE_HOST` and `GRAPH_MCP_HOST` widen the two loopback ports, deliberately and one at a time. In a pod the
loopback default means those ports are unreachable from outside the pod, which is still the correct starting position:
a password on a cleartext port is only as private as the network under it.

**Why only the binary port terminates TLS itself.** The other two are HTTP, and HTTP already has a TLS story — an
ingress, a service mesh or a reverse proxy, with provisioning and renewal built into the platform. The binary port is
the one thing that story cannot reach: it is a raw TCP protocol, and nothing in front of it can terminate a connection
it does not understand. So the encryption lands where there was no alternative, and does not duplicate what a platform
already does better everywhere else.

**Adding a certificate does not close the cleartext port.** Both listen until `--wire-tls-only`, which is what makes
the move a migration rather than a flag day — and while both are open the server logs a warning naming the plaintext
one at startup. `graph_wire_connections{tls="false"}` is how you tell whether anything is still using it; until that
rate is zero, `--wire-tls-only` breaks somebody. **Neither replaces the other**: a password on a cleartext port is
readable by anything on the path, and an encrypted port with no login is readable by anyone who can reach it.

**A grant is now the per-database boundary** that §6 of [DATABASES.md](DATABASES.md) said could not exist. A principal
granted `MATCH ON sales` reads `sales` and is refused `default`, with a message that does not say whether `default`
exists. What has not changed is that the **flags bound the deployment**: a request needs the privilege *and* the flag,
so enabling authentication can never widen what a server started read-only is able to do — [AUTH.md §5](AUTH.md).

**A backup hands over everything, and that is off by default too.** `--allow-backup` serves
`GET /api/databases/{name}/backup`, which streams a whole database in one request. It is a read, so `--allow-write`
does not cover it — and it is the read that hands over every byte, so it does not come with `--allow-write` either.
Treat enabling it as equivalent to publishing the store directory, because that is what it is.

**The query language can read files, and that is off by default and is a separate decision.** `--allow-load-csv`
enables `LOAD CSV`, and `--import-dir=<path>` is the whole of what it may then read. It is **not** covered by
`--allow-write`, because `LOAD CSV … RETURN row` writes nothing — a server left deliberately read-only would otherwise
have read files off its own disk for anything that could reach the port, and returned them as query results. Inside the
import directory, `file:///x.csv` means `<import-dir>/x.csv`; traversal, absolute paths and symlinks pointing out are
refused, `http://` is refused by name so that the clause cannot be used to make the server fetch an address of the
caller's choosing, and a refusal never says whether the file exists. It is refused unconditionally on the MCP port.
[CYPHER.md §5.6](CYPHER.md) has the design; `SandboxedFileCsvSourceTest` is the test.

**The query language can write, and that is off by default.** `--allow-write` enables `CREATE`, `SET`, `REMOVE` and
`DELETE` on both ports at once; without it a write query is refused before it reaches a worker thread — 403 over HTTP,
`READ_ONLY` on the wire. **The flag bounds what a peer can do, not who the peer is**; it is a posture, not access
control. Embedded Java callers are unaffected either way.

**Who the peer is now has an answer, and it does not replace the flag.** [AUTH.md](AUTH.md) adds users, roles and
per-database grants, and the two compose as an **AND**: a write needs `CREATE` (or `MERGE`, or `SET`…) *and*
`--allow-write`. The flag can only subtract. That ordering is deliberate — if a grant could override it, then switching
authentication on would turn a deliberately read-only deployment into a writable one, which is the one failure mode the
design exists to make impossible. It also means the two refusals are different and say so: `readonly` names the flag
and advises a restart, `forbidden` names the missing privilege and advises an administrator.

`DELETE /api/databases/{name}` has its **own** flag, `--allow-drop`, also off by default. It stays separate from
`--allow-write` because it destroys a whole database and its directory in one request — including databases no query
addressed — and enabling writes is not asking for that.

Two consequences of enabling writes that are easy to miss:

- **Writers run concurrently, and may be refused rather than made to wait.** A long write query no longer blocks
  every other writer in the process; instead two writers that touch the same records collide, and one of them is
  retried. Cypher write queries absorb their own conflicts — up to a bounded number of attempts — so what a client
  sees under contention is latency rather than an error, until the bound is reached. Writers do still wait for
  maintenance: `verify`, `checkpoint`, `reclaim`, compaction and `close` exclude them outright. The binary port bounds
  that wait and reports `OVERLOADED` rather than hanging; the HTTP route does not, beyond its query deadline.
- **A write query is materialised and commits before its first row is sent.** Peak memory for a write is the whole
  result, not one batch, whichever port it arrived on.

- `--no-wire` turns the binary port off; `--wire-port=<n>` moves it. There is no flag to bind the binary port to
  another interface, deliberately — putting it somewhere reachable should require thinking about what is in front of
  it.
- **A frame arriving on the binary port is untrusted input on the same terms a store directory is**, and is hardened
  on the same terms: a non-differential oracle (`WireDecoderHostileInputTest`) asserts that decoding either returns a
  value or throws the protocol's own exception, never runs out of memory on a length field, never overflows the
  stack, and allocates in proportion to its input. It found three real defects the first time it ran.
- **A single client can occupy the server, though it has to log in first now.** `maxConcurrentStreams` (default 8) is
  the only rate limit on queries; past it they are refused rather than queued. A deadline and a retention watchdog
  bound how long any one of them can hold a read view — see [WIRE.md §4](WIRE.md).
- **Logging in is itself rate limited, and has to be.** Verifying a password is ~100 ms of PBKDF2 that an
  *unauthenticated* caller can ask for, so the throttle refuses **before** the derivation runs — five failures per user
  name and per source address inside fifteen minutes, then a minute's wait. Concurrent verifications are capped at the
  processor count; past that a login is 503 rather than a server that has stopped answering queries because it is busy
  hashing. Fixed policy, no flag: a tunable lockout is a tunable that gets set to zero.

---

## 10. What Is Not Here

Stated plainly, because an operations document that implies capabilities the system lacks is a liability:

- No incremental backup and no point-in-time recovery (§5). Online *logical* backup exists; a physical snapshot does not.
- No repair tool for a store that fails `verify` (§6.3).
- **No transport security on the HTTP or MCP ports.** The binary port can encrypt itself (§9.1, [WIRE.md §7.1](WIRE.md));
  the other two are HTTP and expect an ingress or a reverse proxy in front of them. Without one, a password crosses in
  cleartext on 8080 and 8083.
- **No certificate rotation without a restart.** The PEM is read once at bind. A renewed certificate takes effect when
  the process does, which cert-manager's default renewal window makes a scheduled restart rather than an outage — but
  it is a restart.
- **No client-certificate authentication.** The TLS listener does not ask for one, and would not look at it. A
  principal comes from the `AUTH` frame and nowhere else.
- **No audit log.** There is a record of what ran — the slow query log — and none of who ran it.
- **No external identity provider.** One mechanism, one password; no OIDC, no LDAP, no mTLS.
- **No authorization below a database.** No per-label, per-property or per-row rule; that would have to be enforced on
  every element a query touches rather than once before it runs.
- **Sessions are per process.** A second replica does not honour a token the first issued, and a restart logs everyone
  out.
- **No bound on what one `LOAD CSV` may cost.** The import directory bounds *which* files, not their size: a load runs
  under the query deadline like anything else, and a batched one holds the writer lock for a batch at a time. Put only
  files you meant to import in that directory.
- No metrics exporter **for an embedder**. `graph-server` exports Prometheus metrics ([METRICS.md](METRICS.md)); the
  engine itself still just returns `StoreMetrics` as a Java object, and wiring that up in a process that embeds
  `graph-core` directly remains the embedder's job. That is deliberate — see METRICS.md §2.
- No background maintenance. Checkpoint, reclaim and compact are calls the embedder has to schedule.
- No multi-writer concurrency, no replication, no failover.
- The power-loss procedure in §8 is written and untested.

---

_Git HEAD revision: `4f21a62`_
_Last updated: 2026-10-24 (WAL failures stop commits until reopen; missing log segment refuses the open)_
