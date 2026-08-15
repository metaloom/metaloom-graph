# openCypher — the accepted subset

A **read-only** openCypher subset over the storage engine, plus an HTTP server and a browser UI.
Three modules:

| Module | What it is |
|---|---|
| `graph-cypher` | Lexer, parser, pattern matcher, evaluator. Depends on `graph-core`'s **public api package only** |
| `graph-server` | A Vert.x HTTP endpoint and a React and D3 graph browser, shaded into one runnable jar |
| `graph-core` | The engine, unchanged except for what §6 lists |

```java
CypherResult result = Cypher.query(store,
    "MATCH (p:Person)-[:KNOWS*1..2]->(f) WHERE p.age > $min RETURN f.name, count(*) AS paths ORDER BY paths DESC",
    Map.of("min", 30));
for (CypherRow row : result) {
    System.out.println(row.get("f.name") + " " + row.longValue("paths"));
}
```

> [ARCHITECTURE.md](ARCHITECTURE.md) listed a query language as an explicit non-goal *of the storage
> layer*, and it still is. That is what the module split is for: `graph-cypher` is a consumer of the
> public API and cannot reach inside it. Building it found exactly one gap in that API — there was no
> way to scan every node — which is the sort of thing only a real consumer finds.

---

## 1. Grammar

```
Query        := SingleQuery (UNION [ALL] SingleQuery)*
SingleQuery  := (Match | Unwind | With)* Return
Match        := MATCH Pattern [WHERE Expr]
Unwind       := UNWIND Expr AS Variable
With         := WITH Projection [WHERE Expr]
Return       := RETURN Projection
Projection   := [DISTINCT] ('*' | Item (',' Item)*) [ORDER BY SortItem+] [SKIP Expr] [LIMIT Expr]
Item         := Expr [AS Variable]
Pattern      := PatternPart (',' PatternPart)*
PatternPart  := [Variable '='] NodePattern (RelPattern NodePattern)*
NodePattern  := '(' [Variable] (':' Label)* [MapLiteral] ')'
RelPattern   := ('-' | '<-') ['[' [Variable] [':' Type ('|' Type)*] ['*' Range] [MapLiteral] ']'] ('-' | '->')
Range        := [Int] ['..' [Int]]
```

Expression precedence, loosest first: `OR` · `XOR` · `AND` · `NOT` · comparison
(`= <> < <= > >=`, `IN`, `STARTS WITH`, `ENDS WITH`, `CONTAINS`, `IS [NOT] NULL`) · `+ -` · `* / %` ·
`^` (right associative) · unary `-` · property access, indexing, `:Label` predicate.

**Parameters** are `$name`. **Comments** are `//` and `/* */`. **Backticks** quote a name containing
spaces. Keywords are case-insensitive; identifiers are not; `:Order` and `.end` are ordinary names.

### 1.1 Functions

| Group | |
|---|---|
| Aggregate | `count` (incl. `count(*)`), `sum`, `avg`, `min`, `max`, `collect`, each with `DISTINCT` |
| Graph | `id`, `labels`, `type`, `keys`, `properties`, `startNode`, `endNode`, `nodes`, `relationships` |
| Scalar | `length`, `size`, `head`, `last`, `tail`, `reverse`, `range`, `coalesce`, `timestamp`, `exists` |
| String | `toString`, `toUpper`, `toLower`, `trim`, `ltrim`, `rtrim`, `substring`, `replace`, `split` |
| Numeric | `toInteger`, `toFloat`, `toBoolean`, `abs`, `ceil`, `floor`, `round`, `sqrt`, `sign` |

An unknown function names itself and lists what exists, rather than returning null — a typo that
returns an empty result set is much worse than one that fails.

---

## 2. Null semantics

Cypher is three-valued, and almost nothing here matches Java. `NullSemanticsTest` covers this and
nothing else, because a mistake in it does not throw: it silently returns rows the query did not ask
for, or drops rows it did.

| Expression | Result | |
|---|---|---|
| `null = null` | `null` | not true |
| `null <> null` | `null` | |
| `1 < null` | `null` | every comparison with null |
| `false AND null` | `false` | a decisive operand wins |
| `true AND null` | `null` | |
| `true OR null` | `true` | |
| `false OR null` | `null` | |
| `NOT null` | `null` | |
| `3 IN [1, null]` | `null` | the null might have been a 3 |
| `1 IN [1, null]` | `true` | a match is a match |
| `null IN []` | `false` | nothing is in an empty list |
| `WHERE <null>` | row dropped | the one place the third value collapses |

