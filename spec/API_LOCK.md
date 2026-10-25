# The API Lock

How the public Java API of the library modules is held still: every element of it is written to a
committed baseline, a test fails the build on any difference, and only a deliberate, human step records a
change. The API itself is in [API.md](API.md); this file is about the process that guards it.

**Locked is not frozen.** During the alpha releases the API may still change, breaking changes included.
What the lock guarantees is that no change to it happens *by accident*: not as the side effect of a
refactoring, not by an AI agent adjusting a test until it passes, and not without a reviewable diff of
exactly what moved.

---

## 1. How It Works

```
 compiled classes ──► ApiDump.render(scope) ──► live dump ─┐
                                                           ├─► ApiDiff ──► ApiLockTest: fail on ANY difference
 <module>/api/<module>.api ──► Baseline.read (sha256) ─────┘

 tools/api-lock.sh accept <module>  ──► ApiLockTool ──► ApiDump.render ──► Baseline.write   (a person, never a test)
```

| Piece | Where | Does |
|-------|-------|------|
| `ApiDump` | `graph-api-lock` | Renders a module's public API as sorted text, one line per element, by reflection over the compiled classes |
| `LockedModule` | `graph-api-lock` | The one definition of which modules are locked and what their API is (§2) |
| `Baseline` | `graph-api-lock` | The baseline file: header, dump, and a SHA-256 over every other line |
| `ApiDiff` | `graph-api-lock` | Splits a difference into removed, changed, abstract-added (breaking) and added (compatible) |
| `ApiLock.verify` | `graph-api-lock` | The check. Reads, compares, fails. **Never writes** |
| `ApiLockTest` | each locked module | One line: `ApiLock.verify("<module>")`, in that module's own `mvn test` |
| `ApiLockTool`, `tools/api-lock.sh` | `graph-api-lock`, `tools/` | The only writer of a baseline |

`graph-api-lock` is a reactor module of its own, depending on nothing but the JDK, and every locked module
takes it in **test scope**. It is not in graph-core's test-jar, which was the other candidate, because
graph-protocol and graph-client must never see graph-core on any classpath, test scope included
(`ClientDependencyTest`): their locks would then have had to run from graph-server's suite, and
`mvn test -pl graph-client` would pass over a changed client API. With its own module, each lock runs where
its code is.

The tool and the test **cannot disagree**: there is one dumper, one scope definition, and the accept tool
runs it over the same classes with the same test classpath that surefire uses — `tools/api-lock.sh`
compiles with `-am`, so every sibling module, `graph-api-lock` above all, comes from this checkout's
`target/` directories rather than from an older jar in `~/.m2`.

---

## 2. What Is Locked

| Module | Scope | Why that scope |
|--------|-------|----------------|
| `graph-core` | Exactly the unqualified `exports` of `module-info.class` — today `io.metaloom.graph.core.api` | Read from the compiled module descriptor, not listed, so exporting a package locks it without anybody editing the scope |
| `graph-cypher` | The package `io.metaloom.graph.cypher` and `io.metaloom.graph.cypher.value`, plus the named types `ast.Ast$Query`, `io.CsvSource`, `io.CsvAccessException`, `io.CsvFormatException`, `parse.CypherSyntaxException`, `exec.CypherTypeException` | No module-info. `Cypher`, `CypherResult`, `CypherRow`, `CypherStats` and their exceptions are what callers use; a row hands back the `value` records; and the named types are the ones the top-level signatures and graph-server reach into the internal packages for — the parsed query a caller holds on to (`requiredPrivileges()`, `isWrite()`, `readsFiles()`), the CSV policy an embedder plugs in, and the exceptions a caller catches. The rest of `ast`, `exec`, `parse`, `func` and `io` is implementation |
| `graph-protocol` | The package `io.metaloom.graph.protocol` | No module-info. Every type in it is shared by the server and the client: the frames, the codec, the wire records, the error codes |
| `graph-client` | The package `io.metaloom.graph.client` | No module-info. The client, its options, rows and exceptions |

The scope is defined once, in `LockedModule`, and the resolved roots are the first lines of every dump
(`scope module … exports …`, `scope package …`, `scope type …`). **Narrowing a scope is therefore an API
change like any other**: the `scope` line disappears from the live dump and the lock fails.

Within a scope, types are found by **listing the class files** in the package directory or jar, not from a
list, so a newly added public type is caught the same way a new method is. A `type` root takes the type and
its public nested types.

**Not locked: graph-server and graph-cli.** They are applications. Their contract is their command line,
their HTTP routes and the wire protocol, each covered by its own tests (`GraphServerOptionsTest`,
`CliOptionsTest`, the route tests, `WireVersusEmbeddedTest`), and their Java classes are not something
anyone compiles against.

