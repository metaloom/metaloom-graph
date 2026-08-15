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
| **Default port** | 8081, bound to **127.0.0.1**. HTTP stays on 8080 |
| **Surface** | Cypher, reads and writes. **Writes off unless the server has `--allow-write`**; §4.1. No schema call |
| **Security** | **None.** No authentication, no transport security. §7 |
| **Protocol version** | 3. Negotiated on connect; a mismatch is an error, not a hang |
| **Database** | Chosen per connection at `HELLO` — see [DATABASES.md](DATABASES.md) |
| **Client dependencies** | `graph-protocol` and vertx-core. **Not** graph-core, **not** graph-cypher, no FFM, no native library — asserted by `ClientDependencyTest` |

**What it bounds and what it does not**, stated plainly because the distinction is easy to overclaim:

| | Bounded by |
|---|---|
| The **encoded payload** | **This.** Rows encode and flush in batches; neither end builds the whole result |
| The **client's** memory | **This.** §5 |
| **Time to first row** | **This.** The first batch goes out after ~64 KiB or 20 ms, not after the last row is found |
| The executor's intermediate `List<Row>` | **Not this.** The engine is clause-at-a-time over a list, so a query's peak *server* memory is still its widest intermediate result. See [CYPHER.md](CYPHER.md) |
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

**The stream id** is in the header rather than in each payload because the dispatcher has to route a
frame before it has decoded anything. One connection carries many queries at once; stream 0 is the
connection itself.

| Frame | Direction | Payload |
|---|---|---|
| `HELLO` 1 | client | `i32` protocol version, value: database name (`""` = the server's default) |
| `WELCOME` 2 | server | `i32` version, `i32` max frame bytes, `i32` max concurrent streams, value: the database it resolved |
| `QUERY` 3 | client | value: query text, value: parameter map, `i64` deadline millis (0 = the server's own) |
| `CANCEL` 4 | client | empty |
| `COLUMNS` 5 | server | value: list of column names |
| `ROWS` 6 | server | `i32` row count, then `rows × columns` values |
| `COMPLETE` 7 | server | `QuerySummary`: nine `i64`s — row count, elapsed micros, then nodes created and deleted, relationships created and deleted, properties set, labels added and removed |
| `ERROR` 8 | server | `i32` error code, value: message, `i32` line, `i32` column |

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
anything else; the server answers `WELCOME` or an `ERROR` carrying `UNSUPPORTED_VERSION` or
`NO_SUCH_DATABASE`, and closes. A client that started querying without waiting would meet a version
mismatch as a stream of frames it could not read, with no explanation.

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

**Tags 5 to 9 are reserved and rejected, not skipped.** They are the storage codec's `BYTES`,
`TIMESTAMP`, `UUID`, `ELEMENT_REF` and `VECTOR_F32`, none of which can reach a query result. Meeting
one means corruption or a version mismatch, and both deserve an error rather than a shrug.

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

The client has the mirror of both: `readBufferBytes` (heap) and `socketReceiveBufferBytes` (kernel).
Crossing them pauses the socket, which closes the receive window, which is what the server discovers
as backpressure.

**One flow-control point per connection.** A plain TCP socket has one, so a consumer that stalls on
stream A stalls stream B on the same connection. The answer is a second connection, not a cleverer
client. HTTP/2 would not have had this property; that was known when the transport was chosen.

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

| Rule | Why |
|---|---|
| **Rows already sent before an error are not a result** | The stream throws at exactly the row where the `ERROR` arrived, and `close()` reports one nothing else observed. Returning them as if the stream had merely ended is truncated data with no way to tell |
| **No automatic reconnect** | Reconnecting mid-stream would silently resume against a *different* snapshot, so a result spanning the break would mix two points in time. Whether to run the query again is the caller's decision, and it is a real one |
| **A stream id is freed by the server's terminal frame, not by `close()`** | Frames for a cancelled stream are still in flight. Reusing the id early delivers one query's rows to another — a wrong answer rather than a crash, and therefore the worse bug |
| **Not thread-safe per stream** | One stream, one consuming thread. Several streams may be consumed by several threads |

---

## 7. Security posture

**There is none.** No authentication, no transport security, no rate limit beyond
`maxConcurrentStreams`.

That is why the binary port binds **loopback** by default — a new unauthenticated port should not
default to every interface. The HTTP port does not bind loopback and predates this note;
[OPERATIONS.md §9](OPERATIONS.md) carries the operational form of both.

What the surface actually is: **Cypher over an unauthenticated socket**. It can read every byte of every
database under the root, and — if the server was started with `--allow-write` — change and delete any of
it. Without the flag a write is refused with `READ_ONLY` before it reaches a worker thread, which is a
posture and not a control: it bounds what an unauthenticated peer can do, not who the peer is. Anything
beyond a trusted host needs something in front of it.

The frame decoder is the part an attacker reaches first, and it is the part with a non-differential
oracle rather than only a round-trip test — see §8.

---

## 8. Tests

| | |
|---|---|
| `WireVersusEmbeddedTest` | **The load-bearing one.** 27 queries, in process and over the wire, compared **cell by cell** — nodes, materialised *and* lightweight relationships, paths, lists, maps, nulls, and a string over 255 bytes. The comparison is written out field by field rather than calling the server's own converter, because a test that used the code under test to decide the answer would agree with it however wrong it was |
| `WireDecoderHostileInputTest` | `@Tag("fuzz")`. Random bytes, every truncation offset, every single-byte corruption, a list claiming 2^31−1 elements in twelve bytes, nesting a million deep. The oracle knows nothing about the format: decode or raise `WireFormatException`, never OOM, never `StackOverflowError`, always terminate, **allocation proportional to input** |
| `FrameReaderTest` | Reassembly against the delivery patterns TCP actually produces: a frame split at every byte boundary, three frames in one chunk, a header claiming two gigabytes |
| `WireServerTest` | The mechanics — overload, cancel, deadline against a client that stopped reading, an oversized frame taking one connection and not the server, and `openViews() == 0` after every test |
| `WireServerTest` (writes) | A write refused without the flag and accepted with it; the counters in `COMPLETE`; a failed write rolled back with the writer lock returned; two connections writing to one database both finishing; and no view *and no held lock* left behind |
| `WireVersusEmbeddedTest` (writes) | Eleven write statements run embedded against one database and over the wire against another seeded identically, comparing both the counters and a fingerprint of the resulting graph — with a control asserting the fingerprint can tell two graphs apart |
| `QuerySummaryTest` | The `COMPLETE` payload: round trip, every truncation offset refused, trailing bytes ignored |
| `WireServerOptionsTest` | The multiplication in §5, enforced |
| `WireServerTest` (databases) | Two connections to two databases from one process seeing different data; an unknown database and a name that is not a name both refused at the handshake |
| `ClientDependencyTest` | Fails if graph-core or graph-cypher ever reaches the client's classpath. Checked by **loading**, not by reading a pom, because a transitive arrival would pass a pom check |

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
| `graph-server/…/wire/WireServer.java` | Dispatch, the producer, the four bounds, the watchdog |
| `graph-server/…/wire/WireServerOptions.java` | §5, enforced |
| `graph-server/…/wire/CypherWireValues.java` | The conversion at the server's edge, and why it exists |

---

_Git HEAD revision: `e4601d1`_
_Last updated: 2026-08-15 (Vert.x 5.0.11, protocol version 3)_