**The classic surprise**: `WHERE NOT n.age > 20` does not return the nodes with no age. `n.age > 20`
is null, `NOT null` is null, and a null predicate drops the row. Use `IS NULL` for that.

Aggregates **skip nulls**; `count(*)` counts **rows**. Over five people of whom two have no age,
`count(*)` is 5, `count(p.age)` is 3, and `avg(p.age)` divides by 3. `sum` of nothing is `0`;
`avg` of nothing is `null`.

DISTINCT and ORDER BY need a total order, so they are the one place two nulls are treated as equal.
Nulls sort **last** ascending.

---

## 3. Where this differs from the engine

Two mismatches, both real, both surfaced rather than papered over.

### 3.1 A lightweight edge has no identity

`createEdge` costs 16 bytes and writes no record, so it has no id and no properties. In a query:

```
MATCH ()-[r:KNOWS]->()  RETURN id(r), type(r), r.since
                               null    KNOWS    null
```

`type(r)`, `startNode(r)` and `endNode(r)` work normally. A synthetic id was considered and rejected:
nothing else could resolve it, so it would be a value that looks usable and is not. The UI draws these
edges **dashed** so the difference is visible.

### 3.2 `traverse` answers a different question

The engine has a breadth-first walk. It was not reused, and the difference is not cosmetic:

| | `GraphStore.traverse` | Cypher `-[*1..3]->` |
|---|---|---|
| Uniqueness | per **node** | per **relationship**, within each path |
| Result | flat list of edges | one row per distinct **path** |
| Question | what can I reach | how can I get there |

On a diamond — two routes from A to D — `traverse` reports D once, Cypher returns two rows.
`VarLengthPathTest` builds that graph and asserts both, so the claim is measured rather than argued.
Relationship uniqueness is also what makes `-[*]-` terminate on a cycle.

---

## 4. Isolation

**Every query runs inside a `SNAPSHOT` read view.** A query is not one read — it scans, expands from
what it found, then reads properties off the far end — and without a view a writer committing between
those steps could give it a result that never existed at any instant.

If the caller already has a view open, the query **joins** it rather than opening another (views are
thread-confined and refuse to nest). So several queries inside one `beginRead()` see one consistent
state, which is how a caller asks for that.

