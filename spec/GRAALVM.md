# GraalVM Native Image

Whether this project — which is built on the Foreign Function and Memory API and on memory-mapped
files — can be compiled to a standalone native binary, and what it takes.

**It can, and it is built.** `mvn -Pgraalvm verify` produces **two** binaries:

| Binary | From | Size | What it is |
|---|---|---|---|
| `graph-server/target/native/graph-server` | `graph-server`, profile `graalvm` | 55 MB | The server: Cypher API and the React UI over a real store, with the Rust crate loaded and `io_uring` reported available inside the image |
| `graph-cli/target/native/metaloom-graph-cli` | `graph-cli`, profile `graalvm` | 38 MB | The backup tool of [BACKUP.md](BACKUP.md): opens a store, walks it under a snapshot, writes and reads a dump |

The second one is the artifact this whole page argues for. [§9](#9-what-it-costs-and-what-it-buys)
measures the trade and it is genuinely two-sided for a server — but a CLI runs once, does one pass
and exits, so it never reaches the warm-query row that is the case against. It also **costs nothing
to add**: everything the engine needs from native-image lives in `graph-core`'s jar, so a second
executable built on that jar inherits it. See [§10.1](#101-the-cli).

**FFM was not the obstacle.** That was the open question going in, and the answer is that GraalVM 25
supports every part of the API this engine uses: downcalls against libc, downcalls into a
`dlopen`ed shared object found at run time, `FileChannel.map` into a `MemorySegment`, and
`Arena.ofShared`. The engine's own native layer needed **no code changes at all** — only a metadata
file and one build flag.

What did cost work was, in order of how long it took to understand:

| Obstacle | Fix | Section |
|---|---|---|
| **netty** builds its FFM handles reflectively, and the analysis cannot compile that | One substitution class | [§5](#5-the-netty-substitution) |
| Every downcall needs its signature declared at build time | `reachability-metadata.json`, guarded by a test | [§3](#3-downcall-registration) |
| `Arena.ofShared` is off by default | `-H:+SharedArenaSupport` | [§4](#4-shared-arenas) |
| logback objects in the image heap | build-time init, with a consequence | [§6](#6-logging-is-baked-in) |
| The UI bundle is a classpath resource | one glob | [§7](#7-reflection-and-resources) |

And what it buys, measured in [§9](#9-what-it-costs-and-what-it-buys): **4.3x faster to first
response, half the resident memory — and 3x slower on a warm query.** The last one is not a
footnote. For a server whose work is scanning a store, it is the number that decides whether the
native build is the right artifact.

---

## 1. The Question

The engine addresses its data through `MemorySegment`s over mapped files and reaches the kernel
through FFM downcalls ([NATIVE_FFM.md](NATIVE_FFM.md)). Native image compiles a closed world ahead of
time. The two look incompatible: a downcall stub is generated machine code, and native image has no
compiler at run time to generate one with.

The resolution is that GraalVM generates the stubs **at build time**, from a list of function
signatures it is given. So the constraint is not "no FFM" but "no FFM signature the build did not
know about" — and everything in this file follows from that one sentence.

## 2. What Was Verified, and How

Before touching the real build, a 40-line probe exercised each capability the engine depends on in
isolation. Its whole value was answering the question in five minutes instead of inferring an answer
from a 200-line stack trace out of Vert.x.

| Probe | In a native image |
|---|---|
| `Linker.downcallHandle` on a `static final` field, called with `invokeExact` | works |
| A downcall taking `ADDRESS`, given an `Arena`-allocated segment | works |
| `FileChannel.map(READ_WRITE, …, Arena.ofShared())`, write then read back | works |
| `SymbolLookup.libraryLookup(path, arena)` on a `.so` **found at run time** | works |

The last one matters most and is the least obvious: the Rust crate is not linked into the binary. It
is a resource, unpacked to a temporary file by `NativeCapabilities.fromClasspath` and `dlopen`ed —
and that still works, because registration is by *signature*, not by symbol or library. A second
probe against the real engine classes confirms it end to end:

```
crate status    = ABI 2
capabilities    = crc32c,edge-codec,xxh3,lz4,io_uring
libc fallocate  = true
libc direct io  = true
libc madvise    = true
```

That output is from the native binary, not the JVM. The ABI negotiation of
[NATIVE_FFM.md §3](NATIVE_FFM.md) — version checked before any other symbol, mismatch refused —
happens inside the image exactly as it does on the JVM.

The same status is now on `/api/stats` as `nativeLayer`, which is the only way to ask a running
binary which of the two paths it took.

## 3. Downcall Registration

Every distinct `FunctionDescriptor` has to be declared in

```
graph-core/src/main/resources/META-INF/native-image/io.metaloom.graph/graph-core/reachability-metadata.json
```

The file is in **graph-core**, not in the server, on purpose: the registrations are a property of the
engine, so anything that embeds graph-core in an image gets them without knowing they exist.

```json
{ "returnType": "jlong", "parameterTypes": ["jint", "void*", "jlong", "jlong"] }
```

Three things about that syntax are worth writing down, because none is guessable:

- **The layout names are the keys of `Linker.canonicalLayouts()`**, not the names of the
  `ValueLayout` constants. `JAVA_INT` is `jint`, `JAVA_LONG` is `jlong`, and **`ADDRESS` is
  `void*`** — `pointer` is rejected with `Unknown value layout: pointer`.
- **A `comment` inside a downcall entry is a fatal parse error** (inside a resource entry it is a
  warning). Every explanation therefore lives in the file's top-level `comment` array, including the
  table mapping each shape back to the function and class it belongs to.
- **Entries are shapes, not functions.** `close(2)` and `fsync(2)` are one entry. The seventeen
  entries the engine declares plus netty's two collapse to the **16 stubs** the build reports,
  because GraalVM deduplicates further at the ABI level, where `void*` and `jlong` are the same
  machine argument.

### 3.1 The drift guard

Adding a libc binding and forgetting the metadata breaks nothing visible: the JVM build passes, the
native build passes, and the omission surfaces as a `MissingForeignRegistrationError` the first time
that particular call is made — which for `statx` or `FICLONE` is a path a smoke test never reaches.

`ForeignDowncallMetadataTest` closes that gap. It reads the five classes that build descriptors,
extracts every `FunctionDescriptor.of(...)` by matching parentheses rather than by regex, and
compares the set against the JSON **in both directions** — a missing entry is a latent crash, a stale
entry is a false claim in the one file a reader consults to find out what the engine calls.

It runs in the ordinary `mvn test`, on any JDK, with no GraalVM installed. That is the point: the
metadata is checked by the build everyone runs, not by the build almost nobody runs.

## 4. Shared Arenas

`SegmentFile.open` maps every segment file through an `Arena.ofShared()` — shared because the mapping
outlives the thread that made it and is read from many. In an image that fails at run time:

```
UnsupportedFeatureError: Support for Arena.ofShared is not active: enable with -H:+SharedArenaSupport
```

Note *when* it fails: the mapping, the write and the read all succeed, and it is **closing** the arena
that throws. A smoke test that maps a store and never closes it would pass.

The flag is still experimental in GraalVM 25, so it needs the unlock. Both travel in graph-core's
`native-image.properties`, next to the registrations, for the same reason:

```
Args = -H:+UnlockExperimentalVMOptions -H:+SharedArenaSupport --enable-native-access=ALL-UNNAMED
```

## 5. The netty Substitution

This is the only thing in the whole exercise that required writing code, and it is not the project's
own code.

Netty allocates direct buffers through `io.netty.util.internal.CleanerJava24Linker`, which calls libc
`malloc` and `free` over FFM. Netty compiles against Java 8, so it cannot name `java.lang.foreign` in
its source and builds the two handles **reflectively**. The analysis cannot see through that:

```
Error encountered while parsing io.netty.util.internal.CleanerJava24Linker.malloc
  unexpected input could not be handled: linkToNative
```

**Registering the descriptors does not help**, and understanding why is the useful part. Registration
lets a *run-time* `Linker.downcallHandle` call find a stub. It does not teach the static analysis how
to compile a `MethodHandle.invokeExact` whose receiver it cannot resolve. The engine's own downcalls
are unaffected precisely because they name the API directly.

[`graph-server/src/nativeimage/java`](../graph-server/src/nativeimage/java) replaces those two methods
with the same two libc calls, written the way the image can compile them. **It is not a disable:**
netty keeps its direct buffers and keeps freeing them; only the route to `malloc` changes.

### 5.1 The wrong fix, and what it taught

The first attempt substituted `isSupported()` to return `false`, so netty would pick a different
cleaner. It built. The binary then started, logged its port, accepted connections — and returned an
empty reply to every request, with **nothing in the log**.

Two lessons, both general:

1. **Substitutions do not apply to build-time class initialisation.** Netty's `PlatformDependent` is
   initialised at build time, so it chose its cleaner on the builder JVM, where `isSupported()` was
   still netty's own. The image held a `CleanerJava24Linker` selected before the substitution could
   answer.
2. **A failure on a netty event loop is silent here.** The exception came from
   `AbstractNioByteChannel.read` allocating a buffer, and netty logged it through an internal logger
   that produced nothing in this image. It was found by building a ten-line bare Vert.x server as a
   separate image with an explicit `exceptionHandler` — a 25-second build to iterate against, instead
   of a 40-second one.

### 5.2 Its blast radius

The substitution is why `-Pgraalvm` exists as a profile rather than being pure metadata. It has to be
compiled, which means:

- **A jar built with `-Pgraalvm` is native-image ready; a jar from a plain `mvn package` is not.**
  That is the only difference between the two, and `native-image -jar` on the plain one stops at
  `CleanerJava24Linker`.
- The GraalVM `svm` dependency is `provided` and declared **inside the profile**, so an ordinary
  build never resolves it and an offline build is unaffected.
- `src/nativeimage/java` is added as a source root only by the profile, so the test suite never sees
  it.

Delete the class the day netty ships FFM metadata of its own.

## 6. Logging Is Baked In

Netty holds its loggers in `static final` fields of classes native image initialises at build time,
so a logback `Logger` ends up in the image heap:

```
An object of type 'ch.qos.logback.classic.Logger' was found in the image heap
```

The fix is `--initialize-at-build-time=ch.qos.logback,org.slf4j`, in the server's
`native-image.properties`. **The consequence is that the logging configuration is compiled in**: the
binary logs at the level the build chose, and `-Dlogback.configurationFile` at run time does nothing.
Anyone who needs a different level in the binary changes the configuration and rebuilds.

## 7. Reflection and Resources

Vert.x, netty and Jackson need reflection metadata, and there is no point deriving it by hand. It was
recorded by running the shipped jar under the tracing agent and exercising every route:

```bash
java -agentlib:native-image-agent=config-output-dir=<dir> \
     -jar graph-server/target/graph-server.jar --demo /tmp/store 8123
# then: /api/stats, /api/schema, a table query, a graph query, a syntax error, the index page, the assets
```

89 reflection entries, edited by hand afterwards in two places:

- **`webroot/**`.** The agent records the files a run *happened* to request. It recorded
  `webroot/index.html` and nothing under `assets/`, because Vert.x's `StaticHandler` reached those by
  a path the agent does not see. A binary built from the unedited output serves the page and 404s the
  JavaScript.
- **The agent does not see the engine's downcalls at all.** It recorded netty's two, because netty
  reaches `Linker.downcallHandle` reflectively, and none of the engine's seventeen, because those are
  direct calls. Anyone who assumes the agent's `foreign` section is complete gets a binary that dies
  on the first `pread`.

### 7.1 The JCA provider, which no green build proves anything about

`PasswordHash` derives with `SecretKeyFactory.getInstance("PBKDF2WithHmacSHA512")`, and a JCA service
lookup reaches its provider **reflectively**. So a missing registration fails at **run** time under
`--no-fallback`, with `NoSuchAlgorithmException`, and at no earlier point: `mvn verify` stays green and
the binary cannot verify a password. The first symptom in the field is an operator concluding they have
forgotten it.

The registrations live in **graph-core's** `reachability-metadata.json`, not the server's, because both
binaries need them — `metaloom-graph-cli auth reset-admin` generates and hashes a password, and §10.2's
claim that the CLI needs no metadata of its own depends on it inheriting the engine's:

```
com.sun.crypto.provider.SunJCE
com.sun.crypto.provider.PBKDF2Core$HmacSHA512
com.sun.crypto.provider.HmacCore$HmacSHA512
com.sun.crypto.provider.PBKDF2KeyImpl
sun.security.provider.SHA512
sun.security.provider.NativePRNG
```

The last two were already there, which is the evidence that this file is where such things go.

**What catches it** is the CLI smoke test in `tools/native-image.sh`, which now runs
`auth reset-admin` against a throwaway root, checks that a 24-character password came out on stdout,
and fails the build if the output mentions `NoSuchAlgorithmException`. A `--help` check would pass with
the provider missing; so would starting the server, right up until somebody logged in.

The escape hatch, if the provider ever proves hostile: PBKDF2 is about twenty lines over
`javax.crypto.Mac` with `HmacSHA512`, which is a much smaller JCA surface — and the pre-existing
`sun.security.provider.SHA` entry is evidence that path works. Do not hand-roll SHA-512.

### 7.2 TLS, which has the same shape and a different trigger

The binary port can terminate TLS ([WIRE.md §7.1](WIRE.md)), which reaches JSSE — a second reflective
service lookup, with the same property: **a provider missing from the image fails at run time and at no
earlier point.** GraalVM 21 and later detect JSSE usage and include the crypto backend automatically, so
no hand-written registration is expected here. "Expected" is the operative word, and it is why the check
is a running binary rather than a paragraph.

`smoke_server` in `tools/native-image.sh` generates a throwaway certificate with `openssl`, starts the
server binary with `--wire-tls-cert`/`--wire-tls-key`, and runs `tools/WireSmoke.java --tls` against it:
the same handshake and the same query as the plaintext check, so a pass means TLS and nothing else
changed. Without `openssl` on the path it says so and skips, rather than failing for a reason that is
not about the image.

**Two things this does not cover**, both worth knowing before exposing a native binary:

- **The trust store.** A native image bakes in the build machine's `cacerts`. That is a *client* concern
  and the server terminates rather than initiates, so it does not arise here — but a native client would
  need `-Djavax.net.ssl.trustStore` at run time, or `ClientTls.trusting(pem)`, which reads a PEM and
  consults no store at all.
- **The certificate is read once, at bind.** In a native image exactly as on the JVM. A renewal takes
  effect when the process restarts.

## 8. Building It

```bash
export GRAALVM_HOME=/path/to/graalvm-25
mvn -Pgraalvm verify                  # jar, then binary, then a smoke test against the binary
./graph-server/target/native/graph-server --demo /tmp/demo 8080
```

**`verify`, not `package`.** The shade plugin has to have finished writing the jar that native-image
reads, and two plugin executions in one phase order by declaration in a way that is easy to get wrong
and hard to notice — so the exec binds a phase later. `mvn -Pgraalvm package` builds a jar that is
native-image ready and no binary, which looks like a silent failure and is not one.

`tools/native-image.sh` does the work and can be run standalone. Unlike the cargo and npm steps,
**a missing toolchain here is a hard failure**: those two are optional capabilities the build routes
around, this one only runs because someone asked for it, and skipping what was explicitly requested
is worse than stopping.

### 8.1 The smoke test is not a formality

It seeds a demo store, runs a query that returns nodes and relationships, fetches the index page and
its hashed asset, runs a second query **over the binary port**, and prints which native path the binary
took. Every one of those covers a failure that **builds cleanly and fails at run time**: a missing
registration, an unregistered resource, a buffer allocator throwing on the event loop. `--help` would
have passed all of them.

The binary port earns its own check rather than being assumed to work because the HTTP server did:
`NetServer` is a different netty path from `HttpServer`, and the whole lesson of this file is that the
paths which break under AOT are the ones nobody exercised. `tools/WireSmoke.java` does it with plain
sockets and no classpath — a single-file source-launcher program, so it needs a JDK and nothing else,
and it doubles as an executable description of the handshake ([WIRE.md](WIRE.md)).

### 8.2 Without the Rust crate

`mvn -Pgraalvm -Dnative.skip=true verify` builds an image from a jar with no `.so` in it. It builds
without warnings — the unmatched `native/**` glob is not an error — and the smoke test passes, on the
pure-Java fallback. The fallback contract of [NATIVE_FFM.md §4](NATIVE_FFM.md) holds in a native image
as it does on the JVM.

Note the two flags read almost the same and mean opposite things: **`-Pgraalvm` is the GraalVM build,
`-Dnative.skip` is the Rust layer.** The profile was renamed from `native` for exactly this reason.

## 9. What It Costs and What It Buys

Same machine, same store, `--demo` seeding included in the startup figure, three runs each.

| | jar on JDK 25 | native binary |
|---|---|---|
| Launch to first HTTP response | 463–506 ms | **106–120 ms** |
| RSS at first response | ~160 MB | **~78 MB** |
| Artifact | 8.1 MB jar **plus a JDK** | **38 MB, nothing else** |
| Build | ~5 s | ~32 s more, ~6 GB peak RSS |
| Warm query, 6,060 nodes / 23,584 relationships | **42 ms median** | 129 ms median |

The last row is the trade. A scan-heavy query runs **3x slower** in the image, because there is no
JIT: the binary executes what the ahead-of-time compiler produced from a static profile, and never
gets faster. The first query costs about the same either way (131 ms against 134 ms) — the JVM is
still interpreting then. By the twentieth the JVM has pulled away and stays there.

So the honest reading is:

- **The binary wins where startup and footprint decide it**: a CLI, a container that scales to zero,
  a short-lived job, an embedded demo. Ten runs of a small query finish sooner in the image.
- **The jar wins for a server that stays up and answers queries**, which is what this one is for.

Profile-guided optimisation is the standard answer to the third row and has not been tried here. Nor
has `-march=native`, which the build output suggests. Both are listed in [§12](#12-not-done).

### 9.1 The same measurement for the CLI, where it comes out one-sided

Three runs each, same machine, against a three-node store — deliberately tiny, because what is being
measured is the cost of *starting*, not of walking.

| `metaloom-graph-cli` | shaded jar on JDK 25 | native binary |
|---|---|---|
| `--help` | 18–20 ms | **1–2 ms** |
| `database inspect` (reads a header, opens no store) | 25–27 ms | **1–2 ms** |
| `database backup` (opens a store, maps it, walks it, closes it) | 96–97 ms | **32–35 ms** |
| Peak RSS for that backup | 64 MB | **22 MB** |
| Artifact | 608 KB jar **plus a JDK** | **38 MB, nothing else** |

There is no row where the jar wins, because the row where the jar wins is the twentieth query and
this program does not run twenty queries. Note the third line in particular: even the command that
does real work — map every segment file, scan under a snapshot, `fsync`, close — is **3x faster**,
because at that size it is still almost entirely start-up.

The last row is the one that matters for the container image rather than for a person's terminal:
the native server image has **no JVM in it**, so `java -jar graph-cli.jar` is not a fallback there.
Before this binary existed, that image could not be backed up from the inside at all.

## 10. The Second Binary

### 10.1 The CLI

`graph-cli`'s `graalvm` profile is **fifteen lines and no metadata**, which is the interesting part.
It has no `svm` dependency, no substitution source root, and no `native-image.properties` of its own.
Everything it needs — every downcall shape, and `-H:+SharedArenaSupport`, without which every segment
file it maps fails on close — travels inside `graph-core`'s jar under
`META-INF/native-image/io.metaloom.graph/graph-core`.

That was the point of putting those registrations in `graph-core` rather than in the server
([§3](#3-downcall-registration)), and this is the first thing to test the claim: a second, unrelated
executable built on the engine compiled first time, with no metadata written for it.

The two differences from the server's profile are both absences:

- **No netty substitution**, because there is no netty. The online mode is `java.net.http`, which
  native-image compiles as it stands. So the CLI, unlike the server, does **not** need a jar built by
  `-Pgraalvm` — its shaded jar is native-image ready either way.
- **No logback**, so nothing has to be initialised at build time and
  [§6](#6-logging-is-baked-in)'s consequence does not apply.

It reads the **shaded** jar, `graph-cli/target/graph-cli-cli.jar`. The unclassified artifact is the library
`graph-server` depends on for the dump format and contains no engine; handing that one to
native-image would produce a binary that failed on the first class it loaded.

### 10.2 Its smoke test

The CLI cannot create data — it backs a database up and puts one back, and both halves need one that
already exists — so `tools/GraphSeed.java` writes it a store first, on the JVM, over the same shaded
jar that has just been compiled into the binary. A single-file source-launcher program with a
classpath, the same trade `WireSmoke.java` makes for the server.

The binary then does the whole round: `list`, `backup`, `inspect`, `restore`, `backup` again, and the
two dumps must agree on every count. It also points `--server` at a port nothing listens on (which
constructs the HTTP client, resolves a name and opens a socket — 1.2 MB of `java.net.http` that
nothing else here would exercise) and feeds `inspect` a truncated dump, which has to be **diagnosed
rather than crash**.

Two details are load-bearing and were both got wrong first:

- `-H:+SharedArenaSupport` **fails on close, not on use** ([§4](#4-shared-arenas)), so a check that
  opened a store and stopped would pass on a broken binary. The round trip closes several.
- The truncation has to cut **inside** the header. A dump's header is about 50 bytes, so a 64-byte
  prefix still parses and `inspect` correctly succeeds on a file that is nine tenths missing.

## 11. Gotchas

- **`ADDRESS` is `void*` in the metadata**, and `pointer` is rejected. The valid names are exactly the
  keys of `Linker.canonicalLayouts()`, which is worth printing once rather than guessing.
- **A `comment` in a downcall entry fails the build.** In a resource entry it only warns.
- **`Arena.ofShared` fails on close, not on use.** A test that maps and reads without closing does not
  catch it.
- **The tracing agent's `foreign` section is not a complete list.** It sees reflective linking only.
- **Substitutions do not affect build-time class initialisation.** If a library picks its
  implementation in a static initialiser that runs at build time, substituting the thing it picks
  between is too late.
- **A netty pipeline exception can be completely silent** in an image. Reproduce against a minimal
  Vert.x main with an explicit `exceptionHandler` rather than adding logging to the real server.
- **`-Dlogback.configurationFile` does nothing** to the binary; the configuration is compiled in.
- **`--no-fallback` is deliberate.** Without it, a build that cannot close the world silently emits a
  "fallback image" that requires a JVM anyway — a 38 MB file that is not what anyone asked for.

## 12. Not Done

- **No PGO, and no `-march=native`.** Either might close part of the 3x query gap. Neither has been
  measured, so neither is claimed.
- **Linux x86-64 only.** Nothing here is platform specific except the Rust crate's classifier, but no
  other target has been built.
- **The binaries are not in CI**, and the Playwright suite still runs against the jar. The smoke tests
  in `tools/native-image.sh` are what stand in for both.
- **No native `graph-client`.** It would compile — no netty, no engine — but nothing has asked for it,
  and unlike the CLI there is no image whose only option it is.
- **No `--enable-monitoring`.** The binary has no JFR and does not dump thread stacks on `SIGQUIT`,
  which was noticed while debugging §5.1 and worked around rather than fixed.
- **`io_uring` is reported available inside the image but never exercised there**, because nothing in
  the engine issues a batch yet on any runtime. See [IO_URING.md](IO_URING.md).

## 13. Key Files

| File | Purpose |
|---|---|
| `graph-core/src/main/resources/META-INF/native-image/io.metaloom.graph/graph-core/reachability-metadata.json` | Every downcall shape the engine uses, the JCA provider (§7.1), and the map back to the code |
| `tools/WireSmoke.java` | The binary port's handshake in one file, with `--tls` for the encrypted listener. **Outside the reactor**, so nothing compiles it: a protocol change breaks it silently, and it has been broken that way once |
| `.../graph-core/native-image.properties` | `SharedArenaSupport`, native access |
| `graph-server/src/main/resources/META-INF/native-image/io.metaloom.graph/graph-server/reachability-metadata.json` | Agent output: reflection, resources, netty's two downcalls |
| `.../graph-server/native-image.properties` | logback and slf4j at build time |
| `graph-server/src/nativeimage/java/…/NettyDirectBufferSubstitution.java` | The one substitution, and why |
| `graph-core/src/test/java/…/nativelib/ForeignDowncallMetadataTest.java` | The drift guard, run by every `mvn test` |
| `tools/native-image.sh` | Builds either binary and smoke tests it. `server` and `cli` are the two shorthands |
| `tools/WireSmoke.java` | The server smoke test's binary-port half: handshake, query, rows. Single-file source launcher, no classpath |
| `tools/GraphSeed.java` | The CLI smoke test's fixture: writes a store for the binary to back up. Single-file source launcher over the shaded CLI jar |
| `graph-server/pom.xml`, profile `graalvm` | Source root, `svm` dependency, the `verify` binding |
| `graph-cli/pom.xml`, profile `graalvm` | The `verify` binding, and nothing else — see [§10.1](#101-the-cli) |
| `container/Containerfile.native` | Both binaries into one image, because that image has no JVM to fall back to |

---

_Git HEAD revision: `58cd33e`_
_Last updated: 2026-08-18 (TLS on the binary port)_
