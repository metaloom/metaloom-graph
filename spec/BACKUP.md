# BACKUP.md — the logical dump, and the CLI that takes it

Backing a database up **while it is being used**, and restoring it into another one. A dump is a
portable file: it carries the graph, the declared schema, and the three geometry settings a target
has to match, and it does not carry a single byte of the store's on-disk format.

> **Do not put here**: the storage format ([FILE_FORMAT.md](FILE_FORMAT.md)), the query language
> ([CYPHER.md](CYPHER.md)), the frame layout ([WIRE.md](WIRE.md)), or the operational procedure
> ([OPERATIONS.md §5](OPERATIONS.md#5-backup-and-restore) — that is where an operator looks, and it
> links back here for the design).

---

## 0. Status

| | |
|---|---|
| **Module** | `graph-cli`, the sixth. `graph-server` depends on it for the format, so there is one encoder |
| **Binary** | `graph-cli/target/graph-cli-cli.jar` (released as `graph-cli-<version>-cli.jar`), main class `io.metaloom.graph.cli.GraphCli`; or a GraalVM binary, `mvn -Pgraalvm verify` ([GRAALVM.md §10](GRAALVM.md#10-the-second-binary)) |
| **In a container** | Both images carry it as `metaloom-graph-cli` — §7.1 |
| **Offline** | `--path=<root>` opens the store directly. Takes the `LOCK`, so the server must not hold it |
| **Online** | `--server=<url>` drives `GET /api/databases/{name}/backup`, **behind `--allow-backup`** |
| **Restore** | Offline only. It creates the target from the dump's geometry, which a running server cannot do |
| **Consistency** | One `SNAPSHOT` view for the whole walk. Writers are **not** stopped |
| **Ids** | **Not preserved.** §4 |
| **Format version** | 1, versioned independently of the store format — which is why a dump is the path between builds that cannot read each other's stores ([MIGRATION.md §7](MIGRATION.md#7-policy)) |

**What this closes.** [OPERATIONS.md §10](OPERATIONS.md) listed "no online backup" as the largest gap
after authentication, and [PROGRESS.md §7](PROGRESS.md) makes a 1.0 API freeze wait for "at least the
cursor and backup surfaces". This is the backup surface.

**What it is not.** Not incremental, not point-in-time, and not a physical copy. Restoring is O(graph)
rather than O(bytes), because it replays through the public API rather than copying pages.

---

## 1. Why logical rather than a file copy

A reflink clone of `seg/` is faster, preserves ids, and carries the indexes for free — `NativeLib.reflink`
has been bound and tested since Phase 6 waiting for exactly this. It was not chosen, for two reasons.

**It is not portable.** A physical copy is only readable by a build with the same store format, the
same page size and the same segment capacity. A dump is readable by any build that reads dump format
1, which is the property that matters for the artefact you reach for after losing a store.

**It cannot be online without a snapshot of the filesystem.** Copying a live store catches half-applied
commits, and the engine has no way to quiesce writers from outside the process. A reflink is atomic per
file and there are many files. The logical walk gets its atomicity from the engine's own MVCC instead,
which already exists and is already tested.

The physical shape remains the better answer for "clone this store onto the same machine right now",
and is still not built.

---

## 2. The walk is online, and this is why it works

The whole backup runs inside one `store.beginRead(IsolationLevel.SNAPSHOT)`.

**The part that is not obvious**: it is not only each node's *contents* that the view fixes — it is the
**set of nodes** as well. `SegmentSet.forEachLiveIdWhile` decides what is live by reading the free-slot
bitmap, `FreeSlotBitmap` reads through `SegmentFile.readSlice`, and `readSlice` resolves through the
snapshot's page versions like any other read. So a node allocated after the view opened reads as *free*
and is skipped.

That is what makes the next paragraph possible.

### 2.1 Pacing, and the writer it does not block

`forEachNodeWhile` takes the apply latch **outright** and holds it for the whole scan — correct for a
query, and catastrophic for a walk of every node, because that latch is the one a committing writer
takes exclusively. An unpaced full scan stops every writer in the process until it finishes.

So `GraphStore` grew a second form:

```java
boolean forEachNodeWhile(LongPredicate consumer, ScanOptions options);
```

`ScanOptions.throttled()` releases the latch every 4,096 ids and pauses briefly, so a writer waits for a
batch rather than for the store. The shape is `VerifyOptions`' deliberately — `verify` met this problem
first, and two spellings of one idea would be worse than one.

**Unlike a throttled `verify`, this costs no consistency.** `verify` holds no view, so pacing genuinely
makes it a series of instants ([VerifyOptions](../graph-core/src/main/java/io/metaloom/graph/core/api/VerifyOptions.java)
says so). A backup holds a view, and by §2 the view — not the latch — is what fixes what the scan sees.
Dropping and retaking the latch between batches changes nothing about the answer.

`SnapshotScanTest` is the proof, and it was written **before** any dump code, because if it had failed
the whole design would have collapsed back to stop-the-world.

### 2.2 What it does cost

| | |
|---|---|
| Writers | Wait one batch, not the whole walk |
| `reclaim()` / `compact()` | **Refused for the duration.** An open view holds back reclamation |
| Retention | Grows with write volume × backup duration. Past `retainedVersionBytes` (256 MiB) the engine abandons the oldest view — usually this one |
| Reader memory | `Snapshot.reconstructed` caches every page it had to rebuild, for the life of the view |

The last two are the same failure from two sides, and it surfaces as `SnapshotTooOldException`.
`GraphBackup.BackupAbandonedException` re-throws it with the three things an operator can actually do:
raise the budget, back up when less is being written, or stop the server and use `--path`.

### 2.3 One thread

A read view is thread-confined — `SnapshotHolder` is a `ThreadLocal`, so a second thread silently gets
**no view** and reads current data rather than failing. The walk therefore never leaves the thread that
opened it, and the server route runs the whole thing inside one `executeBlocking` body for that reason
and not for convenience.

---

## 3. The format

```
Header    magic "MLGDUMP\0", u16 version, string database, u64 takenAtMillis,
          u64 snapshotLsn, u32 pageSize, u64 segmentCapacity, u32 vectorDimensions
-- the checksum covers everything from here --
Schema    composite indexes | store-wide unique keys | vector index definitions
Nodes     per node: u8 1, varInt labelCount, labels, varInt propCount, {key, value}*
          terminated by u8 0
Edges     per edge: u8 1, varInt fromDumpId, varInt toDumpId, string type,
          u8 materialised, and when materialised its properties
          terminated by u8 0
Trailer   u64 nodes, u64 relationships, u64 lightweightEdges, u64 crc32c
```

Little-endian throughout, like every other binary structure here.

### 3.1 Neither section carries a count, and that is not an oversight

The obvious design writes `varInt(nodeCount)` and then that many nodes. It was written that way first
and it was **wrong**, because `StoreStats.nodeCount()` is *not* snapshot-resolved: it reports the store
as it is now, so under a concurrent writer it disagrees with what the scan produces. The guard caught it
on the first run of `BackupUnderLoadTest` — 2,050 against 2,000 — which is the scan being right and the
count being live.

The alternatives were buffering a whole section to count it, which defeats the streaming this exists for,
and seeking back to patch a placeholder, which a pipe cannot do. So: one marker byte per element, a zero
to end, and the true totals in the trailer where the reader checks them against what it actually read.

### 3.2 Dump ids are dense and implicit

A node is named in the edge section by its position in the node section, `0..n-1`, and that position is
never written down. Two things fall out: the file is smaller than one carrying real `ElementId`s, which
are packed with a kind in the high bits and land around 2^60 where a varint costs nine bytes; and the
restore's id map is a `long[]` indexed by dump id rather than a hash map.

### 3.3 The value codec is its own, and had to be

| | Why it could not be reused |
|---|---|
| `ValueCodec` (storage) | Escapes any value over 255 bytes **to the blob store** — a write, and it throws when there is no blob store. One long string would be unencodable. It is also versioned with the store, and a dump must version independently |
| `WireValueCodec` | **Rejects tags 5–9**: `BYTES`, `TIMESTAMP`, `UUID`, `VECTOR_F32`. The query layer renders those as strings before they reach a result, so the wire never carries them — but they are real property values the Java API writes, and a backup that turned a `float[]` embedding into a list of doubles would restore a store no vector index could be rebuilt over |

`DumpValueCodec` reuses the storage codec's **tag numbers** wherever the encoding is byte-identical —
the convention `WireValueCodec` set, so one reader can be checked against the other — while sharing no
code. It drops `ELEMENT_REF` and `VECTOR_REF` entirely: both name a location inside a specific store,
which is the one thing a dump must not depend on.

### 3.4 Untrusted input

A dump is the file somebody reaches for when things have already gone wrong, which makes it the file
most likely to be damaged and the worst moment for a confusing failure. It is hardened on the same terms
a store directory is ([OPERATIONS.md §9](OPERATIONS.md)): **every length is checked against the bytes
actually remaining before anything is allocated**, nesting is bounded, a varint that does not terminate
is refused, and the only exception that escapes is `DumpFormatException`.

`DumpHostileInputTest` asserts it: every prefix of a valid dump, every single-byte corruption, and 200
random inputs — none of which may produce an `OutOfMemoryError`, a `StackOverflowError`, or a silent
success.

---

## 4. Ids are not preserved

A restored node holds the same labels, the same properties and the same place in the graph, and a
**different** `ElementId`, because a fresh store allocates its own slots. Anything outside the store that
recorded an id from it is stale after a restore.

This is the cost of the logical shape and the thing a physical copy would have bought. It is stated in
the CLI's help text, printed after every restore, and asserted in the round-trip test — which compares
the two graphs as an **isomorphism keyed on a property**, because an id-by-id comparison would be
asserting something the design explicitly does not promise.

---

## 5. Restore

1. **Header**, validated before anything is created. A wrong magic or an unknown version fails here,
   with the target still untouched.
2. **The target is created from the dump's geometry** — `GraphDatabases.create(name, options)`, which
   this work added. Page size, segment capacity and vector dimensions are fixed at creation, so this is
   the only moment at which a target can be made able to hold the data. A dump with 768-dimension
   vectors restored into a root whose default has none is refused, by name, rather than half-loaded.
3. **Nodes**, then **edges**, in transactions of 4,000 elements — the same batch size `DemoData` settled
   on, and for the same reason. A lightweight edge is restored **as a lightweight edge**: promoting it
   would give it an id it never had and cost a 64-byte record it does not need.
4. **Schema last.** A declared index backfills when it is created, so declaring it first means
   maintaining it through the whole load instead of building it once; and a unique constraint declared
   first would be checked against a half-loaded graph.
5. **`verify(FULL)`** — the engine's own oracle, entirely outside this code.

A restore refuses a database that already exists and holds data unless `--force`, which replaces it.
Restoring into a non-empty store would merge two graphs, which is not what the word means.

The replacement is built **beside** the target — `GraphDatabases.stage`, a `.staging-<name>` directory
that no database name can address — and swapped in by `GraphDatabases.install` only after the dump has
been read to its trailer and passed `verify(FULL)`. The checksum is at the end, so a target replaced
first would be lost to a truncated or corrupt dump, `--force` or not; a failed restore now discards the
staging directory and leaves the original exactly as it was.

**`createUniqueConstraint` refuses a declaration the data already violates.** A faithful dump from a
store that held the constraint cannot do that, so a failure there means the dump is damaged or the target
was not empty — and the message says so rather than repeating the engine's, which would send somebody
looking at their data.

---

## 6. The server route

`GET /api/databases/{name}/backup`, behind **`--allow-backup`**.

**Its own flag, not `--allow-write`.** A backup is a read — and it is the read that hands over *every
byte* of a database in a single request. A principal needs `BACKUP` on the database as well, and both have to permit. That is a different decision
from "answer queries", in exactly the way `--allow-drop` is a different decision from "accept writes".
Folding it into either would mean an operator enabling one had silently enabled this.

Three things about the implementation are decisions rather than details:

- **The whole walk is inside one `executeBlocking` body**, because the view is thread-confined (§2.3).
  This is the only route in the server that streams while holding a view; every other one materialises
  first, and its javadoc says why.
- **Backpressure is honoured by blocking the worker.** When the socket cannot keep up, the thread waits
  rather than queueing a database in memory. An event loop could not do that, which is the second reason
  the walk is not on one.
- **The query deadline does not apply.** It exists to stop a runaway query from holding a worker and a
  view; a backup holds both on purpose, and thirty seconds is not a bound anybody wants on a backup.

**A failure after the first byte resets the connection** rather than trying to change a status that has
already been sent. A truncated dump arriving with a `200` would look like a valid short one until its
checksum was checked.

**The counts do not arrive over HTTP, and the client does not want them to.** The server puts them in
trailers, which is the only place they *can* go — it does not know them until the walk it is already
streaming has finished — and `java.net.http` does not expose trailers at all. So the CLI reads the
dump's own header and its own trailer out of the bytes as they go past: one 512-byte buffer and one
32-byte one, no seeking, so `--out=-` reports the same numbers a file does. It states what arrived
rather than what the far end claimed, and it catches a `200` carrying a proxy's login page immediately
instead of at the next restore. Before that, a piped online backup reported a full database as
`0 nodes`.

**Restore is not served.** It would have to overwrite a database the server has open, and it cannot
create a target with the dump's geometry. Stop the server and use `--path`.

---

## 7. The CLI

```bash
metaloom-graph-cli database backup  sales --path=/data/graphs --out=sales.dump
metaloom-graph-cli database backup  sales --server=http://localhost:8080 --out=sales.dump
metaloom-graph-cli database restore sales --path=/data/graphs --in=sales.dump --force
metaloom-graph-cli database list           --path=/data/graphs
metaloom-graph-cli database inspect        --in=sales.dump
```

The same binary carries `database info` and `database migrate`, which read and migrate a store's on-disk format
rather than its contents; they are [MIGRATION.md §5](MIGRATION.md#5-the-cli).

- **Options follow `GraphServerOptions`' shape**: a record, a `parse` taking the environment as a
  function, a declared `VARIABLES` list the help text is generated from, and a `HelpRequested` exception
  rather than an exit buried in a parser.
- **Neither `--path` nor `--server` is a default.** They do different things — one takes an exclusive
  lock on somebody's data — so guessing which was meant would be guessing about that.
- **Everything user-facing goes to stderr**, and only the dump to stdout, so `--out=-` can be piped.
- **`java.net.http`, not `graph-client`.** The client deliberately carries no engine, and this module
  needs one for offline mode. Pulling Vert.x in for a single request would be the wrong trade the other
  way.
- **Exit codes**: 0 success, 1 failure, 2 a usage mistake.
- **`--out` is replaced only by a complete dump.** It is written as `<out>.<random>.partial` in the same directory and
  renamed over `--out` at the end; a failure deletes the partial file. Opening `--out` directly truncated the previous
  dump before the database was even opened, so any failed backup destroyed the last good one.
- **`backup` and `list` refuse a `--path` that does not exist** instead of creating it. `GraphDatabases.open` makes a
  missing root with an empty `default` in it, which a restore wants and which turned a mistyped path into a valid,
  empty dump and exit 0.
- **`--password-stdin` reads one line**, without its `\n` or `\r\n`. It read the whole stream and trimmed only
  trailing newlines, so a second line became part of the password.
- `inspect` reads the header without opening any store, which is what makes a bad dump cheap to
  diagnose.

Only `backup` is served online because only `backup` can be (§6). `list` works either way.

### 7.1 In a container

Both images ship it at `/usr/local/bin/metaloom-graph-cli` — the JVM image as a launcher over
`graph-cli.jar`, the native image as the compiled binary. Same spelling either way, and in the native
image it is the **only** option: that image has no JVM, so before it existed that container could not
be backed up from the inside at all.

```bash
docker exec g metaloom-graph-cli database backup default --server=http://localhost:8080 --out=- > g.dump

docker run --rm -i -v graphdata:/data metaloom/graph-server:latest \
    metaloom-graph-cli database restore default --in=- < g.dump
```

Two things about that pair are worth knowing before they are discovered:

- **`--server`, not `--path`, while a server is running.** `GRAPH_ROOT=/data` is set in the image and
  is read by *both* programs, so a bare `database backup` inside a serving container opens `/data`
  directly and is refused — the server holds the store's exclusive `LOCK`. The message says so and
  names `--server`.
- **The restore has no `--path` and no `--force`.** `GRAPH_ROOT` supplies the first. The second is not
  needed because the target is empty: see §7.2.
- **`--server` needs `GRAPH_TOKEN` when authentication is on**, which is the default. It is sent as
  `Authorization: Bearer`; the value is the `token` that `POST /api/login` returns, for a login holding
  `BACKUP` on the database (or `MATCH`/anything for `database list`). Without it every `--server`
  command was a 401 with no way to send a credential. A variable rather than a flag, for the reason
  `GRAPH_ADMIN_PASSWORD` is: a flag is in shell history and in `ps`.
  `docker exec -e GRAPH_TOKEN=… g metaloom-graph-cli database backup …`.

### 7.2 Restoring over the last database

Opening a root that does not exist creates a `default` inside it, and `GraphDatabases.drop` refuses to
remove the last database in a root — a server left holding none has no useful behaviour. Those two
rules meet exactly at the commonest restore there is, "put this dump on that empty machine", and for
the whole of this feature's first version that restore could not be performed.

Two changes, both narrow:

- **`GraphDatabases.replace(name, options)`**, which closes, deletes and recreates in one step while
  holding the lock. The root is never observed empty, so this is not "drop without the check" — the
  check is about a state that no longer occurs. `drop` is unchanged and still refuses. The CLI's restore
  has since moved to `stage` + `install` (§5), which keep the same property — `install` renames the
  original aside, renames the staged store in, then deletes the original — and also survive a dump that
  turns out to be damaged; `replace` remains for callers with nothing to lose.
- **`--force` is about data, not existence.** A target that exists but holds no nodes is replaced
  without it. Being asked to confirm the loss of an empty database that the tool itself had just
  created was a question with only one answer.

---

## 8. Tests

| | |
|---|---|
| `SnapshotScanTest` (graph-core) | **The foundation.** A scan under a view does not see nodes committed after it, still sees nodes deleted after it, and a paced scan sees the same set while a writer commits throughout |
| `BackupRoundTripTest` | Dump, restore, compare. Multi-label and label-less nodes, lightweight edges, a self-loop, parallel edges, every property type including the four no other codec carries, and the declared schema. Ends in `verify(FULL)` — and a store after two thirds of it was deleted and it was compacted, vectors and a vector index included, whose restored copy still answers a nearest-neighbour query with a survivor |
| `BackupUnderLoadTest` | A backup taken while a writer commits: every edge joins one generation and every generation is present whole, so the dump is one instant — **and** the writer made progress, so it was online |
| `DumpHostileInputTest` | §3.4: every truncation, every single-byte corruption, 200 random inputs, a huge length, deep nesting, a non-terminating varint |
| `CliOptionsTest` | The command line, both directions, and that every dispatched command is documented |
| `GraphCliTest` | The commands as somebody runs them, through `run()` and its exit status. §7.2 lived here: neither of the two above goes through the CLI, so neither could see that a restore into a fresh root was impossible. Also that a truncated or corrupt dump restored with `--force` leaves the original untouched and no staging directory listed |
| `GraphCliEdgeCaseTest` | A failed backup (missing database, locked store) leaves the previous dump byte for byte and no partial file; a mistyped root refused by `backup` and `list` without being created; usage errors and the 0/1/2 exit codes; `--password-stdin` reading one line; garbage and empty dumps refused by `inspect` |
| `GraphDatabasesTest` (graph-core) | That the last database can be `replace`d though it cannot be `drop`ped, and that the root is never observed empty |
| `GraphServerOptionsTest` | That `--allow-backup` is off by default and independent of `--allow-write` and `--allow-drop` |
| `tools/native-image.sh`, `cli` | Not a unit test: the GraalVM binary doing the whole round against a real store. `-H:+SharedArenaSupport` fails on *close*, so only a round trip catches its absence — [GRAALVM.md §10.2](GRAALVM.md#102-its-smoke-test) |

---

## 9. What is not here

- **No incremental or point-in-time backup.** The log is trimmed at each checkpoint, so it cannot be
  replayed against an older dump.
- **No restore over the network** (§6).
- **No physical snapshot.** `NativeLib.reflink` is bound, and its only caller is the migration's store copy
  ([MIGRATION.md §4.3](MIGRATION.md#43-the-copy)), which is most of a physical snapshot already: a reflink or sparse
  copy of a cleanly closed store, without its log.
- **A dump is not how a store moves to a newer format version.** `metaloom-graph-cli database migrate` does that in
  place and keeps ids ([MIGRATION.md](MIGRATION.md)). A dump is the way across a version that has no migration path
  — versions 1 and 2 — and the only way back to an older build.
- **No compression.** A dump is very compressible and `gzip` is one pipe away; building it in would mean
  choosing a codec on everyone's behalf.
- **No parallel restore.** The engine has one writer, so there is nothing to parallelise into.
- **No identities.** A dump is one database, and the users, roles and grants live in `<root>/.auth/`, which is not a
  database ([AUTH.md §3.1](AUTH.md)). So **a restore does not restore who may use what** — it puts the data back and
  leaves the identity file alone, which is right when restoring onto a machine that already has one and a surprise when
  restoring onto a bare machine. On a bare machine the server bootstraps a fresh administrator on first start and prints
  a password; the grants that named the restored database have to be made again. Back `.auth/` up separately, or record
  the grants somewhere; `metaloom-graph-cli auth list` prints them in the form `auth grant` accepts, which makes that
  practical.
- **A credential for the online half.** `--server` mode now has to log in, so a scripted backup needs a principal
  granted `BACKUP` on the database — and the server still needs `--allow-backup`, because the flag and the grant both
  have to permit.

---

_Git HEAD revision: `8f8caad`_
_Last updated: 2026-10-25 (dump versus migration; reflink's first caller)_