Consequences worth knowing: a long query holds a view, and an open view refuses `reclaim()` and
`compact()` and holds back version reclamation ([TRANSACTIONS.md §5](TRANSACTIONS.md#5-version-storage)).

---

## 5. What is not built, and why

| | Reason |
|---|---|
| `CREATE`, `MERGE`, `SET`, `REMOVE`, `DELETE` | Read-only by decision. Writes stay on the Java API |
| `OPTIONAL MATCH` | Needs null-padding semantics through the rest of the pipeline |
| `CASE`, list comprehensions, map projections, pattern comprehensions | Not built |
| `shortestPath`, `allShortestPaths` | Not built |
| `ALL`/`ANY`/`NONE`/`SINGLE` predicates, `EXISTS` subqueries | Not built |
| `CALL`, procedures, `LOAD CSV` | No procedure mechanism exists |
| Temporal and spatial types | The store has `Instant`; Cypher's temporal algebra is not implemented |

**Each of these is refused at parse time, by name, with a caret.** The parser accepts only what the
executor can run, so "this build cannot do that" and "you typed it wrong" are different messages:

```
MATCH (n:Person) WHERE n.age > > 30 RETURN n
                               ^
Unexpected '>' in an expression (1:32)
```

```
CREATE (n:Person) RETURN n
^
CREATE is not supported: this build runs read-only queries. Use the Java API for writes (1:1)
```

---

## 6. Storage changes this required

**Multi-label nodes**, format version 2 → 3. Cypher allows `(n:Person:Employee)`; the engine stored
one `label_id`.

The label set reuses the node record's `version_ptr` and `xmin` — 16 bytes at offsets 24 to 39 that
Phase 5 established are written as zero and read by nothing, because visibility is resolved per page
([TRANSACTIONS.md §5.1](TRANSACTIONS.md#51-what-was-built)). So:

- The record is still **64 bytes** and the stride is unchanged. Space gates unaffected.
- **No data migration.** `ElementId.NULL` is zero and both fields were written as zero, so every v2
  node decodes as exactly one label — which is what it has. The version bump exists for the other
  direction: a v2 build reading a v3 node would read its extra labels as an undo pointer.
- Three labels live in the record; beyond that the set spills into a property block, which brings the
  allocator, the orphan checker and compaction's reference rewriting along for free rather than
  needing each reimplemented.

Also added: **`GraphStore.forEachNode`**, the full scan `MATCH (n)` needs. A callback rather than a
list, because materialising eight bytes per live record would make the unindexed case the one that
runs out of memory.

---

## 7. Planning, and what it costs

Start points, most selective first: a variable already bound by an earlier clause → an indexed
property equality → a label scan → a full node scan. So
`MATCH (a:Person {email:$e})-[:KNOWS]->(b)` is one index probe and one expansion.

**Known costs, stated rather than discovered:**

- **Rows are materialised per clause**, not streamed, so a query's peak memory is its widest
  intermediate result and `LIMIT` does not stop the scan early. The shape was chosen because ORDER BY,
  DISTINCT and aggregation all need their whole input anyway, and a pull-based tree has many more
  places for a scoping bug to hide. **`Cypher.stream` does not change this** — it bounds what the
  *caller* holds and when the first row arrives, not what the executor holds. [WIRE.md §0](WIRE.md)
  states the same distinction from the transport's side, where it is easiest to overclaim.
- **DISTINCT is quadratic** in the number of distinct rows. It cannot use a hash set: `1` and `1.0`
  are the same Cypher value with different hash codes, and two nodes are equal by id rather than by
  contents.
- **Only the first label of a pattern drives the scan**; the rest are verified per candidate. The
  rarest would be the better choice, and the store does not expose per-label counts.
- **No join reordering.** A pattern is expanded left to right from its chosen seed.

---

## 8. The server and UI

```bash
java -jar graph-server/target/graph-server-0.0.1-SNAPSHOT.jar /path/to/store 8080
java -jar graph-server/target/graph-server-0.0.1-SNAPSHOT.jar --demo /tmp/demo 8080   # §8.3
mvn -o -pl graph-server exec:java -Dexec.args="/path/to/store 8080"   # from the reactor
tools/demo-server.sh                                                  # the demo graph on 8080
```

The jar is shaded: server, engine, query language, Vert.x, Netty, logging and the UI bundle in one
file. `Enable-Native-Access: ALL-UNNAMED` is in its manifest — an uber jar cannot carry `graph-core`'s
module descriptor, so the narrow `--enable-native-access=io.metaloom.graph.core` grant is not available
to it. Run the reactor jars to keep the narrow one.

This is one of **two** front ends over a **root of databases**. The other is the binary streaming port,
on loopback 8081 by default, which pushes rows as the engine produces them instead of building a whole
result: [WIRE.md](WIRE.md). `--no-wire` turns it off. What follows is the HTTP one.

Every route below takes `?database=<name>`, and without one it uses the server's default —
[DATABASES.md](DATABASES.md). A query runs against exactly one database; there is no cross-database
query and the engine has no notion of a second store.

| Route | |
|---|---|
| `POST /api/query` | `{"query": "...", "parameters": {...}}` → columns, rows, and a `graph` of nodes and links |
| `GET /api/schema` | Labels and relationship types with live counts. `?refresh=true` forces a rescan |
| `GET /api/stats` | Node and relationship counts, labels, storage bytes, open views, and which database answered |
| `GET /api/databases` | Every database under the root, which is the default, and whether dropping is enabled |
| `PUT /api/databases/{name}` | Creates one. `DELETE` drops it, and only with `--allow-drop` |
| `GET /` | The UI |

One result serves both views: the table is the rows, and the graph is built by walking every value in
every row and collecting whatever is renderable. A query returning only `n.name` gets a table and an
empty canvas, which the UI says rather than showing blank space. A link is drawn only when **both**
endpoints were returned, or D3 would invent a vertex for the missing one; the rest travel as
`danglingLinks`, because merge mode may already have the far end on screen.

**Ids cross as strings.** An id here is around 2^60 and a JavaScript number is a double: exact to
2^53. `1152921504606846977` arrives in a browser as `1152921504606846976`, and ids close enough
together would collapse onto one value and merge two nodes into one circle. Any integer column outside
the safe range is stringified for the same reason — `RETURN id(n)` has to give back something a caller
can paste into another query. Found by running the UI, so there is now a test.

**Queries run on worker threads**, never the event loop: an unindexed query is a full scan, bounded by
the data rather than by the request. They also run under a deadline — `--query-timeout`, 30 s by
default, 0 to disable — which is enforced by the engine consulting an abort signal *while it scans*
rather than by the route giving up waiting. `executeBlocking` cannot interrupt a task that has
started, so a route that merely stopped waiting would abandon the answer while the scan kept its
thread and its read view, which is the failure a timeout exists to prevent.

### 8.1 `/api/schema` is a scan

The engine interns node labels and relationship types into **one** dictionary — `internEdgeType`
delegates to `internLabel` — so the dictionary can say that `KNOWS` exists but not that it is a
relationship type, and it still holds labels whose last node was deleted, because ids are never reused.
A sidebar built from it would mix the two and list things that match nothing.

So the counts come from the data: one pass over the live nodes, reading each node's labels and its
outgoing edges. Outgoing only, or every relationship would be counted twice. It is O(nodes + edges),
runs on a worker thread inside a snapshot view, and is cached for 60 s; the response carries
`elapsedMillis` and `cached` so the UI can say which answer it is showing.

### 8.2 The browser UI

A React application under `graph-server/src/main/frontend`, bundled by Vite straight into
`target/classes/webroot` — which is what `StaticHandler` serves and what ends up inside the jar, so
`mvn package` cannot ship a stale bundle. npm is treated exactly as cargo is: absent means skipped,
with a message, and `-Dui.skip=true` forces the same path. Without a bundle the server still answers
every API route and the index page says so rather than returning a 404.

| | |
|---|---|
| Left | Labels and relationship types with counts, each one clickable into a query; below it the inspector — the selected node's labels and properties, or the selected relationship's, with its incident relationships as links to walk |
| Above | The query editor. Ctrl/Cmd+Enter runs it. **Replace** or **Merge** decides what a result does to the canvas |
| Right | The force layout: colour per label and per relationship type, the type written on each line, drag, zoom, fit |

Three decisions worth recording:

- **Colour comes from a hash of the name**, not from order of appearance. The sidebar lists what is in
  the store and the canvas draws what the last query returned; handing colours out in encounter order
  would make the two disagree, and would change a label's colour depending on which query ran first.
  Hashing means the legend and the picture agree with no shared state.
- **Merge reuses node objects rather than rebuilding them.** d3-force writes `x`/`y` onto the objects
  it is given, so a fresh object per result would drop every position and the whole picture would jump
  each time a query mentioned a node already on screen. `model/graph.ts` mutates the existing object in
  place and its tests assert object identity, which is the only way to state that rule.
- **A dangling link is held, not dropped.** The server can only judge drawability within one result. In
  merge mode a link whose far end arrives two queries later is drawn then — which is what makes it
  possible to build a picture one query at a time. Held links are capped, newest kept.

### 8.3 Demo data

`--demo` fills an **empty** store with a generated graph and then serves it. `--demo-size` takes `tiny`, `small`
(the default), `medium`, `large`, or a number of airports.

| Size | Airports | Nodes | Relationships | Seed |
|---|---|---|---|---|
| `tiny` | 12 | 37 | 93 | 55 ms |
| `small` | 58 | 159 | 483 | 55 ms |
| `medium` | 400 | 860 | 4,893 | 150 ms |
| `large` | 3,000 | 6,060 | 29,581 | 360 ms |

**Only into an empty store.** Seeding on top of existing data would work — the generator deletes nothing — and that is
why it is refused: invented routes silently mixed into somebody's real graph is not a mistake they would notice
quickly. A store with nodes in it is left alone and logged about, so the flag is safe to leave in a start script. It
is the one write path in an otherwise read-only application, it is opt-in, and it runs before the server binds.

The domain is an **air route network**: `(:Airport)-[:ROUTE_TO {airline, flightNumber, distanceKm, durationMinutes,
weeklyFlights}]->(:Airport)`, with `(:Airport)-[:IN_CITY]->(:City)-[:IN_COUNTRY]->(:Country)` and
`(:Airline)-[:HUB_AT {since}]->(:Airport)`. It was chosen because it needs no explanation — so the demo teaches the
query language rather than the dataset — and because it contains every awkward shape the engine and the browser have
to handle without a single one of them being contrived:

| In the domain | Which exercises |
|---|---|
| A hub is `:Airport:Hub` | The node record's inline label slots |
| A freight airline is a `` `Cargo Carrier` `` | A label that has to be quoted to become a query |
| `IN_CITY`, `IN_COUNTRY` carry nothing | Lightweight edges: 16 bytes, no record, no id |
| Two airlines fly one pair | Parallel relationships, which must be drawn apart rather than on top of each other |
| A sightseeing flight returns to its airport | A self-relationship |
| A hub has 46 routes, a spoke has 3 | The supernode end of the adjacency tiers |

**Airport names, codes and coordinates are real**, rounded to a tenth of a degree, and route distances are computed
from them with a haversine — so `ORDER BY r.distanceKm DESC` gives an answer that matches the world, and
`DemoDataTest` checks London to Paris against 350 km. **The airlines and every route are invented.** Past the 58 real
airports the generator invents those too, in invented countries with ISO 3166-1 user-assigned codes, rather than
inventing plausible-looking facts about real places.

It is seeded, so a size always produces the same graph — which is what lets the browser suite name Heathrow and
expect an answer.

**No authentication.** The query language cannot write, but it reads everything. Bind to localhost
unless something else provides access control.

---

## 9. Tests

| Class | Cases | |
|---|---|---|
| `CypherParserTest` | 20 | Tokens, precedence, ranges, and every refusal naming itself with a caret |
| `NullSemanticsTest` | 16 | §2, on its own |
| `CypherExecutionTest` | 31 | Every clause, end to end |
| `CypherVersusApiTest` | 12 | **The load-bearing one** — see below |
| `VarLengthPathTest` | 8 | §3.2, against a graph where the difference shows |
| `CypherSnapshotTest` | 4 | §4: views closed, joined, and a query atomic against a live writer |
| `GraphServerTest` | 18 | The HTTP contract, both result shapes, the schema scan and its cache, and the id-precision regression |
| `DemoDataTest` | 10 | §8.3: the graph is connected as claimed, its distances match the world, and it is the same every time |
| `GraphServerOptionsTest` | 8 | The command line, so an ignored flag fails a test rather than starting a server that does the wrong thing |
| `model/graph.test.ts` | 12 | The merge rule: node identity across results, held links, and that every change advances the version |
| `model/colors.test.ts` | 5 | A colour is a function of a name, and the demo graph's labels do not collide |
| `e2e/graph-browser.spec.ts` | 29 | The UI in a browser against a real server — see §9.1 |

`CypherVersusApiTest` is the one that matters. [TESTING.md](TESTING.md) opens by observing that every
defect this project has found came from a test comparing the engine against **something outside it**.
The query layer's version of that is: seed a random graph, and require every query's answer to equal
what the Java API computes directly. The graph is seeded rather than chosen, so it contains the shapes
an author would not have thought to write — nodes with several labels and none, missing properties,
parallel edges, self-loops, and a mix of materialised and lightweight relationships.

### 9.1 The browser suite

`npm run e2e` in `graph-server/src/main/frontend`, opt-in because it needs a browser and starts a
server. There are no mocks: Playwright drives Chromium against `tools/demo-server.sh`, which is the
same command a human uses to look at the UI, over a store on disk filled by the shipped `--demo` flag (§8.3). That
graph
is deliberately awkward — a node with two labels, a node with none, a label with a space in it, two
relationships between one pair, a self-relationship, and three lightweight edges — because each one is
a case the UI has to get right and would otherwise only be exercised by hand.

Writing it found four defects that the Java tests could not have:

| | |
|---|---|
| **Replace mode drew every result but the first** | The version counter the canvas re-renders on was rebuilt from an empty graph each time, so it was always 1. Now a model function, with a test that the counter never repeats |
| **Fit ran before the layout had settled** | Positions are still bunched a moment after a query, so the computed scale was the maximum and the simulation then spread the graph out at that magnification, off the edges of the screen. Now deferred to the simulation's `end` event, and capped |
| **`Person` and `Product` were the same colour** | A twelve-entry palette, indexed by hash, collided on the two labels a user most needs to tell apart. Now a hue per name, with the demo graph's labels as a regression test |
| **A 1.6 pixel line is not a click target** | Each line now has an invisible 16 pixel copy that takes the click — needed for a mouse, not only for a test |

The suite also caught a harness defect worth naming: Vert.x copies classpath resources into
`.vertx/file-cache-*` and serves the copy, so a server left running across a UI rebuild serves the
previous bundle. Every assertion in the first run failed against markup that no longer existed.
`tools/demo-server.sh` now starts the server with that cache off.

---

_Git HEAD revision: `364b5f7`_
_Last updated: 2026-08-14_
