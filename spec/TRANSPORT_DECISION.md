# Transport trial — the decision, written before the numbers

**Status: decided and promoted — arm A, the custom framed protocol.** See [Results](#results),
[Decision](#decision) and [What promotion changed](#what-promotion-changed) at the foot of this file.
The shipped protocol is specified in [WIRE.md](WIRE.md); this file is the record of *why* it is that
one, and is history rather than specification.

Everything from here down to the Method section was written and **committed on its own, in commit
`16c429c`, before a single line of the measuring code existed** — so the ordering is a fact in the git
history rather than a claim made afterwards. Two of the things registered there turned out to be wrong,
and both are corrected in the Results rather than quietly amended above.

**The harness is gone and the numbers are not reproducible from this repository.** The
`graph-wire-trial` module was throwaway by design and was deleted when the winner was promoted, but it
was never committed on its way past — so what survives is this file: the criteria, the measurements,
and the reasoning. Anyone revisiting the decision would have to rebuild the harness from the
[Method](#method) section, which is written in enough detail to make that possible. That is a worse
outcome than committing it and then deleting it, and it is recorded here rather than glossed over.

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

`tools/wire-trial.sh 1000000`, one clean JVM per arm, 5 measured passes after a discarded 200k-row
warmup, seed 20260815, JDK 25+36. `big` is capped at 20k rows because each row is 64 KiB.

| Metric | framed | grpc | |
|---|---|---|---|
| narrow, rows/s | **14,824,116** | 14,348,749 | grpc −3.2% |
| node, rows/s | **3,477,762** | 3,312,189 | grpc −4.8% |
| wide, rows/s | 4,937,729 | **5,015,346** | grpc +1.6% |
| path, rows/s | 584,958 | **612,047** | grpc +4.6% |
| big (64 KiB rows), rows/s | **21,466** | 20,253 | grpc −5.6% |
| bytes/row, narrow | 36.01 | 36.00 | identical |
| bytes/row, node | 92.91 | 92.90 | identical |
| bytes/row, path | 549.99 | 549.93 | identical |
| time to first row, p50 | **75.6 µs** | 166.0 µs | grpc 2.2x |
| time to first row, p99 | **400.5 µs** | 718.8 µs | grpc 1.79x |
| producer allocation, bytes/row (node) | 519.2 | **426.4** | |
| **slow client, summed peak server buffered** | 41,761,720 | **8,916,296** | **grpc holds 4.7x less** |

Checksums matched across arms on every shape, which is what proves the payloads were byte-identical
and the comparison was of framing alone.

### The gates

| | framed | grpc | |
|---|---|---|---|
| **G1** native image builds and serves | **pass** | **pass** | 44 MB, 34.9 s, both arms served correctly with matching checksums |
| **G2** buffered under 64 MiB | **pass**, 41.8 MB | **pass**, 8.9 MB | |
| **G3** jar delta under 4 MiB | **pass**, ~0 | **pass**, ~2.4 MB | |
| **G4** round-trip + hostile-input oracle | **pass** | **pass** | one codec, so one answer |

**G1 does not discriminate, and the reason is worth recording.** Both arms hit the same wall — netty's
`CleanerJava24Linker` builds its FFM handles reflectively and the analysis cannot compile it — and both
are fixed by the substitution `graph-server` already ships for exactly this
([GRAALVM.md](GRAALVM.md) §5). It is a property of Vert.x on JDK 25, not of either
transport.

### Two corrections to what was registered above

1. **The guava claim was wrong.** This file states that `protobuf-java-util` pulls guava. It did once;
   at 4.29.3 it does not. The real transitive set is protobuf-java (1.87 MB), gson (258 KB),
   protobuf-java-util (76 KB), three vertx-grpc jars (~128 KB) and three annotation jars — about
   **2.4 MB, not the ~6 MB predicted**. So arm B *passes* G3 where the registered reasoning expected it
   to fail. Recording this because a pre-registration that gets quietly corrected afterwards is worth
   nothing.
2. **The expected outcome happened, and then some.** Both arms cleared 2M rows/s by a factor of seven,
   so the registered rule fires and **throughput is struck from the decision**. It deserves striking:
   the spread across arms is 3–5%, which is inside the run-to-run spread of a single arm (framed narrow
   ranged 6.0M to 16.5M across its own five passes) — and **in the native image the ordering reverses**,
   grpc 9.4M against framed 7.3M. Any decision made on these numbers would have been a decision made on
   noise.

## Decision

**Arm A, the custom framed protocol**, by the tiebreak exactly as registered:

> Arm A wins unless arm B is within 10% on narrow throughput **and** within 10% on bytes/row **and**
> within 2x on time-to-first-row p99 **and** its shaded-jar delta is under 1 MiB.

Arm B satisfies the first three — 96.8% of arm A's throughput, identical bytes/row, 1.79x on p99 — and
fails the fourth at ~2.4 MB against a 1 MiB threshold. The threshold was set where it was because gRPC
carrying raw buffers with no `.proto` delivers **none** of the cross-language interop that is the usual
reason to pay for it, so it had to cost nothing rather than merely tie. It costs 2.4 MB.

### What arm B was better at, which the decision does not erase

**Backpressure.** Arm B held 8.9 MB where arm A held 41.8 MB for the same work — 4.7x less. That is the
one place the two are genuinely different rather than differently spelled: HTTP/2's window is a limit
the *peer* grants, while a write queue is a local reaction to bytes already accepted. Arm A passes G2,
but note *why* it passes: its ceiling is `writeQueueMaxSize × concurrent streams`, which is 32 MB by
configuration here and would be 320 MB if someone raised the queue to 40 MB or allowed 80 streams.

**So the promoted implementation must treat that ceiling as a real limit and not an accident**: the
concurrent-stream count and the write-queue size together are a memory bound, they belong in the server
options next to each other, and the documentation has to say that multiplying them is how you size the
server. gRPC would have made that mistake impossible. That is the price of the 2.4 MB, and it is worth
paying only if the bound is actually written down and enforced.

### If this is revisited

The premise dies the day a non-JVM client is wanted. At that point gRPC's cost buys something real, the
2.4 MB stops being dead weight, and this decision should be made again rather than cited.

---

## What promotion changed

The winning arm became `graph-protocol`, `graph-client` and `io.metaloom.graph.server.wire`, specified
in [WIRE.md](WIRE.md). Three things came out of building it for real that the trial did not show, and
they are here because a decision record that stops at the decision is only half of one.

**The condition attached to the decision was met, and it took more than a paragraph.** The ceiling is
now `maxConcurrentStreams × (writeQueueMaxBytes + socketSendBufferBytes)`, validated in
`WireServerOptions` against a hard 128 MiB, with a message that names the multiplication — and
`WireServerOptionsTest` fails if any of that stops being true.

**Two defects had to be fixed before that multiplication was true at all**, and both were found by a
test that *measured* the number rather than trusting the mechanism:

1. `writeQueueFull()` alone is not a bound. `socket.write` from a producer thread queues a task for
   the event loop, so the bytes are not in the channel's outbound buffer yet and `isWritable()` is
   still true. A 64 KiB write queue held **1,051,200 bytes** — sixteen batches past its mark. The
   producer now counts its own bytes in flight.
2. The kernel send buffer is memory held for a peer that is not reading, and Linux autotunes a
   loopback socket into the megabytes. A four-megabyte result went out in full against a client
   reading nothing, while the write queue never once looked full. `SO_SNDBUF` is now set explicitly
   and is inside the product.

Neither is an argument against the decision — HTTP/2 flow control would have made the first
unnecessary and left the second exactly as it was — but both are evidence for the shape of the
condition: the trial measured buffering under a slow client and the promoted code had to be measured
again, because the trial's arm and the shipped server are not the same program.

**The trial's own slow-client figure was measured under the same two defects**, so arm A's 41.8 MB was
if anything flattering to the mechanism rather than to the arm. It does not change the ordering — gRPC
held less for a structural reason that neither fix touches — and the decision stands as recorded.
