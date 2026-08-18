# TASKS.md — deferred work items

Work that a spec file identified, argued about, and then did **not** do. For each item: whether it is
worth building, what it would actually cost, what would change the verdict, and what "done" means.

It is a task list, not a design document. The design lives in the spec of the layer it touches, and a
task that grows past a paragraph of design gets its own file. [INDEX_TASKS.md](INDEX_TASKS.md) is the
same idea scoped to one spec; this file holds everything else, so **ids carry an area prefix** and a
new area adds its own rather than extending someone else's numbering.

> **Do not put here**: how the ring works ([IO_URING.md](IO_URING.md)), how the log is written
> ([WAL.md](WAL.md)), the index backlog ([INDEX_TASKS.md](INDEX_TASKS.md)), the cross-project backlog
> ordering ([PROGRESS.md §5](PROGRESS.md)).
>
> **A finished task leaves this file.** Its reasoning moves to the layer it changed, because that is
> where the next reader of that code will look; a backlog entry marked "done" is a second copy that
> goes stale. §3 is the ledger of what left and where it went. **Ids are never reused.**

---

## 0. The task list

| # | Task | Priority | Effort | Verdict |
|---|---|---|---|---|
| [URING-1](#uring-1--measure-the-commit-path-before-wiring-anything) | Measure where a commit's time actually goes | P2 | S | **Do this first.** Every other item here is gated on it |
| [URING-2](#uring-2--group-commit-that-actually-groups) | Durability outside the writer lock | P2 | L | Build on its own merits, not for the ring. It is the prerequisite for URING-3 |
| [URING-3](#uring-3--the-write-side-of-the-ring-reachable-from-java) | Bind `submit_write` / `submit_fsync`, and link them | P3 | M | **Not until URING-2 lands.** Needs a Rust change too, not just a binding |
| [URING-4](#uring-4--ioenginekinduring) | `IoEngineKind.URING` | P3 | S | **Do not add.** A selectable engine with no caller, under a name that would mislead |
| [URING-5](#uring-5--a-reap-that-happens-later) | Asynchronous reap | P3 | M | **Do not build.** No caller, and URING-3 does not create one |
| [URING-6](#uring-6--measure-whether-the-mapped-data-path-binds) | Fault-bound scan measurement | P2 | M | Build when a workload outgrows RAM. It is the only gate on URING-7 |
| [URING-7](#uring-7--a-data-path-that-is-not-a-mapping) | Buffer-pool data path | P4 | XL | **Do not build.** Recorded so the decision is not re-derived |

---

## 1. First: is the WAL really the caller the ring is waiting for?

[IO_URING.md §6.1](IO_URING.md) nominates the write-ahead log under `GROUP_COMMIT`, on the grounds
that it is the one part of the system doing I/O through a descriptor rather than through a mapping,
that it already runs on `O_DIRECT`, and that "the Rust side is already written for it — `submit_write`
and `submit_fsync` are there."

**All three halves of that are weaker than they read**, and the tasks below are sized against the
corrected version rather than the original claim.

**It does not already run on `O_DIRECT`.** `WalWriter.resolve` maps `DIRECT` to the direct log and
`MMAP` **and `AUTO`** to a buffered `FileChannel`. `GraphStoreOptions` defaults to `AUTO`, and both
convenience `WalWriter` constructors hardcode `MMAP`. Nothing outside `DirectWalTest` selects `DIRECT`.
The direct path is real, it is FFM end to end — `Arena`, an aligned `MemorySegment`, and
`NativeLib.pwrite`/`fsync` through `DirectIoEngine` — and it is **off by default**, deliberately: the
pad-to-block rule costs up to one block per fsync, which `WalWriter` §"What it costs" argues is worse
than what bypassing the cache buys on many small commits.

**The batching the ring would add is already done, in userspace.** `DirectLog` stages records into one
aligned buffer and flushes whole blocks. A commit is typically already *one* `pwrite` plus one
`fsync` — not the N scattered writes a ring exists to merge. What remains for a ring is one syscall of
the two, and overlap between *concurrent* committers.

**There are no concurrent committers.** `wal.logCommit` runs inside the `writerLock` region in
`GraphStoreImpl`, and `GROUP_COMMIT` is an interval-based *fsync skip*, not a merge of several
transactions' blocks. So at any instant there is at most one commit's worth of bytes pending, and a
batched submission has nothing to batch.

That is why URING-2 is priced as the real work and URING-3 as the small thing that follows it, rather
than the other way round — and why URING-1 comes before both.

---

## URING-1 — measure the commit path before wiring anything

**Priority P2, effort: small.** The 15x in [IO_URING.md §2](IO_URING.md) is a number about a `pread`
loop against a ring. It is not a number about a commit, and nothing in this repository measures where
a commit's time goes.

Measure, on both engines and both relevant sync modes:

- commit throughput at 1, 2, 4, 8, 16 concurrent committing threads;
- the split between time in `handle.write` / `channel.write` and time in `force`;
- how much of the wall clock is spent *waiting for `writerLock`* rather than in either.

**Why it decides things.** If the fsync round trip does not dominate once `GROUP_COMMIT`'s interval
has already amortised it, §6.1's premise is gone and URING-2 and URING-3 are both unjustified — for
I/O reasons, at least; URING-2 may still be worth it for lock contention alone, which the third
measurement is there to separate. If the lock wait dominates instead, that is URING-2 confirmed and
the ring is still not the reason to do it.

**Done when**: a report under [reports/](reports) carries the three numbers, and
[IO_URING.md §6.1](IO_URING.md) either cites it or is rewritten to match it.

---

## URING-2 — group commit that actually groups

**Priority P2, effort: large.** `WalSyncMode.GROUP_COMMIT` exists and does something real — it skips
fsyncs that fall inside the interval — but it does not group anything. Committers serialize on
`writerLock`, so transaction B does not begin its log write until A's has returned.

Real group commit decouples durability from the writer lock: a committer appends its records under the
lock, releases it, and waits for `durableLsn >= itsLsn`; one thread at a time takes the role of group
leader, issues the write and the fsync for everything appended so far, and releases the waiters. That
is the shape that produces several transactions' blocks pending at once — which is the shape URING-3
needs, and independently the shape that makes fsync cost amortise across committers instead of across
time.

**The rule that cannot bend.** A commit must not return to its caller before its own LSN is durable
under `FSYNC_ON_COMMIT`. The whole point of moving durability out of the lock is to overlap the
*waiting*, never to shorten it. `WalFaultTest` and `DirectWalTest` cover the two behaviours that catch
a mistake here — a write that fails, and a write that succeeds followed by a failing fsync — and both
must keep passing with several threads in flight, which today they never are.

**The second trap.** `DirectLog`'s invariant is that a written block is immutable and a flush pads to
a boundary. A group leader flushing a partly-filled buffer that a later committer then appends to must
not violate that; the padding is what makes it safe, and it means a group's tail block is spent, so
grouping trades wasted bytes for saved fsyncs. URING-1's numbers are what say whether that trade is
positive.

**Done when**: N threads committing concurrently produce N commits' blocks in one fsync, shown by
`IoCounters.walSynced` rather than by timing; the fault tests pass under concurrency; recovery after a
kill during a group commit replays every acknowledged transaction and no unacknowledged one.

---

## URING-3 — the write side of the ring, reachable from Java

**Priority P3, effort: medium. Not until URING-2 lands** — binding a function with no caller is
exactly how this capability got into its present state.

The gap is real: `mlg_ring_submit_write` and `mlg_ring_submit_fsync` are exported by the crate and
never looked up in Java. `UringIoEngine` binds five of the eight ring functions.

**It is not a binding-only change, and this is the part [IO_URING.md §6.1](IO_URING.md) gets wrong.**
The win it describes is a linked write-then-fsync, one submission instead of two round trips. But
`ring.rs` builds both entries **unlinked** — neither `submit_write` nor `submit_fsync` sets
`IOSQE_IO_LINK`. Submitting a write and its fsync into the same ring as it stands gives no ordering
between them, which for a log is not a missing optimisation but a correctness hazard: an fsync that
completes before the write it was meant to make durable acknowledges a transaction that is not on the
device. So this task is a Rust change (an explicit link flag, or a `submit_write_fsync_linked` pair)
plus its ABI-checked binding plus its test — not thirty lines of `findOrThrow`.

**Done when**: the linked pair exists in `ring.rs` with a Rust test that asserts the *ordering* under a
fault, not just the completion; both functions are bound in Java; `WalWriter`'s direct path can issue a
group's write and fsync as one submission; `DirectWalTest`'s fsync-failure injection still fails the
commit.

---

## URING-4 — `IoEngineKind.URING`

**Priority P3. Do not add it**, and the reason is not only "no caller".

`IoEngineKind`'s javadoc is explicit that the data path is memory mapped whichever value is chosen and
that what the enum selects is **how the write-ahead log reaches the device**. A `URING` value would
therefore mean "the log goes through a ring", while every reader who has seen the 15x will read it as
"queries go through a ring". A configuration knob whose name promises the headline number and delivers
something else is worse than no knob.

If URING-2 and URING-3 both land, the value to add is scoped to what it does — and `AUTO` must not
resolve to it, for the same reason `AUTO` does not resolve to `DIRECT` today.

**Done when**: n/a. If this is ever built it is a line of the URING-3 change, not a task of its own.

---

## URING-5 — a reap that happens later

**Priority P3, effort: medium. Do not build.** `UringIoEngine.readBatch` submits and waits inside one
call. That is batching, not asynchrony: the calling thread is blocked for the whole flight, so the only
thing overlapped is the I/O with itself, never I/O with computation.

Real overlap needs a caller that submits, does unrelated work, and reaps later — a different interface
(`submit` returning a handle, `poll` draining it) and a different lifetime rule, since the buffers must
stay alive across the gap and the `Arena` that owns them now outlives a single call.

No such caller exists, and **URING-3 does not create one**: a group commit's leader has nothing to do
between submitting and being durable, because being durable is the thing it is waiting for.

**What would change the verdict**: URING-7, or a scan that could decode page N while page N+1 is in
flight.

---

## URING-6 — measure whether the mapped data path binds

**Priority P2, effort: medium.** Everything about the data path's I/O strategy — mapping, `madvise`
prefetch, and the decision not to have a buffer pool — rests on an untested assumption: that the OS
page cache and its eviction policy are good enough, and that a traversal's stalls are dominated by
pointer-chase latency rather than by faults.

Measure a working set well past RAM: scan and traversal throughput with
`metaloom.graph.prefetch.traversal` on and off, and the fraction of wall clock in major faults. The
prefetch path (`SegmentSet.prefetch`, driven from `GraphStoreImpl`) is the thing under test — it is the
right primitive for a mapping, and nobody has shown what it recovers.

**Why it is here rather than in a benchmark file**: it is the only measurement that can promote URING-7
from "not planned" to "planned", and the only one that can retire it.

**Done when**: a report under [reports/](reports) states the fault-stall fraction at a working set of
2x and 8x RAM, with prefetch on and off.

---

## URING-7 — a data path that is not a mapping

**Priority P4, effort: extra large. Do not build.** Recorded so that the reasoning is not re-derived
from scratch by the next person who reads the 15x and wonders why queries do not get it.

A mapping ties the engine to the page cache and gives no way to express "fetch these eight". A
buffer-pool data path makes every page fetch a real read through a descriptor, and then `readBatch` has
a caller on the hottest path in the system. It also means owning eviction, pinning, and a page table —
which [ID_ENCODING.md §6](ID_ENCODING.md) rules out for id resolution on grounds that would have to be
re-examined, not merely restated.

**Note what it would still not fix.** A pointer chase is serial whether the pages come from faults or
from reads; a hop cannot be issued before the previous one lands. The ring pays for scans, for
frontier fetches, and for anything whose addresses are known in advance — never for the chain walk that
[GRAPH_BENCHMARK.md §7](GRAPH_BENCHMARK.md) measures at 94 ns a hop.

**What would change the verdict**: URING-6 showing that fault stalls dominate at scale *and* that
`madvise` prefetch does not close the gap. Both halves are required — the first alone argues for better
prefetching, not for a different data path.

---

## 2. What this list deliberately leaves out

- **Any measurement of the ring itself.** `UringIoEngineTest.testHowMuchBatchingBuys` already answers
  "how much faster is a batched submission than a `pread` loop", and it prints rather than asserts
  because a gate on that speedup would be measuring the machine. Running it harder produces a
  better-characterised number about a thing nothing calls. The measurements worth adding are URING-1
  and URING-6, and both are about callers.
- **Exercising the ring under GraalVM.** [IO_URING.md §9](IO_URING.md) reports that the ring survives
  native-image and has not been exercised there, for the same reason it is not exercised on the JVM. It
  becomes a task when it has a caller, and then it is that caller's task.
- **Removing the ring.** It is cheap to keep — dormant, ABI-checked, and `isSupported()` gated — and
  expensive to rediscover. The argument for keeping it is in [IO_URING.md §6.2](IO_URING.md) and does
  not need re-making here.

---

## 3. Closed

The reasoning moved with the work; this is only the ledger, so that a spec still linking to one of
these ids leads somewhere.

| # | Task | Where it lives now |
|---|---|---|
| — | — | — |

---

## AUTH — identity and what it does not yet cover

[AUTH.md §9](AUTH.md) states these as gaps; this is what closing each would mean. Nothing here is started.

### AUTH-1 · TLS — done on the binary port, open on HTTP and MCP

**The binary port is done.** `--wire-tls-cert` / `--wire-tls-key` open a **second** listener on 8084 over the same
`WireServer`; `--wire-tls-only` closes the plaintext one once the clients have moved; `ClientOptions.withTls(...)`
takes a `ClientTls` that verifies the hostname by default. [WIRE.md §7.1](WIRE.md) has the design and the reasoning for
two ports rather than a negotiated upgrade. The four decisions this entry warned about were answered: a PEM pair at a
path (the shape of a Kubernetes `kubernetes.io/tls` secret), rotation by restart, the chart mounts a secret the operator
names, and the client verifies unless deliberately told not to.

**What remains is the HTTP and MCP ports**, and the verdict on them is different rather than merely unfinished. Both are
HTTP, and HTTP already has a TLS story — an ingress, a mesh or a reverse proxy, with certificate provisioning and
renewal built into the platform. Duplicating that inside the server buys little and adds a second place for a
certificate to be stale. The binary port had no such alternative: nothing in front of it can terminate a raw TCP
protocol it does not understand, which is why the work landed there first.

**Cost**: small — the same `PemKeyCertOptions` on `HttpServerOptions`, plus the chart wiring.
**Done**: `--tls-cert` / `--tls-key` for the HTTP and MCP listeners, `Secure` on the session cookie becoming
unconditional when the server terminates TLS itself, and a k3d phase that talks to it over HTTPS.
**What would change the verdict**: somebody exposing 8080 without a proxy. Until then a proxy is the better answer and
this stays open on purpose rather than by neglect.

**Not covered by this, and deliberately**: certificate *rotation* without a restart (the PEM is read once at bind), and
client certificates, which are `AUTH-8` because they are an authentication mechanism rather than a TLS setting.

### AUTH-2 · An audit log

**Worth building: probably.** There is a record of *what* ran — the slow query log — and none of *who* ran it, which is
the question asked after an incident rather than during one. The reason it is not free: the useful version records
logins, refusals and administrative edits, and the shape has to survive being the thing somebody greps at 3am.

**Cost**: small. An appender and a decision about what not to record.
**Done**: logins and refusals with principal and source address, every `.auth/` edit with who made it, parameter values
never recorded (the rule `SlowQueryLog` already follows), and a documented retention story.

### AUTH-3 · An external identity provider

**Worth building: only on request.** One password per user is enough for a single deployment and is the wrong answer for
an organisation that already has OIDC or LDAP. The protocol is ready for it — `AUTH` carries a `mechanism` — so this is
additive rather than a rework.

**Cost**: medium, and it brings a dependency this build has so far avoided.
**Done**: a second mechanism, a mapping from external groups to roles, and `.auth/` still holding the local
administrator so a provider outage is not a lockout.

### AUTH-4 · Authorization below a database

**Worth building: no, not as it stands.** Per-label or per-property rules cannot be checked once before a query runs;
they have to be checked on every element it touches, inside `GraphAccess`. That is a different feature with a different
cost model — the check lands on the read path, where a node read by id is 21 ns — and the honest version also has to
answer what a filtered `count(*)` means.

**Cost**: large, and it lands on the hottest path in the engine.
**What would change the verdict**: a concrete requirement, plus a measurement showing the per-element check is
affordable. Both, in that order.

### AUTH-5 · A `RESTORE` privilege and a restore route

**Worth building: only alongside a restore route.** The name is reserved in `Privilege`'s javadoc and there is nothing
to gate: restore is offline, under the store `LOCK` ([BACKUP.md §6](BACKUP.md)). A privilege for an operation that does
not exist would be exactly the empty check `McpToolDescriptor` used to argue against.

### AUTH-6 · A shared session store

**Worth building: when there is a second replica.** Sessions are in memory, so a token issued by one pod is not
recognised by another and a restart logs everyone out. The engine is single-writer and the chart defaults to one
replica, so this is not currently reachable — but it is the first thing that breaks if anybody scales the Deployment
for read capacity.

**Cost**: medium, and it wants a store this project does not have.
**Done**: tokens honoured across replicas, or a documented refusal to support more than one.

### AUTH-7 · Login and session meters

**Worth building: yes, and it is small.** A login-failure rate is the signal that says somebody is guessing, and there
is no meter for it: refusals are counted through `graph_queries_rejected{reason}`, which now carries `unauthenticated`
and `forbidden`, and that covers queries rather than logins.

The reason it was left out of the change that built everything else: a new meter needs a row in
[METRICS.md](METRICS.md) §3 **and** a line in `MetricsCatalogExercise`, because `MetricsCatalogScrapeTest` scrapes a real
registry and fails on a meter that is documented and never fires. Two files and a test, for a signal nothing yet alerts
on.

**Done**: `graph_auth_logins_total{outcome}`, a session gauge, both in §3, both exercised, and an alert rule in
`OPERATIONS.md §4` on the failure rate.

### AUTH-8 · Client certificates as an identity

**Worth building: only on request, and it belongs with `AUTH-3` rather than with TLS.** The binary port terminates TLS
and does not ask for a client certificate; if it did, the interesting question would not be the handshake but the
mapping — which certificate subject is which user, who issues them, and what happens when one is revoked between the
handshake and the next query. That is a second authentication *mechanism*, and the protocol already has the field for
one: `AUTH` carries a `mechanism`, so this is additive.

The trap to avoid: treating "presented a valid certificate" as "is the principal named in it" without deciding where
the mapping lives. A certificate is not a grant, and a CA that can issue any name is a CA that can issue `admin`.

**Cost**: small in Vert.x (`setClientAuth`, `PemTrustOptions`), real in the mapping and the revocation story.
**Done**: `mechanism = CERTIFICATE`, a documented subject-to-user mapping held in `.auth/`, a decision on revocation,
and `.auth/` still holding a password for the local administrator so a CA outage is not a lockout.

---

_Git HEAD revision: `58cd33e`_
_Last updated: 2026-08-18 (TLS on the binary port)_