**Named but not locked.** A locked signature can name a type the lock does not cover. Today that is
`Ast$SingleQuery` (the component type of `Ast$Query`, deliberately opaque) in graph-cypher. graph-core had one
too until the first alpha: `Kind`, which `CompactionOptions`, `CompactionReport`, `IoStats` and
`OutOfSegmentsException` use, lived in the unexported `core.id`, so a modular caller could receive a `Kind`
but not name the type, and the lock did not record its constants. It was moved into `io.metaloom.graph.core.api`
before 0.1.0-alpha.1, so its constants and accessors are locked like the rest. graph-server and graph-cli still
reach into graph-core's unexported packages (`core.nativelib.NativeCapabilities`) from the classpath; that is
in-repository use and is not locked.

---

## 3. The Dump, and What Counts as Breaking

One line per element, `<owner> <category> <text>`, the owner being the binary name of the type
(`a.b.Outer$Inner`). Sorted by owner, then category, then text; deterministic across runs and machines.

| Category | Line text |
|----------|-----------|
| `type` | modifiers, kind (`class`, `interface`, `enum`, `record`, `@interface`), `sealed`, type parameters with bounds, generic superclass, interfaces (sorted), `permits` (sorted) |
| `annotation` | `@java.lang.FunctionalInterface` |
| `deprecated` | one per deprecated element, with `since` and `forRemoval` — its own line, so deprecating reads as an *addition* |
| `components` | a record's header, in order, with generic types and names |
| `constant` | an enum constant and its **ordinal** |
| `field` | modifiers, generic type, name, and `= value` for a compile-time constant — callers inline those, so a changed value is a binary break |
| `constructor`, `method` | modifiers (`default`, `static`, `abstract`, `final`), type parameters, generic return and parameter types, varargs as `...`, sorted `throws` clause, an annotation element's `default` |

Included: public top-level types; public and protected nested types whose enclosing types are included;
their public and protected members; public and protected members a public class inherits from a
superclass that is not itself public. Excluded: synthetic and bridge members, anything private or
package-private, anonymous and local classes, parameter names. Classes are loaded **without being
initialised**, and constant values and enum constant order are read from the class file through
`java.lang.classfile`, so dumping graph-core does not load its native library.

| Diff section | Meaning | Breaking? |
|--------------|---------|-----------|
| **removed** | a baseline line with no counterpart | yes |
| **changed** | a removed and an added line with the same owner, category and member name — a changed return type, a new parameter, a lost `default`, a reordered enum, an extra permitted subclass | yes |
| **abstract added** | a new abstract method on an existing type a caller can implement: not sealed, not final, not an enum or record, and for a class one with an accessible constructor. Also an annotation element without a default | yes |
| **added** | anything else new: a type, an overload, a default or static method, a field, a constant appended at the end | no |

### 3.1 Evolving the API compatibly

- **Add an overload** rather than change a signature; let the old one delegate.
- **Add a default method** to an interface rather than an abstract one.
- **Deprecate, then remove**: `@Deprecated(since = "0.2", forRemoval = true)` in one release, removal in a
  later one. The deprecation is an addition; the removal is the breaking change, made once and on purpose.
- **Append enum constants at the end.** Ordinals are part of an enum's API (`ordinal()`, `compareTo`, and
  anything that persisted one).
- **Records evolve by adding a constructor**, not by changing the canonical one — adding a component
  changes the canonical constructor and is breaking. [reports/API_REVIEW.md §2.3](reports/API_REVIEW.md)
  already flags the four output records as the types expected to evolve this way.
- **Seal what callers are not meant to implement.** `GraphStore`, `Transaction`, `ReadTransaction` and
  `NeighbourCursor` are ordinary public interfaces today, so under `policy: stable` adding a method to one
  is an *abstract added* break, which is honest: a caller may have written a decorator. Sealing them
  before the first stable baseline makes every later method addition compatible. Sealing is itself a
  breaking change, which is why it belongs in the alpha.

---

## 4. Accepting a Change

When `ApiLockTest` fails and the change is intended, **a person** records it:

```bash
tools/api-lock.sh accept graph-core                    # record the current API as graph-core's baseline
git diff -- graph-core/api/                            # review exactly what moved
git add graph-core/api/graph-core.api                  # commit it with the code that changed the API

tools/api-lock.sh check                                # read-only: compare every locked module, write nothing
MAVEN_ARGS=-o tools/api-lock.sh accept graph-client    # offline; Maven flags go through MAVEN_ARGS
```

