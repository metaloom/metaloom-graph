# WIRE.md — the binary streaming protocol and the Java client

This is the specification of the **binary port**: how a Cypher result is streamed off a socket, and
what the Java client does with it.

**Why it exists.** The HTTP route builds a whole `JsonObject` — every row, plus a graph projection —
before a byte reaches the caller. That is right for a browser drawing two hundred nodes and useless
for anything pulling a million rows. This is the other shape: rows encode and flush as the engine
produces them, the client holds one batch at a time, and a slow consumer becomes backpressure rather
than heap.

**Which transport, and why this one**: [TRANSPORT_DECISION.md](TRANSPORT_DECISION.md) — a measured
trial against gRPC with the criteria committed before the numbers. Read it before proposing a change
to the framing; it also names the one thing the losing arm did better, which shapes §5 below.

> **Do not put here**: the query language ([CYPHER.md](CYPHER.md)), the storage format
> ([FILE_FORMAT.md](FILE_FORMAT.md)), the embedded Java API ([API.md](API.md)), or the HTTP server and
> browser UI ([CYPHER.md §7](CYPHER.md)).

---

## 0. Status, in one table

| | |
|---|---|
| **Modules** | `graph-protocol` (frames, codec, error codes), `graph-client` (the Java client), `io.metaloom.graph.server.wire` (the server) |
| **Default port** | 8081, bound to **127.0.0.1**, unencrypted. **8084 for the TLS listener**, off until a certificate is given; §7.1. HTTP stays on 8080 |
| **Surface** | Cypher, reads and writes. **Writes off unless the server has `--allow-write`**; §4.1. No schema call |
| **Security** | **Authentication, yes. Transport security, optional and off by default.** An `AUTH` frame during the handshake ([AUTH.md](AUTH.md)); on port 8081 the password crosses in cleartext, on the TLS listener it does not. §7 and §7.1 |
| **Protocol version** | 5. Negotiated on connect; a mismatch is an error, not a hang, and it happens *before* any credential is asked for. **TLS did not change it** — the frames are identical on both listeners. Version 5 added temporal values (tag 15, §2) |
| **Database** | Chosen per connection at `HELLO` — see [DATABASES.md](DATABASES.md) |
| **Client dependencies** | `graph-protocol` and vertx-core. **Not** graph-core, **not** graph-cypher, no FFM, no native library — asserted by `ClientDependencyTest` |

**What it bounds and what it does not**, stated plainly because the distinction is easy to overclaim:

| | Bounded by |
|---|---|
| The **encoded payload** | **This.** Rows encode and flush in batches; neither end builds the whole result |
| The **client's** memory | **This.** §5 |
| **Time to first row** | **This.** The first batch goes out after ~64 KiB or 20 ms, not after the last row is found |
| The executor's intermediate `List<Row>` | **Now bounded for a query whose every clause streams** — the executor pushes rows through to this transport instead of building a list, and a `LIMIT` reaches the scan. For anything with `ORDER BY`, `DISTINCT`, an aggregate, `RETURN *` or `UNION`, the peak *server* memory is still the widest intermediate result. [CYPHER.md §7.1](CYPHER.md) |
| A **write** query | **Nothing streams.** Its rows are produced, its transaction commits, and only then does the first byte go out — §4.1 |

`ORDER BY`, `DISTINCT`, aggregation and `UNION` buffer their whole input whatever the protocol does.
That is semantics, not a limitation waiting to be fixed.

---

## 1. The frame

```
u32 length     payload bytes, little-endian
u8  type
u8  flags      reserved, written as zero
u16 streamId   little-endian
```

Eight bytes, so the payload starts 8-aligned. Little-endian explicitly, like every other binary
structure in this project.

**The length is checked against `MAX_FRAME_BYTES` (16 MiB) before anything is allocated for it** —
before `RecordParser` is even told to expect that many bytes. A length prefix off a socket is the
textbook unvalidated length field, and seven defects of exactly that kind have been found in this
project's storage parsers. A frame that fails the check ends *that connection* and touches no other.

**Until `WELCOME` the server's limit is 16 KiB, not 16 MiB** (`WireServer.HANDSHAKE_MAX_FRAME_BYTES`,
through `FrameReader.attach`'s per-header ceiling). `HELLO` and `AUTH` are a few hundred bytes; the
full limit before the handshake let a peer that had presented nothing make the server buffer sixteen
megabytes per connection. A peer also has **10 seconds** (`DEFAULT_HANDSHAKE_TIMEOUT_MILLIS`) to send
its `HELLO` and, when asked, its `AUTH` — counted only while the server waits on the peer, not while it
verifies a password or opens a store. There is deliberately no idle timeout after `WELCOME`: the
protocol has no keep-alive and a pooled client idle between queries is normal. There is no
per-address connection cap either; that is still open.

**The stream id** is in the header rather than in each payload because the dispatcher has to route a
frame before it has decoded anything. One connection carries many queries at once; stream 0 is the
connection itself.

