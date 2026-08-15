# Transport trial — the decision, written before the numbers

**Status: criteria registered, results empty.** Nothing below the line has been measured yet. This
file exists in this state, on its own, so that the ordering is a fact in the git history rather than
a claim made afterwards.

This project has a habit of measurements coming out against the thing that was expected to win — the
Rust edge codec was 1.38x faster and shipped anyway as *not worth it*; the Rust CRC32C turned out
**7.8x slower** than the JDK's and the earlier "200x" figure had to be withdrawn as a comparison
between two implementations rather than two languages; a SWAR varint decoder was 2.3x *slower* than
the loop it replaced. Pre-registering the criteria is what makes that habit productive rather than
embarrassing, because it removes the option of deciding first and choosing the metric afterwards.

---

## What is being decided

Which transport carries a streamed, read-only Cypher result to a Java client:

| Arm | |
|---|---|
| **A — framed** | Length-prefixed binary frames over a Vert.x `NetServer` / `NetClient` |
| **B — grpc** | `vertx-grpc-server` / `vertx-grpc-client` 5.0.11, using `GrpcMessageDecoder.IDENTITY` and `GrpcMessageEncoder.IDENTITY` over raw `Buffer` |

**Both arms carry byte-identical row payloads from the same codec.** Only the framing differs. That
is what makes a bytes-per-row comparison mean anything: it isolates framing overhead instead of
comparing two serialisation formats and calling the difference transport.

The streaming core — thread confinement, view lifetime, backpressure, cancellation — is written
**once**, in `trial/core`, behind a `RowTransport` interface. Building it twice would measure which
harness was written more carefully.

## What is not being decided

**Arm B is gRPC framing without a `.proto`.** It therefore delivers no cross-language interop, which
is the usual reason anyone chooses gRPC — a Go client would still have to hand-implement this
project's value codec. What it does deliver is HTTP/2 flow control, multiplexing, and standard
deadline and cancellation semantics.

If the project later decides to publish a `.proto` and support non-JVM clients, **this trial's
premise is void** and the choice must be made again on different grounds. Recording that here so
that a future reader does not mistake this for a decision that gRPC is the wrong technology.

---

## Hard gates

Failing any one of these disqualifies an arm regardless of every other number.

| | Gate |
|---|---|
| **G1** | The GraalVM native image builds via `tools/native-image.sh` and serves 1M rows correctly |
| **G2** | Server-side buffered bytes stay **under 64 MiB** across 8 concurrent streams against a deliberately slow client, for a whole 5M-row run |
| **G3** | Shaded-jar delta over the current `graph-server` jar is **under 4 MiB** |
| **G4** | All five row shapes round-trip byte-exactly, and the frame decoder passes the hostile-input oracle |

G1 is a gate rather than a score because the native binary already exists and works
(`spec/GRAALVM.md`); a transport that breaks it is taking something away. G2 is the gate that
matches the actual requirement — streaming without caching on the backend — and is the one where the
two arms can genuinely differ, since HTTP/2 has flow control and a hand-managed write queue does not.

## The tiebreak, if both arms clear the gates

> **Arm A wins unless arm B is within 10% on `narrow` throughput, and within 10% on bytes/row, and
> within 2x on time-to-first-row p99, and its shaded-jar delta is under 1 MiB.**

Arm B must not merely tie. `vertx-grpc-common` 5.0.11 declares `protobuf-java` and
`protobuf-java-util` at **compile scope, non-optional**, and `protobuf-java-util` pulls **guava**,
gson and error_prone in turn — none of which arm B uses, because it carries raw `Buffer`s. Paying
that for interop that is not being delivered needs the transport to be free in every other respect.

## What makes arm B win outright

- Arm A fails **G2** and HTTP/2 flow control bounds the buffering for free.
- Arm A fails **G1**, or needs a native-image substitution comparable to the netty
  `CleanerJava24Linker` one already carried in `spec/GRAALVM.md`.
- A separate, explicit decision is taken to publish a `.proto` for non-JVM clients — see *What is not
  being decided*.

## The qualitative tiebreak

Registered in advance so it cannot be invented after the fact: **HTTP/2 frame decoding is Netty's and
is fuzzed by the world; a hand-rolled frame decoder is new attack surface on a port with no
authentication.** If arm A's decoder cannot pass G4's oracle cleanly, that counts against it even
where it wins on every number.

## The outcome I expect, registered so a microwin cannot drive the decision

> **If both arms exceed 2M rows/s on `narrow`, throughput is struck from the decision entirely.**

At that rate the transport is not the bottleneck for any realistic query — the Cypher engine is, by
about two orders of magnitude, and a query that scans 20,000 nodes already takes milliseconds. The
choice would then rest only on G1, G2, G3 and decoder safety. I expect this to be what happens, and
saying so now is the point of saying it at all.

---

## Method

Per `spec/TESTING.md` §7, which notes that JMH is not available here.

- **One clean JVM per arm.** Two socket stacks in one JVM pollute each other's JIT profile, which is
  exactly the sort of thing that manufactures a 10% difference out of nothing.
- Explicit warmup pass, discarded, then 5 measured passes; median reported with the min/max spread.
- Seeds printed, per `spec/TESTING.md` §9.
- Mock rows only. **No `GraphStore` and no `Cypher` anywhere in this module** — with the engine in
  the loop the benchmark would measure the engine.
- `tools/wire-trial.sh` runs the matrix and writes the Results section below.

### Row shapes

| Shape | Row | Isolates |
|---|---|---|
| `narrow` | `(Long, String(16), Double)` | raw framing throughput |
| `node` | one node, 2 labels, 4 properties | the realistic row |
| `wide` | 20 mixed scalars | per-cell tag overhead |
| `path` | one path of length 3 | nesting depth |
| `big` | one 64 KiB string | large values, fragmentation, frame limits |

### Measurements

1. Throughput, rows/s and MiB/s, 5M rows, client checksums every cell so nothing is optimised away.
2. Time-to-first-row, p50 and p99 over 1000 sequential single-row queries.
3. Bytes/row on the wire, per shape — deterministic, so reported exactly.
4. Server-side buffered bytes under a slow client, sampled every 10 ms, maximum reported.
5. Allocated bytes per row on the producer thread, and GC count and time.
6. Shaded-jar size delta, exact bytes.
7. Native image: builds or not, build time, binary size, and the metadata diff needed to get there.

---

## Results

_Empty. To be filled in by `tools/wire-trial.sh` once the trial module is built._

## Decision

_Empty._