The script compiles the module with `-am`, writes its test classpath with `dependency:build-classpath`,
and runs `ApiLockTool`, which prints the same removed/changed/added report the test printed and rewrites
the baseline — header, dump and checksum together, through a temporary file and an atomic move.

### 4.1 Policy

Each baseline header carries `policy: alpha` or `policy: stable`. **All four are `alpha` today.**

| Policy | `accept` records |
|--------|------------------|
| `alpha` | any change, breaking or not |
| `stable` | additions only. A removal, change or abstract addition is refused unless `--allow-breaking` is given, and then recorded under a banner warning that it needs a major version and a release note |

```bash
tools/api-lock.sh policy graph-core stable                     # raise: only on a baseline that matches the code
tools/api-lock.sh policy graph-core alpha --allow-breaking     # lower: the same decision as breaking it, so it takes the flag
```

`policy` refuses to run while the code differs from the baseline, so it cannot carry an API change in with it.
`accept` never changes the policy. A new baseline is created as `alpha` (`--policy stable` to start stable).

### 4.2 The checksum

The `sha256:` header line is over every other line of the file, each followed by a newline (so a CRLF
checkout still verifies). A hand edit anywhere — a dump line, the policy, a comment — or a truncated or
half-merged file fails the test with *"fails its sha256 check"*, even when the edited dump would have
matched the code. `accept` refuses to overwrite such a file without `--allow-breaking`; the normal fix is to
restore the committed file from git. On a merge conflict in a baseline, resolve the code, then regenerate the
baseline with `accept` rather than merging its lines.

### 4.3 When the dump format changes

`format:` is `ApiDump.FORMAT`. A change to `ApiDump` that changes how any line is rendered bumps it, which
makes every baseline fail with a message saying the *rendering* changed rather than with a confusing diff.
A person then regenerates all four baselines (`--allow-breaking` for a stable one) and reviews that only the
rendering moved.

---

## 5. Why It Cannot Heal Itself

A lock that can rewrite its own baseline passes whenever it is asked to, which is the same as not having one.
So:

1. **The test never writes.** `ApiLock.verify` reads; there is no system property, profile or flag under
   which it regenerates anything. `ApiLockToolTest#testTheCheckFailsWithAReadableDiffAndNeverWritesAnything`
   asserts the baseline's bytes are unchanged and no other file appeared.
2. **The writer is a separate program** with no JUnit entry point, run from a script.
3. **The checksum** turns "edit the expected lines until the test passes" into a test failure of its own.
4. **The scope is in the dump**, so "lock less" is also a diff.
5. **The agent rule** (§6). The mechanism above makes an accidental change impossible to miss; it does not
   make a deliberate one impossible — a person can always run the tool, and must be able to. The review of
   the `*.api` diff in the change that carries it is the actual control.

---

## 6. The Rule for AI Agents

Stated in [AGENTS.md](../AGENTS.md) at the repository root, in the header of every baseline, in every
`ApiLockTest`, and in the failure message itself:

- **Never** run `tools/api-lock.sh accept` or `policy`, or `ApiLockTool` directly. (`check` is read-only.)
- **Never** edit a `*/api/*.api` baseline or its `sha256` line.
- **Never** weaken, skip, disable, `@Disabled`, exclude or delete an `ApiLockTest`, or narrow a scope in
  `LockedModule`.
- On an API lock failure: make the change additive (§3.1), or **stop and report the diff to the user**.

The one exception so far is the initial creation of the four baselines, at the user's request.

---

## 7. Running It, and Its Limits

```bash
mvn test                                   # every lock, in its own module
mvn test -pl graph-client -am              # one module: -am builds graph-api-lock beside it
mvn test -pl graph-core -am -Dtest=ApiLockTest -Dsurefire.failIfNoSpecifiedTests=false
```

`-pl <module>` without `-am` needs `graph-api-lock` installed in `~/.m2` first (`mvn install -pl
graph-api-lock`), the same way `-pl graph-cypher` already needs graph-core. graph-core's tests run on the
module path and the tool on the class path; the dump is identical either way, and the four locks passing in
`mvn test` right after `accept` is the proof.

What the lock does **not** see: behaviour and javadoc contracts (a method that now returns null), parameter
names, annotations other than `@Deprecated` and `@FunctionalInterface`, the server's and CLI's own surfaces,
and the shape of a type named by a locked signature but outside the scope (§2). It is a check on
*signatures*; the rest is still review and the tests.

---

_Git HEAD revision: `8f8caad` (plus the API lock working tree)_
_Last updated: 2026-10-25 (API lock introduced, baselines created at policy alpha)_
