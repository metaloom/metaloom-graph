# openCypher — the accepted subset

A **read-only** openCypher subset over the storage engine, plus an HTTP server and a browser UI.
Three modules:

| Module | What it is |
|---|---|
| `graph-cypher` | Lexer, parser, pattern matcher, evaluator. Depends on `graph-core`'s **public api package only** |
| `graph-server` | A Vert.x HTTP endpoint and a D3 UI |
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
  places for a scoping bug to hide.
- **DISTINCT is quadratic** in the number of distinct rows. It cannot use a hash set: `1` and `1.0`
  are the same Cypher value with different hash codes, and two nodes are equal by id rather than by
  contents.
- **Only the first label of a pattern drives the scan**; the rest are verified per candidate. The
  rarest would be the better choice, and the store does not expose per-label counts.
- **No join reordering.** A pattern is expanded left to right from its chosen seed.

---

## 8. The server and UI

```bash
mvn -o -pl graph-server exec:java \
    -Dexec.mainClass=io.metaloom.graph.server.GraphServer \
    -Dexec.args="/path/to/store 8080"
```

| Route | |
|---|---|
| `POST /api/query` | `{"query": "...", "parameters": {...}}` → columns, rows, and a `graph` of nodes and links |
| `GET /api/stats` | Node and relationship counts, labels, storage bytes, open views |
| `GET /` | The UI |

One result serves both views: the table is the rows, and the graph is built by walking every value in
every row and collecting whatever is renderable. A query returning only `n.name` gets a table and an
empty canvas, which the UI says rather than showing blank space. A link is drawn only when **both**
endpoints were returned, or D3 would invent a vertex for the missing one.

**Ids cross as strings.** An id here is around 2^60 and a JavaScript number is a double: exact to
2^53. `1152921504606846977` arrives in a browser as `1152921504606846976`, and ids close enough
together would collapse onto one value and merge two nodes into one circle. Any integer column outside
the safe range is stringified for the same reason — `RETURN id(n)` has to give back something a caller
can paste into another query. Found by running the UI, so there is now a test.

**Queries run on worker threads**, never the event loop: an unindexed query is a full scan, bounded by
the data rather than by the request. D3 v7 is **vendored** under `webroot/vendor/`, ISC-licensed, so
the UI works with no network.

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
| `GraphServerTest` | 14 | The HTTP contract, both result shapes, and the id-precision regression |

`CypherVersusApiTest` is the one that matters. [TESTING.md](TESTING.md) opens by observing that every
defect this project has found came from a test comparing the engine against **something outside it**.
The query layer's version of that is: seed a random graph, and require every query's answer to equal
what the Java API computes directly. The graph is seeded rather than chosen, so it contains the shapes
an author would not have thought to write — nodes with several labels and none, missing properties,
parallel edges, self-loops, and a mix of materialised and lightweight relationships.

---

_Git HEAD revision: `364b5f7`_
_Last updated: 2026-08-14_