| Frame | Direction | Payload |
|---|---|---|
| `HELLO` 1 | client | `i32` protocol version, value: database name (`""` = the server's default) |
| `WELCOME` 2 | server | `i32` version, `i32` max frame bytes, `i32` max concurrent streams, value: the database it resolved, value: the principal (empty when the server does not authenticate), value: its grants as strings |
| `QUERY` 3 | client | value: query text, value: parameter map, `i64` deadline millis (0 = the server's own) |
| `CANCEL` 4 | client | empty |
| `COLUMNS` 5 | server | value: list of column names |
| `ROWS` 6 | server | `i32` row count, then `rows × columns` values |
| `COMPLETE` 7 | server | `QuerySummary`: nine `i64`s — row count, elapsed micros, then nodes created and deleted, relationships created and deleted, properties set, labels added and removed |
| `ERROR` 8 | server | `i32` error code, value: message, `i32` line, `i32` column |
| `AUTH_REQUIRED` 9 | server | `i32` mechanism bitmask (bit 0 = password), value: realm |
| `AUTH` 10 | client | `i32` mechanism, value: user name, value: secret |

Everything that is not a fixed-width scalar is a codec value, so there is one decoder to get right and
one to fuzz rather than a bespoke reader per frame type.

`COMPLETE`'s counters are **fixed fields rather than a codec map**. A map would let one be added
without a version bump, but this protocol's rule is to bump instead, and a fixed layout is one the
hostile-input oracle can exhaust where a map is a length field and a loop. Seventy-two bytes once per
query is not worth a branch. The client reads them all and ignores anything after, which is not a
compatibility promise — the version is the promise — but refusing bytes it never asked for would be
gratuitous.

`COLUMNS` always precedes any row **and is always sent**, including for a result with no rows at all —
a protocol whose header is conditional on there being data is a protocol whose clients special-case
the empty case wrongly.

### Version negotiation

The client sends `HELLO` — its version and the database it wants — and **waits** before sending
anything else; the server answers `WELCOME`, or `AUTH_REQUIRED` if it wants a credential, or an
`ERROR` carrying `UNSUPPORTED_VERSION` or `NO_SUCH_DATABASE`, and closes. A client that started
querying without waiting would meet a version mismatch as a stream of frames it could not read, with
no explanation.

```
client                          server
  HELLO (version, database) ──►
                            ◄── WELCOME                  authentication off: as it always was
                            ◄── AUTH_REQUIRED            authentication on
  AUTH (mech, user, secret) ──►
                            ◄── WELCOME (…, principal, grants)
                            ◄── ERROR UNAUTHENTICATED, and the connection closes
```

**Credentials are not in `HELLO`**, and that is the decision worth knowing. Three reasons, the third
decisive:

1. `HELLO` resolves the database, and *which* database a principal may open depends on the principal.
   One frame with two failure modes and two error codes is worse than two frames with one each.
2. A future mechanism — a bearer token, mTLS, an external provider — becomes a `mechanism` value
   rather than a third `HELLO` layout.
3. **A password is only ever sent to a server that asked for it.** A client holding credentials that
   meets a server running with `--no-auth` completes the handshake without them ever leaving the
   process. Credentials in `HELLO` would hand them to any port that answered.

`AUTH_REQUIRED` is a frame rather than an inference, for the reason the handshake exists at all: a
client with no credential to offer learns so immediately and fails with a message, instead of parking
on a socket waiting for a `WELCOME` that is never coming.

The connection has five states — `NEW`, `GREETED`, `AUTHENTICATING`, `OPENING`, `READY` — and the
third earns its place. A password is verified on a worker thread, so between the `AUTH` frame arriving
and the answer coming back there is a window in which a client could pipeline a `QUERY`. Everything is
refused in that window: a query racing an unfinished credential check is not a race worth having.
`OPENING` is the same window for the database, refused the same way.

**With authentication on, the database is resolved only after `AUTH` succeeds.** Resolving it at
`HELLO` answered `NO_SUCH_DATABASE` to a peer that had presented nothing — a listing of the databases
for anyone who can reach the port — and opened a store on its behalf. Now every `HELLO` gets
`AUTH_REQUIRED`, and a missing name is reported after the credential. With authentication off it is
resolved straight after `HELLO`, as before. Either way the open runs on Vert.x's default worker pool,
not on the event loop: the first open of a store maps its segments while holding the
`GraphDatabases` monitor, which on a loop stalled every connection sharing it. Not on the query pool
either, so a handshake never waits behind running queries.

**Grants are re-resolved at every `QUERY`**, by user name, from the identity store — as HTTP resolves
them per request ([AUTH.md §6.1](AUTH.md)). A revoke or a grant reaches an open connection on its next
query; a user who is disabled or dropped gets `UNAUTHENTICATED` on stream 0 and the connection closes.
Freezing them at `AUTH` let a pooled connection outlive a revoke for as long as it stayed up.

Password verification shares one bound with HTTP's login — a semaphore of `availableProcessors()` in
`AuthPolicy` — and past it an `AUTH` is answered `OVERLOADED` and the connection closed, rather than
queued. It runs on the default worker pool rather than the query pool, so a burst of handshakes cannot
make queries wait behind PBKDF2.

**The database is fixed for the connection.** A stream therefore never has to say which database it
belongs to, and the server resolves the store once instead of per query. That is not only simplicity:
a read view belongs to one store, and a connection that could change store mid-flight would be a
connection whose open views outlive their subject. Two databases at once means two connections.

The server's limits travel in `WELCOME` rather than being assumed by the client, because they are
configurable — a client that hard-coded them would either refuse frames the server is entitled to send
or discover the stream limit one `OVERLOADED` at a time.

---

## 2. The value codec

Same tag shape as the storage codec, `sizeClass:3 | type:5`, and the same numbers wherever the
encoding is **byte-identical**, so a reader of one can be checked against the other.

| | | | | |
|---|---|---|---|---|
| 0 `NULL` | 1 `BOOL` | 2 `INT` (zigzag) | 3 `FLOAT` | 4 `STRING` |
| 10 `LIST` | 11 `MAP` | 12 `NODE` | 13 `REL` | 14 `PATH` |
| 15 `TEMPORAL` | | | | |

**Tags 5 to 9 are reserved and rejected, not skipped.** They are the storage codec's `BYTES`,
`TIMESTAMP`, `UUID`, `ELEMENT_REF` and `VECTOR_F32`, none of which can reach a query result. Meeting
one means corruption or a version mismatch, and both deserve an error rather than a shrug.

### Temporal values: one tag, the size class is the kind

| Size class | Cypher type | Java value | Payload |
|---|---|---|---|
| 0 | `DATE` | `LocalDate` | `i64` epoch day |
| 1 | `LOCAL TIME` | `LocalTime` | `i64` nano of day |
| 2 | `TIME` | `OffsetTime` | `i64` nano of day, `i32` offset seconds |
| 3 | `LOCAL DATETIME` | `LocalDateTime` | `i64` epoch second read as UTC, `i32` nano |
| 4 | `DATETIME` at a fixed offset | `ZonedDateTime` whose zone is a `ZoneOffset` | `i64` instant epoch second, `i32` nano, `i32` offset seconds |
| 5 | `DATETIME` in a region | `ZonedDateTime` with a region such as `Europe/Vienna` | `i64` instant epoch second, `i32` nano, the zone id as a string value |
| 6 | `DURATION` | any `TemporalAmount` in years, months, days, seconds, nanos | `i64` months, `i64` days, `i64` seconds, `i32` nanos in `[0, 1e9)` |

**Fifteen** because it is the first number free in this codec, the storage codec and the dump codec
alike ([BACKUP.md §3.3](BACKUP.md)), so the three can agree on it; one tag rather than seven because
the size class carries the kind for free, and seven of the 32 type numbers spent on one family would
leave the three codecs little room to stay aligned.

**A region datetime carries its instant and its zone, not its offset.** The offset follows from the
zone's rules; if the two ends' time-zone data disagree, keeping the instant is keeping the value.

**Decoding is strict**, so one value has exactly one encoding and hostile bytes fail as
`WireFormatException`: every field is range-checked (`java.time`'s `DateTimeException` is turned into
the codec's exception), a nanosecond field outside `[0, 1e9)` is refused rather than carried into the
seconds, and a zone id must be a known region **in its canonical spelling** — a fixed offset spelt as a
region (`+01:00`, `Z`) or a non-canonical id (`UTC+1` for `UTC+01:00`) is refused. Size class 7 is refused.

**A duration decodes to a non-public `TemporalAmount`.** graph-protocol and graph-client have locked
public APIs and the client must not depend on graph-cypher, so the client sees a `TemporalAmount` in
`MONTHS`, `DAYS`, `SECONDS` and `NANOS` with an ISO-8601 `toString` identical to `CypherDuration`'s
(`P1Y2M3DT4H5M6.5S`, `PT-1.5S`, `PT0S`) and equality over the four fields. The encoder takes **any**
`TemporalAmount` whose units are among years, months, days, seconds and nanos — `CypherDuration`,
`Period`, `Duration`, or whatever the store hands back — and refuses any other unit with
`IllegalArgumentException`. On the server a duration **parameter** is turned into a `CypherDuration`
before the engine sees it (`CypherWireValues.parameters`).

**The tag bumped the protocol to version 5.** This protocol's rule is a bump for a new tag, so a
version 4 peer meets a clean `UNSUPPORTED_VERSION` at the handshake instead of a `WireFormatException`
("Unknown or reserved value tag 15") on the first temporal cell.

### Why this is not `ValueCodec`

In increasing order of decisiveness:

1. It lives in a package `module-info.java` does not export.
2. It is a **storage** format, versioned with the store. Welding the wire to it makes every wire change
   a store format bump and vice versa.
3. It has no tags for the four types that dominate this wire, and four that can never appear on it.
4. **It cannot encode what this wire has to carry.** `ValueCodec.encodeBytes` escapes anything over
   255 bytes to the blob store — `blobs.store(data)`, a *write* — and throws without one. Encoding a
   value onto a socket has no blob store and no business allocating one, so **any string over 255
   bytes is unencodable**. That settles it; the other three merely agree. (Write clauses did not change
   this: a write's *parameters* reach the store through the storage codec, which does have a blob
   store; a result being encoded outward still does not.)

**The depth limit, `MAX_DEPTH` 32, counts containers only.** A node's labels, a relationship's type and
a map's or a property map's keys are strings in positions only a string may take; the encoder writes
them without a depth, and the decoder now reads them the same way. It used to decode them as values
one level down, so a node or relationship at exactly depth 32 encoded and then failed to decode — a
result the server could send and the client could not read
(`WireValueCodecTest.testTheDepthLimitIsTheSameInBothDirections`).

**Strict UTF-8, both ways.** A string holding a surrogate without its pair has no UTF-8 encoding, and
`String.getBytes(UTF_8)` silently writes `?` for one — so `"\uD800"` and `"?"` left as the same bytes, and a
parameter reached the server as a different string with nothing to say so. The encoder now refuses one with
`WireValueCodec.UnpairedSurrogateException`, as the storage codec does; on the server a result value that holds one
(`RETURN '\uD800'` makes one) fails its stream with `TYPE` rather than leaving as `?` — the embedded API and HTTP never
answered `?`. The check is cheap on purpose: the JDK writes a lone surrogate as `?`, so only an encoding containing a
`?` byte, found eight bytes at a time, is walked char by char; walking every string halved large-row throughput.
The decoder refuses bytes that are not UTF-8 with `WireFormatException` instead of decoding them to U+FFFD, again only
re-checking a result that contains a replacement character, and decodes straight out of the frame rather than copying
the bytes first. Text the server writes *about* a failure goes through `WireValueCodec.wellFormed` instead, so an error
message quoting a bad string still reaches the client. `WireCodecPropertyTest` round-trips thousands of random values
through all of it.

**A path is checked when it is made.** Only the hop count travels, so a `WirePath` that was not n+1 nodes and n
relationships encoded into bytes the decoder read as something else; the record now refuses that shape.

### Two encoding decisions worth keeping

**Element ids are fixed 8 bytes, not `T_INT`.** A real id is packed with a kind in the high bits and
lands around 2^60, so a zigzag varint costs 9 or 10 bytes for every one. Ordinary integer *property*
values are small and do benefit from zigzag, so those keep their size classes.
`WireValueCodecTest.testAnIdCostsFewerBytesFixedThanItWouldAsAVarint` is the measurement, not the
claim.

**A lightweight edge's null id travels as a flags bit with the id field omitted**, not as a sentinel.
An edge created by `createEdge` has no record and therefore no id and no properties; zero is a
legitimate value elsewhere in this codebase, and a client cannot tell an invented id from a real one.

### An unknown type is refused, not stringified

`ResultJson` turns an unknown object into its `toString`, which silently changes a value's type. On a
typed binary wire that is a wrong answer rather than an ugly one, so the encoder throws. The gap
between the engine's type system and this one becomes a loud failure that
`WireVersusEmbeddedTest` catches.

---

## 3. The server

### One query, one thread, one view

A query is dispatched to a **platform thread** that opens the read view, produces rows, encodes them,
and hands *encoded bytes* to the socket. It never hands a snapshot or a store read to another thread.

This is not caution. `Snapshot.owner` is `final` with no transfer API, and a foreign thread **does not
get an error** — `GraphStoreImpl.read` finds a null `ThreadLocal` and silently reads current committed
data outside the snapshot, producing a result that mixes two points in time with nothing to say so.

Not virtual threads: a page fault on a mapped segment blocks the carrier, and the store is built on
mapped segments.

### Dispatch

1. Parse **on the event loop**. Parsing is pure and cheap and running is neither, so a syntax error —
   much the most common failure — costs no worker thread and no read view.
2. Acquire a permit from `maxConcurrentStreams`. **Fail fast with `OVERLOADED` rather than queue**: a
   client waiting on a queued query cannot tell a busy server from a stuck one.
3. `executeBlocking` on a **dedicated** `WorkerExecutor`, so a long scan cannot starve `/stats` and
   `/schema`, which share the default pool.

### Cancellation never closes a view from another thread

`Snapshot.close()` does not check its owner, so closing it from the cancelling thread would leave the
producer holding a closed snapshot and break **that thread's next query**. A `CANCEL` frame sets a flag
the producer polls; the view is only ever closed by the thread that opened it, on its way out of
`Cypher.stream`.

Exactly one terminal frame per stream, guarded by a CAS — the deadline, the cancel handler and the
producer's error path all race to end a stream.

### What a client may reuse is given back before the frame that says so

A stream's permit, its write gate and its stream id are all released **before** its terminal frame is written, not
after. `socket.write` from the producer only queues the frame for the event loop, so anything released after it can
still be held when a fast client has read the terminal and sent its next query: the permit came back in the
completion callback, and a client with one stream open against a limit of one was answered `OVERLOADED`; the id came
back just after the write, and a client reusing it at once — which §6 says it may — got "already running a query", a
protocol error that closed the connection. Nothing still needs any of the three by then: the view was closed and the
transaction ended on the way out of `Cypher.stream`. `WireClientEndToEndTest` reproduces both.

### Stopping a query that has not produced a row yet

A full scan reaches the sink exactly once, at the end, so a between-rows check alone would let an
abandoned query run to completion and only then notice. The abort signal is therefore consulted inside
`GraphAccess`, the engine's one seam to the store, on a fraction of touches. See
[CYPHER.md](CYPHER.md).

---

## 4. The failure this prevents, named

It is not "the server runs out of memory".

A slow client pins a worker thread **and a read view**. The open view holds back reclamation, so
retention grows with write volume × duration — about 200 MiB over 60k commits, measured. That blocks
`reclaim()` and `compact()` entirely. Past `retainedVersionBytes` the store abandons **the oldest**
view, which need not be the slow client's. **One misbehaving reader breaks a well-behaved one.**

Four bounds, each sufficient alone:

| | |
|---|---|
| Bounded concurrency | `maxConcurrentStreams`, refused rather than queued |
| A hard wall-clock deadline | No client behaviour extends it. It is also the bound on view age, because the view is opened and closed inside the query |
| No unbounded park | Every wait for a writable socket is against the deadline |
| A retention watchdog | Polls `snapshotStats().retainedBytes()` **per database** and abandons that database's oldest *reading* stream past a soft limit — so the server gives up before the *store* has to, and the client gets a typed error naming the reason instead of a `SnapshotTooOldException` surfacing somewhere else. Per database and not summed: retention belongs to one store's version chain, and a reader must not be abandoned because a different database is holding pages. **Readers only**: a write query opens no view, so abandoning one would roll back its work without releasing a byte |

A client may ask for a **shorter** deadline than the server's and never a longer one. Otherwise the
server's bound on view age would be whatever the most optimistic client asked for, which is not a
bound.

### 4.1 Writes, and the lock nobody can see

Write queries are refused with `READ_ONLY` unless the server was started with `--allow-write`, on the
event loop, before a permit or a worker thread is spent.

**A write query does not stream**, and that is what keeps this port safe rather than a cost it pays.
Its rows are produced, its transaction commits, and only then does the first byte go out — because a
client that had already received rows when the commit failed would hold a result from a transaction
that no longer exists, and bytes on a socket cannot be recalled. The consequence that matters here is
the second one: the producer parks against a slow client, and **parking while holding the engine's
single writer lock would freeze every writer in the process**, embedded callers included. By the time
anything can park, the transaction is closed.

What remains is the wait *for* that lock. `GraphStore.begin()` blocks with no deadline and no message,
so a second writer would sit inside it past its own budget and have nothing to report when it gave up.
A **permit per store** puts a bound and a name on a wait that already existed: past it the stream
answers `OVERLOADED` naming the database. It adds no serialisation — the engine's lock is the thing
that serialises — and it is keyed per store because that lock is.

`CONSTRAINT_VIOLATION` has its own code rather than folding into `STORE`: it is the one store refusal a
caller can act on, by sending different data.

---

## 5. The memory bound, which is a multiplication

```
maxConcurrentStreams × (writeQueueMaxBytes + socketSendBufferBytes)
```

**This is the number that sizes the server**, it is enforced in `WireServerOptions` against a 128 MiB
ceiling, and the three factors sit next to each other in that record because they are not independent
knobs. Defaults: 8 × (4 MiB + 256 KiB) ≈ 34 MiB.

It is spelled out this loudly because it is the known weakness of the transport that was chosen. The
trial measured gRPC holding **4.7× less** under a slow client, for the structural reason that HTTP/2's
window is a limit the *peer* grants while a write queue is a local reaction to bytes already accepted.
The framed protocol won on other grounds; this is the price, and it is only worth paying if the bound
is enforced rather than discovered.

**Two things had to be fixed before the multiplication was true**, both found by a test that measured
the number rather than trusting it:

1. **`writeQueueFull()` alone is not a bound.** `socket.write` from a producer thread *queues a task*
   for the event loop; the bytes are not in the channel's outbound buffer yet, so `isWritable()` is
   still true and the producer runs ahead. Measured: a 64 KiB write queue held **1,051,200 bytes**,
   sixteen batches past its mark. The producer now counts its own bytes in flight and that count is
   the limit; Netty's water mark remains as a second signal.
2. **The kernel send buffer is memory held for a peer that is not reading**, and Linux autotunes a
   loopback socket into the megabytes under a fast writer. A whole four-megabyte result went out
   against a client reading nothing while the write queue never once looked full. `SO_SNDBUF` is now
   set explicitly and is inside the product.

And one that did not break the bound so much as double what sat behind it: **a frame was built by growing an
eight-byte header buffer to fit its payload**, and Netty grows to the next power of two, so every 64 KiB batch was
a 128 KiB allocation — twice the bytes the write budget counts as in flight, and twice the zeroing. `Frames` now
allocates header plus payload exactly: 131,192 bytes per 64 KiB frame before, 65,744 after, and building one went
from 8–12 µs to 3–7 µs.

The client has the mirror of both: `readBufferBytes` (heap) and `socketReceiveBufferBytes` (kernel).
Crossing them pauses the socket, which closes the receive window, which is what the server discovers
as backpressure.

**One flow-control point per connection.** A plain TCP socket has one, so a consumer that stalls on
stream A stalls stream B on the same connection. The answer is a second connection, not a cleverer
client. HTTP/2 would not have had this property; that was known when the transport was chosen.

### 5.1 The resume is the dangerous half, and it cost a build

Pausing is easy: it happens on the event loop, in `queued(bytes)`, the moment the queued total crosses
`readBufferBytes`. Resuming is not, because the event that should trigger it — a consumer decoding a
batch — happens on **another thread**. `consumed(bytes)` therefore reads the paused flag off the event
loop and, if it is set, hops back onto the loop to lift the pause there.

**The whole scheme rests on one invariant**: after a pause there is always at least one unconsumed
batch still in a stream's queue, so there is always one more `consumed` call to come and lift it.
Nothing else will — there is no timer behind this, and the socket stays shut until some consumer
calls in. Two separate defects broke that invariant, and the second was hiding behind the first.

**One: `readsPaused` was not `volatile`.** It carried a comment explaining why it did not need to be —
*"only ever written from the socket's event loop, so the pause and the resume cannot cross"* — which
was true about the writes and silent about the read. The read in `consumed` is the one that decides
whether the resume is scheduled at all, and a consumer observing a stale `false` skips the hop
entirely.

**Two, and this is the one that actually hung the build: the ROWS handler published a batch before
accounting for it.** `stream.onRows(bytes)` makes the batch visible to the consuming thread;
`queued(bytes.length)` adds it to the budget and decides whether to pause. In that order a consumer
can take the batch and decrement a total the event loop has not yet incremented — so it sees a
not-yet-paused flag, skips the resume, and the increment that follows crosses the mark and pauses the
socket with no consumer callback left to lift it. When that was the last ROWS batch, the `COMPLETE`
frame behind it was never read and the consumer blocked forever on a query that had finished.
Counting before publishing restores the invariant: every byte a consumer can see has already been
added.

Fixing only the first left the test still hanging, which is the useful part of the story — the
`volatile` was necessary and the ordering was the cause, and stopping at the plausible fix would have
shipped the bug.

The symptom was nowhere near either cause. The suite was green in isolation and hung the whole reactor
build under load, inside `WireServerTest`; the thread dump is the only thing that named it — the
client parked in `LinkedBlockingQueue.take()`, the server's `graph-wire-0` worker **idle in its pool**
having already produced everything, and both event loops asleep in `epoll`. A server that is idle
rather than stuck is the tell that the lost wakeup is on the client side.

Two guards now exist so this class of defect cannot present as a stalled build again: `WireServerTest`
carries a class-level 30 second `@Timeout`, so a parked test is a red test with a stack trace, and the
parent pom sets `forkedProcessTimeoutInSeconds`, so any hung fork anywhere becomes a failure. Both
earned their place immediately: the timeout is what turned the second defect from a stalled reactor
into a 30 second failure naming the line.

**A third, on the give-back side: closing a stream while its rows were arriving.** The event loop looks a stream up,
counts a batch and then queues it; `close()` released what it found in the queue. A batch landing between the lookup and
the queueing was counted and never released — up to a batch of budget gone for good each time, and once the leaked
total sat above half of `readBufferBytes` the next pause had nothing that would ever lift it: the connection hung on
its next large result. A `discarded` flag now closes the window from both sides — `close()` sets it before emptying the
queue, the event loop checks it after queueing and gives the batch back itself.
`GraphClientLifecycleTest.testClosingStreamsMidFlightLeaksNoBudget` hung on the old code and asserts the budget is
back to zero on the new.

**The rules this leaves.** A field written on the event loop and read off it is `volatile`, and a
comment justifying otherwise has to account for the read as well as the write. And a value is added to
a shared budget **before** it is published to another thread, never after — publishing first opens a
window in which the other thread can spend what has not been counted.

---

## 6. The client

```java
try (GraphClient client = GraphClient.connect("localhost", 8081);
     RowStream rows = client.query("MATCH (a:Airport) RETURN a.code, a.name")) {
    for (Row row : rows) {
        System.out.println(row.string("a.code"));
    }
}
```

`Row` is deliberately the same shape as `CypherRow` on the embedded side, so moving code from
in-process to over-the-wire is a change of import rather than of style. The element types differ —
`WireNode` rather than `CypherNode` — because the client does not carry the query engine; the server
converts at its edge, which costs one small record per element and buys a client jar with no storage
engine in it.

Temporal cells come back as `java.time` values — `LocalDate`, `LocalTime`, `OffsetTime`,
`LocalDateTime`, `ZonedDateTime` — and a duration as a `TemporalAmount` whose `toString` is its ISO-8601
form (§2). `row.get` returns them; `row.string` and `Row.toString` print the ISO form. There is no typed
accessor for them, since adding one is a change to the locked API; `row.get(...)` with a cast is the way.
They can be sent as parameters the same way.

| Rule | Why |
|---|---|
| **Rows already sent before an error are not a result** | The stream throws at exactly the row where the `ERROR` arrived, and `close()` reports one nothing else observed. Returning them as if the stream had merely ended is truncated data with no way to tell |
| **No automatic reconnect** | Reconnecting mid-stream would silently resume against a *different* snapshot, so a result spanning the break would mix two points in time. Whether to run the query again is the caller's decision, and it is a real one |
| **A dropped connection fails every later `query()` at once** | The streams open at the drop are failed by `connectionLost`; after it, `query()` throws `GraphClientException` rather than writing to a dead socket and leaving its caller in `hasNext()` for ever. The flag is set before the open streams are swept, so a query racing the drop is caught by one or the other. A `QUERY` whose write fails fails its stream too |
| **A stream id is freed by the server's terminal frame, not by `close()`** | Frames for a cancelled stream are still in flight. Reusing the id early delivers one query's rows to another — a wrong answer rather than a crash, and therefore the worse bug |
| **A closed stream answers at once** | `hasNext`, `next`, `summary` and an unanswered `columns` throw `IllegalStateException` after `close()` on a stream that had not finished. A closed stream is unregistered, so nothing the server sends reaches it again, and they used to park in `take()` for ever |
| **A query that cannot be sent costs nothing** | It is encoded before a stream id is taken: a parameter with no wire type or a lone surrogate throws `IllegalArgumentException`, a query over the frame limit `GraphClientException`, and neither leaves a registered stream behind. They used to, and 65,535 of them made the connection refuse every query |
| **The server's reason reaches the streams it ends** | A connection ended by an `ERROR` on stream 0 fails its open streams with that frame's code — `UNAUTHENTICATED` for a user disabled while connected — not `PROTOCOL`. A `COMPLETE` that does not decode now fails its stream with the rest instead of leaving it waiting for ever |
| **Rows that arrived can be read after the connection ends** | Up to the point of the failure, which is then thrown. Reading them used to throw `RejectedExecutionException` once the client was closed, from a resume hop onto a Vert.x that was gone |
| **Not thread-safe per stream** | One stream, one consuming thread. Several streams may be consumed by several threads |

---

## 7. Security posture

**Authentication, yes. Transport security, optional.** A connection presents a credential during the
handshake and is refused without one ([AUTH.md](AUTH.md)). On the default port that credential crosses
the socket **in cleartext**; §7.1 is the second listener that does not. There is no rate limit on
queries beyond `maxConcurrentStreams`; logging in has its own, because verifying a password costs the
server ~100 ms of CPU that an unauthenticated caller can ask for.

The plaintext port still binds **loopback** by default, and neither authentication nor the existence of
an encrypted sibling is a reason to change that. The three answer different questions: a credential says
who the peer is, TLS says who the *server* is and keeps the conversation private, and only the network
under it says who could have read a cleartext credential on its way past. `GRAPH_WIRE_HOST` widens it,
deliberately.

What the surface actually is: **Cypher over an authenticated socket.** A principal can read every byte
of every database it holds a grant on, and — if the server was started with `--allow-write` *and* that
principal was granted the clause — change it. Both have to permit: without the flag a write is refused
with `READ_ONLY`, and without the grant with `UNAUTHORIZED`, and the two are distinguishable because
their remedies are held by different people. `UNAUTHENTICATED` ends the connection, like every other
handshake failure; `UNAUTHORIZED` is per stream and the connection survives, because the next query on
it may well be one this principal may run.

A refusal never says which part of a credential was wrong, and a database a principal may not use
answers `UNAUTHORIZED` with a message that does not reveal whether it exists — the same rule
`LOAD CSV` follows for a file it will not read.

The frame decoder is the part an attacker reaches first, and it is the part with a non-differential
oracle rather than only a round-trip test — see §8.

### 7.1 TLS: a second listener, not a mode

```
--wire-tls-cert=/tls/tls.crt --wire-tls-key=/tls/tls.key    # opens 8084 as well as 8081
--wire-tls-port=8084  --wire-tls-host=<addr>                # where; host defaults to --wire-host
--wire-tls-only                                             # ...and closes 8081
```

`GRAPH_WIRE_TLS_CERT`, `_KEY`, `_PORT`, `_HOST`, `_ONLY` are the same five. Both files are **PEM**,
the key unencrypted — `tls.crt` and `tls.key` in a Kubernetes `kubernetes.io/tls` secret, which is what
cert-manager and every ACME client write. A certificate with no key, or `--wire-tls-only` with no
certificate, is refused at parse time rather than producing a server that quietly stayed in the clear.

**This is the binary port only.** The HTTP port serving the browser UI and `/api/*` is unchanged and
still expects an ingress or a reverse proxy in front of it; so is the MCP port. That is not an
oversight — it is where the two differ. A browser already has a TLS story with certificate provisioning
and renewal built into the platform in front of it, and the binary port is the one thing that story does
not reach: a `GraphClient` in another datacentre speaks a raw TCP protocol no ingress can terminate
without understanding it.

**One `WireServer`, two `NetServer`s.** The stream permits, the worker pool, the write gates and the
watchdog are shared, so §5's multiplication still describes the whole server rather than one of two
halves. `WireServerOptions.maxBufferedBytes()` does not double when the second listener opens.

**Why two ports rather than one negotiated one:**

1. **The protocol does not change.** No `STARTTLS`, no capability bit, no version bump — a negotiated
   upgrade would have to be refusable by the server and demandable by the client, which is two failure
   modes and a downgrade attack to reason about, in exchange for one port number.
2. **Migration has a middle.** Open 8084, move clients over one at a time while 8081 still works, then
   set `--wire-tls-only`. A single switchable port makes that a flag day.
3. **A wrong guess fails fast.** TLS speaks first and this server does not, so a plaintext client on
   8084 gets a handshake failure rather than parking on a socket waiting for a `WELCOME`. The client
   turns "closed without answering HELLO" into a message naming `withTls`, because from the client's
   side the mistake is otherwise invisible.

**Adding a certificate never closes a port.** `--wire-tls-only` is separate and explicit, and while both
are open the server logs a warning naming the plaintext one. `graph_wire_connections{tls="false"}`
([METRICS.md](METRICS.md)) is how you know when nobody needs it any more.

**What the client verifies, and the default that matters.** `ClientOptions.withTls(...)` takes a
`ClientTls`, and there is deliberately no no-argument form: encrypting is not the decision, *what to
trust* is.

| | Trusts | Checks the name |
|---|---|---|
| `ClientTls.system()` | the JVM trust store | yes |
| `ClientTls.trusting(pem)` | that certificate, or the CA that signed it, **and nothing else** | yes |
| `ClientTls.insecure()` | anything | no |

`verifyHostname` defaults to **on**, and that is load-bearing rather than tidy: Vert.x's own
`NetClientOptions` leaves hostname verification off, so a client that merely sets `setSsl(true)`
accepts a valid certificate issued for any name at all — encrypted, and pointed at whoever answered.
`ClientTls.trusting` is strictly stronger than `insecure`, refuses everything it was not told about,
and is barely more work.

**What TLS here does not do.** It does not authenticate the client: a client certificate is not asked
for and would not be looked at if offered, and a principal comes from the `AUTH` frame and nowhere
else. Mapping a certificate subject onto a user is a second authentication *mechanism*, not a TLS
setting — `AUTH-8` in [TASKS.md](TASKS.md). It does not make `--no-auth` safe, and a login does not
make cleartext safe; neither substitutes for the other.

---

## 8. Tests

| | |
|---|---|
| `WireVersusEmbeddedTest` | **The load-bearing one.** 27 queries, in process and over the wire, compared **cell by cell** — nodes, materialised *and* lightweight relationships, paths, lists, maps, nulls, and a string over 255 bytes. The comparison is written out field by field rather than calling the server's own converter, because a test that used the code under test to decide the answer would agree with it however wrong it was |
| `WireDecoderHostileInputTest` | `@Tag("fuzz")`. Random bytes, every truncation offset, every single-byte corruption, a list claiming 2^31−1 elements in twelve bytes, nesting a million deep. The oracle knows nothing about the format: decode or raise `WireFormatException`, never OOM, never `StackOverflowError`, always terminate, **allocation proportional to input** |
| `WireTemporalCodecTest` | Tag 15: all seven kinds round-trip exactly, at `MIN`/`MAX`, an offset staying an offset and a region a region; `Period`, `Duration` and a hand-made amount encoded by their units, an `HOURS` unit refused; temporal values inside lists, maps and node properties; out-of-range fields, unknown, malformed, offset-spelt and non-canonical zone ids, kind 7 and every truncation refused with `WireFormatException`. `WireDecoderHostileInputTest`'s rich value carries one of each kind, so its truncations and corruptions reach them too |
| `FrameReaderTest` | Reassembly against the delivery patterns TCP actually produces: a frame split at every byte boundary, three frames in one chunk, a header claiming two gigabytes |
| `WireServerTest` | The mechanics — overload, cancel, deadline against a client that stopped reading, an oversized frame taking one connection and not the server, and `openViews() == 0` after every test. **Class-level `@Timeout(30s)`**: these tests block on a socket, so a flow-control defect presents as a parked thread rather than a failed assertion, and without a deadline it stalls the whole build instead of failing (§5.1) |
| `WireServerTest` (writes) | A write refused without the flag and accepted with it; the counters in `COMPLETE`; a failed write rolled back with the writer lock returned; two connections writing to one database both finishing; and no view *and no held lock* left behind |
| `WireVersusEmbeddedTest` (writes) | Eleven write statements run embedded against one database and over the wire against another seeded identically, comparing both the counters and a fingerprint of the resulting graph — with a control asserting the fingerprint can tell two graphs apart |
| `QuerySummaryTest` | The `COMPLETE` payload: round trip, every truncation offset refused, trailing bytes ignored |
| `WireServerOptionsTest` | The multiplication in §5, enforced |
| `WireServerTest` (databases) | Two connections to two databases from one process seeing different data; an unknown database and a name that is not a name both refused at the handshake |
| `WireServerTest` (handshake) | A 1 MiB header before `WELCOME` refused from the header alone; a peer that never says `HELLO` disconnected at the deadline while an idle connected client is not; a `query()` after the server went away failing instead of hanging |
| `WireAuthTest` (handshake) | `HELLO` answered `AUTH_REQUIRED` whether or not its database exists, and `NO_SUCH_DATABASE` only after the credential; a burst of 4×cores `AUTH` frames partly answered `OVERLOADED` rather than queued; a revoke, a re-grant and a disable each reaching an already-open connection |
| `WireTlsTest` | §7.1 end to end against a certificate generated per test: a query over the encrypted listener, both listeners serving one store at once, a login crossing the encrypted one, `close()` stopping both acceptors, and `--wire-tls-only` opening no plaintext listener. Weighted towards **refusal** — an untrusted certificate, a certificate for the wrong name, a plaintext client on the encrypted port and the reverse — because those are the paths where a mistake produces something that looks like it is working. Same class-level `@Timeout` and event-loop count as `WireServerTest`, for the same two reasons |
| `ClientTlsTest` | What the client verifies, and that a copy method does not drop the transport. Cheap assertions that earn their place because the value they pin down — hostname verification on — is one Vert.x defaults the other way |
| `ClientDependencyTest` | Fails if graph-core or graph-cypher ever reaches the client's classpath. Checked by **loading**, not by reading a pom, because a transitive arrival would pass a pom check |
| `WireCodecPropertyTest` | Thousands of seeded random values — nested lists, maps, nodes, lightweight and materialised relationships and paths inside one another, every integer size-class edge, NaN and -0.0 by their bits, strings across all of Unicode — round-trip exactly and consume exactly their bytes, alone and back to back. Random frames of every type, 0 B to 1 MiB, survive random chunking; a frame of exactly 16 MiB is accepted and one byte more refused; the per-header ceiling is re-read between coalesced frames. Lone surrogates refused at every offset, malformed UTF-8 refused, an ill-shaped path refused |
| `GraphClientLifecycleTest` (graph-client) | The client against `ScriptedServer`, a server that misbehaves on cue: a closed stream asked again, a connection dropped mid-result, an error after rows, a truncated `COMPLETE`, an undecodable batch, an unexpected frame, a silent server, a connection-level error's code, closing and interrupting a blocked consumer, rows for an unknown stream, an abandoned stream holding the budget, 65,536 unencodable queries, and 16 threads sharing one client. **`` in `SEPARATE_THREAD` mode**: `CompletableFuture.join` is not interruptible, so a same-thread timeout could not end two of the hangs this found |
| `WireClientEndToEndTest` | The client against a real server: back-to-back queries at the stream limit never refused, a stream id reused the instant it ends, back-to-back writes, twelve threads on one client, every parameter type through `RETURN $p` — the six temporal kinds included, a duration sent as `CypherDuration`, `Period` and `Duration` — a lone surrogate answered `TYPE`, a write cut off by a disconnect all or nothing, a failed write rolled back whole, a database dropped under an open connection, and a query in flight at shutdown answered within the grace period |

**Why a fuzz oracle and not only a differential test.** [TESTING.md](TESTING.md) §0 records the finding:
a differential test compares two implementations and therefore finds only the mistakes they do not
share. This project's two edge codecs were written from one description by one person, so they shared
their bugs, and `cargo fuzz` found two in seconds that it structurally could not.

It earned its place here immediately. Run against this codec for the first time it found **three real
defects within seconds**: a bounds check written `pos + count > limit` that overflows for a count near
`Integer.MAX_VALUE` and let a 2 GiB allocation through, and two `ClassCastException`s escaping the
decoder on corrupt input.

---

## 9. Where things are

| | |
|---|---|
| `graph-protocol/…/Frames.java`, `FrameReader.java` | The header, the frame table, reassembly |
| `graph-protocol/…/WireValueCodec.java` | The codec, on `byte[]` so it can be fuzzed without a transport |
| `graph-protocol/…/WireNode.java`, `WireRelationship.java`, `WirePath.java` | The element types the client sees |
| `graph-protocol/…/WireError.java`, `Protocol.java` | Error codes; version and default port |
| `graph-protocol/…/QuerySummary.java` | The `COMPLETE` payload: how the query went and what it changed |
| `graph-client/…/GraphClient.java`, `RowStream.java`, `Row.java` | Connect, stream, read |
| `graph-client/…/ClientOptions.java`, `QueryOptions.java` | The client's two memory bounds; the per-query deadline |
| `graph-client/…/ClientTls.java` | What a client trusts, and whether it checks the name |
| `graph-server/…/wire/WireServer.java` | Dispatch, the producer, the four bounds, the watchdog |
| `graph-server/…/wire/WireServerOptions.java` | §5, enforced |
| `graph-server/…/wire/WireTlsOptions.java` | §7.1: the certificate, the second port, and why it is a second port |
| `graph-server/…/wire/CypherWireValues.java` | The conversion at the server's edge, and why it exists |

---

_Git HEAD revision: `f2b6d5f`_
_Last updated: 2026-11-06 (temporal values, tag 15; protocol version 5)_
