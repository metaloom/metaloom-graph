# openCypher — the accepted subset

An openCypher subset over the storage engine, plus an HTTP server and a browser UI. Reads and writes;
**writes are off by default on both ports** and turned on with `--allow-write` (§5.1). **`LOAD CSV` is
off by default too, under a separate flag and confined to one directory** (§5.6).
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
SingleQuery  := [PeriodicCommit] Clause+ [Return]   -- Return is required unless the query writes
Clause       := Match | Unwind | With | Create | Merge | Set | Remove | Delete | Call | LoadCsv
PeriodicCommit := USING PERIODIC COMMIT [Int]       -- only before a leading LoadCsv; see 5.6
LoadCsv      := LOAD CSV [WITH HEADERS] FROM Expr AS Variable [FIELDTERMINATOR StringLit]
Call         := CALL Name '(' [Expr (',' Expr)*] ')' YIELD Variable (',' Variable)*
Match        := MATCH Pattern [WHERE Expr]
Unwind       := UNWIND Expr AS Variable
With         := WITH Projection [WHERE Expr]
Return       := RETURN Projection
Create       := CREATE Pattern
Merge        := MERGE PatternPart (ON (CREATE | MATCH) SET SetItem (',' SetItem)*)*
Set          := SET SetItem (',' SetItem)*
SetItem      := Variable '.' Key '=' Expr | Variable (':' Label)+
              | Variable '=' Expr | Variable '+=' Expr
Remove       := REMOVE RemoveItem (',' RemoveItem)*
RemoveItem   := Variable '.' Key | Variable (':' Label)+
Delete       := [DETACH] DELETE Expr (',' Expr)*
Projection   := [DISTINCT] ('*' | Item (',' Item)*) [ORDER BY SortItem+] [SKIP Expr] [LIMIT Expr]
Item         := Expr [AS Variable]
Pattern      := PatternPart (',' PatternPart)*
PatternPart  := [Variable '='] (PathSearch '(' Chain ')' | Chain)
PathSearch   := shortestPath | allShortestPaths        -- MATCH only; one relationship; min 0 or 1
Chain        := NodePattern (RelPattern NodePattern)*
NodePattern  := '(' [Variable] (':' Label)* [MapLiteral] ')'
RelPattern   := ('-' | '<-') ['[' [Variable] [':' Type ('|' Type)*] ['*' Range] [MapLiteral] ']'] ('-' | '->')
Range        := [Int] ['..' [Int]]
```

Expression precedence, loosest first: `OR` · `XOR` · `AND` · `NOT` · comparison
(`= <> < <= > >=`, `IN`, `STARTS WITH`, `ENDS WITH`, `CONTAINS`, `IS [NOT] NULL`) · `+ -` · `* / %` ·
`^` (right associative) · unary `-` · property access, indexing, `:Label` predicate. Comparisons
chain as in openCypher: `a < b <= c` is `a < b AND b <= c`.

**Parameters** are `$name`. **Comments** are `//` and `/* */`. **Backticks** quote a name containing
spaces. Keywords are case-insensitive; identifiers are not; `:Order` and `.end` are ordinary names.

**Parameters are normalised once per query**: `Integer`, `Short` and `Byte` become `Long`, `Float` becomes
`Double`, through lists and maps. A JSON body decodes `30` as an `Integer`, and without this it compared
correctly and then failed the first write. A `float[]` is left alone — it is how a query vector arrives.

**Hostile input is refused, not survived.** An expression nested more than 200 levels deep in the
parser's recursion (parentheses, lists, prefix operators), or whose tree is deeper than 1,000 (a flat
`a OR b OR …` chain of 999 is fine), is a `CypherSyntaxException` rather than a `StackOverflowError`. A
number that does not fit in 64 bits — `9223372036854775808`, `0xFFFFFFFFFFFFFFFFF`, `0x` — and a bad
`\u` escape are syntax errors with a caret; `-9223372036854775808` is the one literal that only exists
negated and is accepted. A range bound past `2^31` means "unbounded" rather than its low 32 bits.

### 1.1 Functions

| Group | |
|---|---|
| Aggregate | `count` (incl. `count(*)`), `sum`, `avg`, `min`, `max`, `collect`, each with `DISTINCT` |
| Graph | `id`, `labels`, `type`, `keys`, `properties`, `startNode`, `endNode`, `nodes`, `relationships` |
| Scalar | `length`, `size`, `head`, `last`, `tail`, `reverse`, `range`, `coalesce`, `timestamp`, `exists` |
| String | `toString`, `toUpper`, `toLower`, `trim`, `ltrim`, `rtrim`, `substring`, `replace`, `split` |
| Numeric | `toInteger`, `toFloat`, `toBoolean`, `abs`, `ceil`, `floor`, `round`, `sqrt`, `sign` |
| Vector | `vector.cosine`, `vector.dot`, `vector.l2` — [VECTOR.md](VECTOR.md) |

An aggregate may sit anywhere inside a projection item — `size(collect(x))`, `count(*) > 0`,
`collect(x)[0]`, `{c: count(*)}` — and is folded when evaluation reaches it, so a `CASE` folds only the
branch it takes.

`startNode(r)` and `endNode(r)` return the **node**, so `startNode(r) = a` and `startNode(r).name` work.
`range()` builds at most **10,000,000** elements and refuses more: the list is materialised and boxed,
and `range(1, 2000000000)` was an `OutOfMemoryError` for every query in the process rather than a slow
answer. Its length is counted up front, so `range(9223372036854775806, 9223372036854775807)` is two
elements rather than a loop that wraps.

**Integer arithmetic does not wrap.** `+ - * /`, unary minus, `abs` and `sum` over integers that leave
64 bits raise a `CypherTypeException` naming the operator; a silently negative sum is a wrong answer that
throws nothing. `SKIP`/`LIMIT` and `substring` arguments past an `int` are clamped as longs, not
truncated.

**One procedure**, and it exists because the planner cannot infer an access path it has no cost model
for:

```cypher
CALL vector.query('Person', 'embedding', $q, 10) YIELD node, score
MATCH (node)-[:MEMBER_OF]->(:Team {name: 'infra'})
RETURN node.name, score
```

`YIELD` is required — a call whose results bind to nothing has nowhere to put them, and this build has
no implicit result table, so accepting it would make a query that looks like it does something do
nothing. An unknown procedure is refused by the executor rather than the grammar, because "no such
procedure" is not a syntax error. So are a pair with no declared vector index, an empty query vector
and one of the wrong length — as type errors, checked before the engine is asked. The engine's own
refusals are an `IllegalStateException` and an `IllegalArgumentException`, which a server reports as its
own failure rather than the query's.

**The second line is the point.** `node` is already bound when the `MATCH` runs, so the pattern matcher
takes its already-bound branch and *verifies* rather than scanning — a graph predicate applied to the
results of a vector search, with no planner change at all. That combined query is the reason the index
lives inside the engine instead of beside it ([VECTOR.md §4.5](VECTOR.md)).

An unknown function names itself and lists what exists, rather than returning null — a typo that
returns an empty result set is much worse than one that fails.

**Strings are measured in characters, not UTF-16 units.** `size`, `length`, `substring` and `split`
with an empty separator count code points, as Neo4j does: `size('😀')` is 1, and `substring` can never
cut a character in half. In UTF-16 units it could, and the half it returned is a string with no UTF-8
form, which the store then refuses to hold.

**The coercions take what their names say and nothing Java's parsers add.** `toInteger('2.7')` is 2 —
a float string truncates as the float would — and `toInteger('1e400')` is null, because no integer is
that number. `toFloat` accepts decimal notation, `NaN` and `Infinity`, and nothing else: Java's parser
also takes `'1.5f'` and hexadecimal floats, so they used to read as numbers. `toBoolean(0)` is false
and any other integer true, as in Neo4j 5. `round` of NaN, an infinity or anything from 2^52 up is the
value itself; it went through a `long` and came back as `0.0` or `9.2e18`.

**The vector functions are the only namespaced names in the grammar, and the dot is not lexical.**
`.` is deliberately not an identifier character — making it one breaks both `n.name` and the `*1..3`
range — so `vector.cosine(a, b)` reaches the parser as a property access on a variable called
`vector`, and `postfix()` folds it back into a call when the namespace is a **bare variable** and a
`(` follows immediately. Both halves of that guard matter: `n.a.b(…)` is left alone. Without the fold
the query does not fail as an unknown function — the unconsumed `(` falls out of the projection and
`RETURN` reports that a parenthesis cannot follow it, which is a message about the wrong thing.

They take a vector in either shape it can arrive in: a `float[]` passed as a parameter, or the list of
numbers a stored vector has already become on its way through `GraphAccess.normalise`. Null in, null
out — a node with no embedding must not fail the query for every node that has one — but **two vectors
of different lengths are refused rather than scored**, because the overwhelmingly likely cause is a
query vector written for a different model than the stored one.

**Cypher can read a vector and cannot write one.** `GraphAccess.storable` accepts only `Boolean`,
`Long`, `Double`, `String` and `List`, so `SET n.embedding = [0.1, 0.2]` stores a list of doubles,
which is a different value from the `float[]` the Java API writes and will not match it. Populating a
vector property is a Java API operation today.

**A top-k.** `ORDER BY` is sort-then-truncate, and its keys are computed **once per row** before the
sort, so ordering directly on `vector.cosine(...)` scores each row once. Projecting the score first is
still the clearer spelling:

```cypher
MATCH (d:Doc) WITH d, vector.cosine(d.embedding, $q) AS score
ORDER BY score DESC LIMIT 10 RETURN d.name, score
```

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
| `[1, null] = [1, 2]` | `null` | a nested null is just as unknown |
| `[1, null] = [2, null]` | `false` | unless a known element already differs |
| `{a: 1} = {a: 1.0}` | `true` | map values compare as Cypher values |
| `-0.0 < 0.0`, `0.0/0.0 > 1` | `false` | `< <= > >=` on floats are IEEE, not `Double.compare`'s sort order |
| `[1, null] IN [[1, 2]]` | `null` | `IN` compares each element with `=`, nested nulls included |
| `CASE [1, null] WHEN [1, null] …` | the `ELSE` | the simple form uses `=` too |
| `[1] < ['a']`, `[1, null] < [1, 2]` | `null` | lists compare element by element and inherit their elements' unknowns |
| `[1, null] < [2, null]` | `true` | an earlier element that differs still decides |
| `WHERE <null>` | row dropped | the one place the third value collapses |
| `UNWIND [null] AS n MATCH (n)` | no rows | a variable bound to null matches nothing; it is not unbound |

**The classic surprise**: `WHERE NOT n.age > 20` does not return the nodes with no age. `n.age > 20`
is null, `NOT null` is null, and a null predicate drops the row. Use `IS NULL` for that.

Aggregates **skip nulls**; `count(*)` counts **rows**. Over five people of whom two have no age,
`count(*)` is 5, `count(p.age)` is 3, and `avg(p.age)` divides by 3. `sum` of nothing is `0`;
`avg` of nothing is `null`.

DISTINCT, grouping and ORDER BY need two-valued answers, so they are the one place two nulls are
treated as equal. DISTINCT and grouping use openCypher's *equivalence* — null ≡ null, NaN ≡ NaN, `1` ≡
`1.0` — through one key function (`Values.groupingKey`), so `UNWIND [1, 1.0] AS x RETURN x, count(*)`
is one group, as `RETURN DISTINCT x` is one row. Nulls sort **last** ascending.

The last row of the table was a live defect: a pattern variable bound to null was treated as unbound,
so the scan rebound it to every node in the store — a missing value turning into all of them. Bound to
something that is not a node at all (`WITH 1 AS n MATCH (n)`), it is a type error. The rows above it
were defects too: `IN` and the simple `CASE` used the two-valued equality DISTINCT needs rather than
`=`, and list comparison borrowed the sort order, which orders every pair.

### 2.1 Numbers, ordering, and `+`

**An integer and a float compare exactly.** `9007199254740993 = 9007199254740992.0` is false: the
integer has no double, and widening it first made the two equal. Grouping already keyed an integral
float as the integer it equals, so `=` and DISTINCT disagreed about the same two values; and widening
is not transitive near 2^53, which a sort comparator has to be.

**Unlike types sort in openCypher's orderability**, the order Neo4j uses: map, node, relationship,
list, path, string, boolean, number, and null last. Nodes and relationships order by identity, lists
and maps element by element, numbers exactly across the two types with NaN above every number.
`ORDER BY`, `min` and `max` all use it, so `min` over `[1, 'a', true, [1]]` is the list and `max` is
the number. (The order used to be alphabetical by type name, and nodes did not order at all.)

**A list on either side of `+` makes it list concatenation**, checked before strings:
`[1, 2] + 'x'` is `[1, 2, 'x']` and `1 + [2]` is `[1, 2]`. The string test ran first, so the first
rendered the list into `"[1, 2]x"` and the second was a type error.

**Literals are ASCII.** Only `0`–`9` make a number — `Character.isDigit` also accepts every other
script's digits, which `Long.parseLong` then read and `Double.parseDouble` threw on — and a float
literal too large for a double, `1e999`, is a syntax error rather than Infinity. A string literal or a
quoted name holding half of a surrogate pair (`'\uD83D'`) is a syntax error; a parameter holding one is
a type error when it is written to the store. Such a string has no UTF-8 form, and the store's own
refusal was an `IllegalArgumentException`, which a server reports as its own failure.

**SKIP and LIMIT take an integer.** `LIMIT 1.5` was read as `LIMIT 1`, a row count nobody wrote.

**A column name appears once.** A row is a map from name to value, so `RETURN 1 AS a, 2 AS a` — or
`RETURN x, x`, or `RETURN *, 2 AS x` with an `x` in scope — kept the second value and reported it
under both headers. The parser refuses the first two, the executor the third.

**After an aggregation, ORDER BY may name a projected expression by its text** as well as by its
alias: `RETURN n.age AS age, count(*) ORDER BY n.age` reads the `age` column, because `n` is no longer
there to read.

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

For writes this has two consequences. `SET r.since = …` on one is **refused** — there is nowhere to put
the value, and materialising it silently would change its identity and rewrite adjacency. `DELETE r`
**works**, through `deleteEdge(from, type, to)`, which cannot distinguish two parallel lightweight edges
between the same pair — but they are the same sixteen bytes, so removing either satisfies a request to
remove one, and two rows asking remove both. `CREATE` never makes one (§5.2).

### 3.2 `traverse` answers a different question

The engine has a breadth-first walk. It was not reused, and the difference is not cosmetic:

| | `GraphStore.traverse` | Cypher `-[*1..3]->` |
|---|---|---|
| Uniqueness | per **node** | per **relationship**, across the whole `MATCH` (every comma-separated part) |
| Result | flat list of edges | one row per distinct **path** |
| Question | what can I reach | how can I get there |

On a diamond — two routes from A to D — `traverse` reports D once, Cypher returns two rows.
`VarLengthPathTest` builds that graph and asserts both, so the claim is measured rather than argued.
Relationship uniqueness is also what makes `-[*]-` terminate on a cycle. A relationship variable
already bound by an earlier clause is a **join**, as a node variable is: `WITH r MATCH (a)-[r]->(b)`
finds `r`'s own endpoints rather than rebinding `r` to every edge.

**An undirected step crosses a self-loop once.** `neighbours(BOTH)` lists a self-loop from both of its
ends, which are one node, so `MATCH (a)-[r]-(a)` returned every loop twice. It is one relationship,
and openCypher — and Neo4j — return it once; `GraphAccess.expand` drops the second listing, for
materialised and lightweight loops alike.

**A name in scope is not silently rebound.** `UNWIND … AS x`, `LOAD CSV … AS x`, `YIELD x`, a path
variable, and a relationship variable in `CREATE` or a created `MERGE` all introduce a name, and one
already defined is refused: `MATCH (n) UNWIND [1, 2] AS n` used to replace every node with a number for
the rest of the query, without a word. A node variable in a pattern is the exception, because naming a
bound node is how a pattern joins to it.

**`WITH` is a projection.** What it leaves out is out of scope afterwards, in its own `WHERE` and in
every later clause, on both execution paths — `MATCH (a)-->(b) WITH a MATCH (a)-->(b)` binds a fresh
`b`. Its `ORDER BY` may still read underneath, as openCypher allows.

---

## 4. Isolation, and which transaction a query gets

`Cypher.stream` decides this in one place, from `Ast.Query.isWrite()`.

**A read runs inside a `SNAPSHOT` read view.** A query is not one read — it scans, expands from what
it found, then reads properties off the far end — and without a view a writer committing between those
steps could give it a result that never existed at any instant.

If the caller already has a view open, the query **joins** it rather than opening another (views are
thread-confined and refuse to nest). So several queries inside one `beginRead()` see one consistent
state, which is how a caller asks for that.

Consequences worth knowing: a long query holds a view, and an open view refuses `reclaim()` and
`compact()` and holds back version reclamation ([TRANSACTIONS.md §5](TRANSACTIONS.md#5-version-storage)).

### 4.1 A write runs in one transaction, and opens no view

```java
try (Transaction txn = store.begin()) {
    Cypher.query(store, "CREATE (n:Person {name:'Ada'})");
    Cypher.query(store, "MATCH (n:Person) SET n.seen = true");   // sees the first
    txn.commit();                                                 // both, or neither
}
```

**No read view.** A writer reads the current mapping rather than a view — that is what prevents a lost
update ([TRANSACTIONS.md §3](TRANSACTIONS.md)) — so a view here would be ignored by the storage layer
while still costing a liveness check that an abandoned view can fail. The transaction's page overlay is
what makes the query's reads consistent, and also what makes them see its own writes.

**A write inside an open read view is refused**, with `CypherWriteException`. The caller asked for a
fixed view and would silently not get it.

**It joins a caller's transaction rather than opening a second.** `GraphStore.begin()` does *not*
refuse to nest, and a nested transaction is not a caught mistake but a silent one: committing the inner
one clears the published overlay, after which the outer transaction's writes go straight to the mapping,
unlogged and past the point a rollback could take them back. `GraphStore.hasWriteTransaction()` was
added for exactly this question.

**A joined query is still one statement.** It runs through `GraphStore.inOpenTransaction`, so a
query that fails after it has written — a plain `DELETE` that removed one node and was then refused on
a connected one — makes the caller's transaction **rollback-only**: `commit()` rolls it back and
raises `GraphStoreException` rather than making half the query durable. A query refused before it
wrote anything (a syntax or type error up front, a unique violation) leaves the transaction usable
([TRANSACTIONS.md §8](TRANSACTIONS.md#8-conventions-and-gotchas)). Before this a caller that caught
the error and committed committed the half that ran.

**The commit happens before the first row is delivered.** A sink that had already received rows when
the commit failed would hold a result from a transaction that no longer exists — and on the far side of
a socket those rows cannot be recalled. So **a write query's result is materialised, not streamed**;
`Cypher.stream` on a write is streaming in name only. That also removes the binary port's worst hazard:
the producer parks against a slow client, and parking while holding the engine's single writer lock
would freeze every writer in the process ([WIRE.md](WIRE.md)).

**One caveat, and it is harmless.** Dictionary interning is not transactional — a label id is appended
and flushed the moment it is interned — so a rolled-back `CREATE (:Typo)` leaves that id allocated for
the life of the store. Nothing user-visible follows: ids never change meaning, and `/api/schema` derives
its counts from the data rather than the dictionary precisely because of this (§8.1).

---

## 5. Writing

```
CREATE (a:Person {name: $name})-[:KNOWS {since: 2020}]->(b:Person {name: 'Bob'})
MERGE (n:Person {email: $e}) ON CREATE SET n.at = timestamp() ON MATCH SET n.seen = true
MATCH (n:Person) SET n.age = 35, n:Employee, n += {city: 'Bonn'}
MATCH (n:Person) REMOVE n.age, n:Employee
MATCH (n:Person)-[r:KNOWS]->() DELETE r
MATCH (n:Stale) DETACH DELETE n
```

A write query need not end in `RETURN`; a read must, or it would compute a result and discard it.
`RETURN` after a write sees the written state — see "rebinding" below.

**Every query reports what it changed**: `CypherResult.stats()`, a `stats` object in the HTTP response
(present only when something changed), and the `COMPLETE` frame on the binary port. For
`CREATE (n:Person)` that is the entire result — there are no rows, and nothing else would say a node
was made. `propertiesSet` counts keys whose value actually differs afterwards, in either direction, so
writing a key the value it already had counts as nothing.

### 5.1 Writes are off by default

`--allow-write`, and it governs the HTTP route, the binary port and the UI together. Off is the only
defensible default: the difference between handing out a copy of the data and handing out the ability
to delete it is worth an explicit act. A write without it is **403** over HTTP and `READ_ONLY` on the
wire, refused on the event loop before a worker thread or a transaction is spent. **Embedded Java
callers are unaffected** — the flag is a property of the servers, not of the language.

**A grant is the other half, and both have to permit.** Every clause maps to a privilege
([AUTH.md §1.1](AUTH.md)), and `Ast.Query.requiredPrivileges()` answers what a query needs from the
tree — the third policy question decided there, beside `isWrite()` and `readsFiles()`, and for their
reason: the refusal costs no worker thread. The grant is checked first, so a principal who simply
lacks `MERGE` is told that rather than being advised to restart a server they cannot restart. The flag
can only subtract: enabling authentication never widens what a read-only deployment can do.

| Clause | Privilege |
|---|---|
| `MATCH`, `UNWIND`, `WITH`, `RETURN`, `CALL` | `MATCH` |
| `CREATE` | `CREATE` |
| `MERGE` | `MERGE` **and** `MATCH` — it matches before it creates, so a principal who could merge without reading would learn whether a pattern existed by watching whether one appeared |
| `SET` | `SET` |
| `REMOVE` | `REMOVE` |
| `DELETE`, `DETACH DELETE` | `DELETE` |
| `LOAD CSV` | `LOAD_CSV` |

`UNWIND`, `WITH` and `RETURN` map to `MATCH` rather than to nothing: they authorise no access of their
own, but a query made only of them still occupies a worker thread, so `MATCH` is the floor for "may
issue a read query at all". One consequence is surprising and correct — `LOAD CSV … CREATE` needs no
`MATCH` while `LOAD CSV … RETURN row` does, because the importing form reads nothing out of the graph
and the previewing form hands rows back to the caller.

`--allow-drop` stays separate. Dropping destroys a whole database and its directory in one request,
including databases no query addressed; enabling writes is not asking for that.

### 5.2 Decisions worth knowing

**A created relationship is always materialised** — a real record with an id, so `id(r)` resolves,
`SET r.since = …` works later, and `DELETE r` names it exactly. The engine's 16-byte lightweight edge
is cheaper, and creating one silently would produce relationships the query language could not then
address. The Java API remains the way to make those.

**Eagerness comes free.** The executor is clause-at-a-time over a materialised list, so
`MATCH (n) CREATE (:Copy)` structurally cannot feed its creations back into the scan driving it. Other
engines insert explicit Eager operators for this. It also avoids the Halloween problem the engine warns
about — freeing a slot during a `forEachNode` callback changes the bitmap under the scan. **A lazy
pipeline must therefore never carry a write clause**; see [PROGRESS.md](PROGRESS.md).

**Bindings are refreshed after a clause writes.** A `CypherNode` is an immutable snapshot taken when the
variable was bound, so without this `MATCH (n) SET n.age = 30 RETURN n` would return the age from
before — a wrong answer that throws nothing. Each write clause re-reads what it touched and rewrites the
bindings in every row, including inside lists and paths. **Within a `SET`, each row also sees what the
rows before it wrote**: it is rebound against the elements already written before its items are
evaluated, or `MATCH (:User)-[:VIEWED]->(p) SET p.views = p.views + 1` over three users would read the
same stale value three times and lose two updates. **And each item sees the items before it**, as if
each were a `SET` of its own: `SET a.x = 1, a.y = a.x` gives `y` the 1, and `SET n:Done, n.done = n:Done`
is true. The writes are still pending — one store write per element is the point of the next paragraph —
so the row is rebound to a view of them rather than re-read.

**A node this query deleted stays in its rows, and cannot be used.** `RETURN n` after `DELETE n` gives
back the snapshot, as before; expanding from it, `startNode()` of a relationship that ended at it, or any
other read of the store about it is a type error naming the node. The engine's own answer was a
`GraphStoreException`, which a server reports as a store conflict.

**`SET n += {k: null}` removes `k`**, exactly as `SET n.k = null` does; in `SET n = {…}` a null entry is
simply not stored.

**`DETACH DELETE` remembers what its cascade took.** `MATCH (a)-[r]->() DETACH DELETE a, r` reaches the
second row with `r` already gone with `a`; deleting it again would free its id twice, which the store
does not catch. A self-relationship is counted once, though both of its ends list it.

**Several assignments to one element are one store write.** `setNodeProperties` decodes the whole
property block, re-encodes the map, allocates a new block, frees the old and diffs both against the
property index, so `SET n.a = 1, n.b = 2` done twice would do all of that twice and leave a freed block
behind for every assignment after the first.

**Plain `DELETE` refuses a node that still has relationships**, naming `DETACH DELETE`. The engine's own
`deleteNode` always cascades, so this rule belongs to the query language and is enforced above it.

**`SET r.x` on a lightweight edge is refused** rather than silently promoting it: it has no property
storage, and materialising it would change its identity and rewrite adjacency. `DELETE r` on one works,
through `deleteEdge(from, type, to)` — see §3.1.

**Reading a value out and writing it back is not always a round trip.** `byte[]`, `UUID` and `Instant`
are rendered as strings on the way out (§ `GraphAccess.normalise`), so `SET n.x = n.x` on such a
property stores a string. The query language cannot write those three types at all.

**A constraint violation aborts the whole query.** `setNodeProperties` checks uniqueness *after*
mutating, so a violation leaves a partially applied write; it is never caught and continued.

### 5.3 `MERGE` matches the whole pattern, or builds the whole pattern

```
MERGE (s:Supplier {supplierId: sid}) ON CREATE SET s.name = 'Supplier ' + toString(sid)
MATCH (s:Supplier) MATCH (p:Part) MERGE (s)-[r:SUPPLIES]->(p) ON CREATE SET r.primary = true
```

**The whole of it is the rule, and it is where MERGE surprises people.** `MERGE (a:A)-[:R]->(b:B)`
against a graph holding an `:A` and a `:B` but no `:R` between them creates a *second* `:A`, a *second*
`:B` and the relationship — the pattern as a whole did not match, and MERGE has no notion of matching
part of one. Binding the endpoints first, as the second line above does, is how the intended thing is
said. This is correct openCypher and a well-known way to double a graph by accident, so it is asserted
rather than only written down (`MergeTest.testAPatternMatchesEntirelyOrIsBuiltEntirely`).

The rest:

- **One pattern part, not a list.** A comma is refused with its own message: "match all of it or build
  all of it" has no single answer across several independent patterns. Write one `MERGE` for each.
- **`ON CREATE SET` / `ON MATCH SET`**, either, both, in either order. Each is an ordinary `SET` applied
  to the rows of just that branch, so it shares every rule in §5.2 — including one store write per
  element per branch.
- **A match returns every match.** `MERGE (n:Person)` against three of them is three rows, not a choice.
- **The created relationship is materialised**, as `CREATE`'s is, so `ON CREATE SET r.x` works.
- **Undirected is allowed here and refused in `CREATE`**, because it means something: `MERGE (a)-[:R]-(b)`
  asks whether an edge exists *either way*, and creates one left to right if none does. `CREATE` has no
  question to ask, so an arrowless relationship there is an omission.
- **A null property is refused.** `MERGE (n:P {k: null})` is an equality with null, which matches
  nothing, so the match half always failed and the create half built a node without `k` — which the next
  run could not match either. Every run of an idempotent-looking statement added a node. The usual cause
  is a missing parameter or CSV column, and the error says so; Neo4j refuses it the same way.
- **A list of numbers matches its float twins.** `[1, 2] = [1.0, 2.0]`, and the property index keys each
  list by its own element types, so probing for the one written lost the other and MERGE created a
  duplicate of a node that existed. Such a value is not probed (§7).

**Why it is idempotent inside one query.** Rows are processed in order and each match reads the store as
it is *now*, so a row merging a key an earlier row created finds it rather than creating a second. That
rests on a writer reading the current mapping rather than a fixed view ([TRANSACTIONS.md](TRANSACTIONS.md)) —
the same property that gives a write transaction read-your-writes. **This is the one place a write clause
sees what the same query wrote**; the eagerness above still holds for the rows *driving* the clause,
which were fixed before it started.

### 5.4 `CASE`

```
CASE WHEN n.score >= 80 THEN 'high' WHEN n.score >= 0 THEN 'low' ELSE 'unknown' END
CASE n.grade WHEN 'A' THEN 4 WHEN 'B' THEN 3 ELSE 0 END
```

Both forms, told apart by whether `WHEN` follows the keyword immediately. Two things about it are easy
to get wrong in ways that throw nothing:

- **Only the branch that is taken is evaluated.** Not an optimisation — it is what makes
  `CASE WHEN n.d = 0 THEN 0 ELSE n.x / n.d END` a guard rather than a decoration.
- **Null follows §2.** No matching branch and no `ELSE` is null, not an error. A null predicate is not
  true. And in the simple form a null subject matches *no* `WHEN`, including `WHEN null`, because
  equality with null is null rather than true — `IS NULL` is how that question is actually asked.

The simple form compares under Cypher equality, so `1` and `1.0` select the same branch.

---

### 5.5 What is not built, and why

| | Reason |
|---|---|
| `FOREACH` | `UNWIND` covers the common case |
| `OPTIONAL MATCH` | Needs null-padding semantics through the rest of the pipeline |
| List comprehensions, map projections, pattern comprehensions | Not built. `[x IN list]` is refused too: in openCypher it is always a comprehension, so reading it as a one-element list of booleans would be a different answer rather than a refusal |
| List slicing, `list[1..2]` | Not built; indexing one element is |
| `ALL`/`ANY`/`NONE`/`SINGLE` predicates, `EXISTS` subqueries | Not built. `exists(n.prop)`, the function, is |
| User-defined procedures | No procedure *registry* exists. `CALL` itself is built and there is exactly one procedure: `CALL vector.query(label, key, vector, k) YIELD node, score` — see [VECTOR.md](VECTOR.md). Any other name is refused at execution with that message |
| Temporal and spatial types | The store has `Instant`; Cypher's temporal algebra is not implemented |

**Each of these is refused at parse time, by name, with a caret.** That claim was untrue for list
comprehensions, map projections, `EXISTS { … }` and slicing — they failed as an unexpected `|`, `{` or
`..` somewhere further on — until `CypherSemanticsTest` asserted it. The parser accepts only what the
executor can run, so "this build cannot do that" and "you typed it wrong" are different messages:

```
MATCH (n:Person) WHERE n.age > > 30 RETURN n
                               ^
Unexpected '>' in an expression (1:32)
```

```
MATCH (n) FOREACH (x IN [1] | SET n.x = x)
          ^
FOREACH is not supported: use UNWIND, which this build does implement (1:11)
```

`CREATE` refuses three patterns MATCH accepts, each with its own reason, because none describes a
graph that could be built: a variable-length range does not say how many relationships to make, an
undirected arrow does not say which way one points, and a missing or alternated type does not say what
to create.

---

### 5.6 `LOAD CSV`

```cypher
LOAD CSV WITH HEADERS FROM 'file:///people.csv' AS row
MERGE (p:Person {id: row.id}) SET p.name = row.name, p.age = toInteger(row.age)

USING PERIODIC COMMIT 10000
LOAD CSV WITH HEADERS FROM $file AS row FIELDTERMINATOR ';'
MERGE (a:City {name: row.from}) MERGE (b:City {name: row.to}) MERGE (a)-[:ROAD]->(b)
```

Structurally an `UNWIND` whose list comes from outside the process: it binds one variable per record and
multiplies the rows flowing through it. `WITH HEADERS` binds a map, so `row.name` works — `Evaluator`
already indexes a `Map` — and without it a list, so `row[0]` does.

**Every value is a string.** `toInteger`, `toFloat` and `toBoolean` are written where they are meant.
Inferring types is the behaviour that looks helpful and quietly destroys data: a leading-zero identifier
loses its zeros and a product code like `1E5` becomes `100000.0`. An unquoted empty field is `null`; a
quoted one is `''`, which is the only distinction the format offers between "no value" and "an empty
one" and is worth keeping.

**The reader is a character state machine, not a loop over lines**, because a quoted field may contain a
newline. `Corpus.splitCsv` in the tests is line-based and honest about being "RFC 4180 enough for this
file"; it is the test corpus for `CsvRowReader`, not its implementation. A BOM is dropped, `\r\n` and a
lone `\r` both end a record, `""` is one literal quote, a short row pads with `null` and a **long** row
is refused naming its line — the likely cause is an unquoted separator, which means the row's other
cells are misaligned too and importing it would write wrong data under correct-looking keys.

#### `FROM` is not a keyword, and must not become one

`FROM`, `HEADERS` and `FIELDTERMINATOR` are matched **contextually**, as ordinary identifiers at the
positions inside the clause where they can appear. Reserving them in the lexer would be the obvious
implementation and would silently break the language: a keyword token carries its **folded** spelling,
and `labelName`/`propertyKeyName` accept keywords as ordinary names, so `r.from` would become `r.FROM`.
A column called `from` is near-universal in the edge lists this clause exists to load.

> That folding was a live defect until this work, not a hypothetical: `(n:Order {end: 1})` really did
> store a label `ORDER` and a key `END`, contradicting §1 and `labelName`'s own javadoc, and
> `CypherParserTest` asserted the wrong behaviour rather than catching it. `Token` now carries the
> original spelling beside the folded one, and those two positions — plus the projection-column naming,
> which had the same bug — read it.

#### The file is not this module's business

`graph-cypher` has no third-party dependencies and no `java.nio.file` in `src/main`, and adding a file
reader would have put the **sandbox policy** in the parser, where the server cannot configure it. So the
module parses CSV and the caller supplies the bytes:

```java
public interface CsvSource { Reader open(String url); CsvSource DENY = url -> { throw ... }; }
```

**The default is `DENY` on every overload that does not name one.** An embedded caller cannot be turned
into a file-reading primitive by a query string it did not write — and a query string is very often the
part of an application that came from outside it.

#### On a server, it is off by default and confined to one directory

| | |
|---|---|
| Flag | `--allow-load-csv` / `GRAPH_ALLOW_LOAD_CSV`, **off by default** |
| Directory | `--import-dir=<path>` / `GRAPH_IMPORT_DIR`, required with the flag and refused without it |
| HTTP | **403** `loadcsv` before a worker thread is spent |
| Wire | `READ_ONLY`, on the event loop |
| MCP | **Always refused**, whatever the flag says |

**`--allow-write` does not cover this, and that is the whole reason the flag exists.** `LOAD CSV … RETURN
row` creates nothing, so `Ast.isWrite()` is false for it: a server deliberately left read-only would
have read files off its own disk for anything that could reach the port. `Ast.Query.readsFiles()` is the
companion predicate, answered from the tree so the refusal costs nothing.

`SandboxedFileCsvSource` is the enabled case. `file:///x.csv` means `<import-dir>/x.csv`, **not**
`/x.csv` — the leading slashes are stripped, so a URL cannot name a file outside the root before any
check runs, which makes containment structural rather than a filter. Then `normalize()`, then a
`startsWith` check, then `toRealPath()` and the check again, because an import directory is very often
somewhere a less-trusted process drops files and a symlink planted in one is a realistic way out.
`http://` is refused **by name**: an SSRF primitive reachable by anything that can log in is worse than the file
read it would be replacing. A refusal never says whether the file exists, so the clause cannot be used
to probe a filesystem the caller cannot otherwise see.

**MCP refuses it unconditionally**, which is not a configuration question. That port is the one an LLM
drives ([MCP.md](MCP.md)); a `LOAD CSV` names a file rather than anything in the graph, and a model that
can read the import directory and return it as query results is a wider capability than "query this
database". A bulk import is an operator's action.

#### `USING PERIODIC COMMIT`

The clause exists because of a property of everything above it. **A write query cannot stream** — it
commits before delivering a row (§4.1) — so `LOAD CSV … CREATE` runs through the materialised path and
its peak memory is the whole file. Batching bounds both the heap and the transaction: *n* rows are read,
the remaining clauses run over exactly those rows, that transaction commits, and the reader continues
from where it stopped. The file is read once, from one reader held open across the batches.

**It gives up atomicity, and the failure mode has to be stated rather than discovered.** If row 70,000
violates a constraint, every batch before it is committed and durable and there is nothing left to roll
back. The error names how many rows survived, because "some prefix of your file is loaded" is only
actionable if the caller is told which prefix — and the advice is to write the load with `MERGE` rather
than `CREATE`, which makes a re-run idempotent. The batch in flight *is* rolled back, so the store holds
whole batches or nothing, never half of one.

Refused in two cases, each because the alternative is worse than a refusal:

- **Inside a transaction the caller opened.** Everywhere else a write query joins one and commits
  nothing; here committing is the entire mechanism, and ending somebody else's unit of work is not this
  code's decision.
- **When `LOAD CSV` is not the first clause.** A batch boundary is only meaningful if the CSV is what
  drives the rows; with anything else first the rows were fixed before the load ran.

A bare `USING PERIODIC COMMIT` takes 4,000 — the same number `DemoData.Batch` settled on, and for the
same reason. The upper bound is 1,000,000, because a batch is materialised before it is committed.

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

**For writes, three more.** `SET n:Label` and `REMOVE n:Label` needed `addNodeLabel` / `removeNodeLabel`
— the engine could only set a label set at creation. The capability existed one layer down
(`NodeStore.labels(id, int[])`, which rewrites the three inline slots and frees or reallocates the
overflow block) but nothing called it, and `IndexCatalog` had no `onLabelsChanged` beside its three
siblings, so a relabelled node would have kept its old label-index entries and gained none. That hook is
now there and rewrites only the difference. `hasWriteTransaction` is the third, and §4.1 says why.

---

## 7. Planning, and what it costs

Start points, most selective first: a variable already bound by an earlier clause → a **declared
composite index** over one of the pattern's labels and one of its literal properties → an indexed
property equality → a label scan → a full node scan. So
`MATCH (a:Person {email:$e})-[:KNOWS]->(b)` is one index probe and one expansion.

**A `WHERE` equality seeds the same probe.** `MATCH (n:Person) WHERE n.email = $e` is the inline map
spelled differently and now costs the same: a top-level `AND`-ed `var.key = <literal | $param |
other.key>` on the leading node is a candidate for the probe, after the inline map. Only a conjunct (under
`OR` or `NOT` the row could pass without it), only a scalar value, and the `WHERE` still runs over every
row — so a hint can narrow the candidates and never decide the answer.

**An integral number is probed in both spellings.** The index is keyed on the stored value's own type,
so `30` and `30.0` hash apart there while `30 = 30.0` is true in Cypher, and an empty probe is an answer
with no scan behind it. `MATCH (n {age: 30.0})` found nothing and `MERGE` duplicated the node; both
spellings are now probed and the results joined (exact below 2^53).

**A list holding a number is not probed at all.** Each element has a twin, so a list has one spelling per
combination of them — `[1, 2]` equals `[1.0, 2.0]` and `[1, 2.0]` — and probing two of them is no more
right than probing one. Such an inline value takes the label scan (or the full one) and is checked with
`=` per candidate; `CypherDifferentialTest` found the lost rows. A list of strings and booleans has one
spelling and is probed as before.

**The composite step is taken only when the pair has been declared** through
`GraphStore.createIndex(label, key)` ([INDEX.md §3.3](INDEX.md)); an undeclared pair falls through to
the property probe, which is what the planner did before. There is no Cypher DDL for a declaration —
the query language uses an index it cannot create.

**Known costs, stated rather than discovered:**

- **A query whose every clause streams is pushed, and `LIMIT` stops the scan.** `MATCH`, `WHERE`,
  `UNWIND` and a projection with no `*`, no `DISTINCT`, no `ORDER BY` and no aggregate form a chain of
  `Row -> boolean` consumers from the seed to the sink; a false anywhere in it reaches
  `GraphStore.forEachNodeWhile` and stops the bitmap walk. That is a change in complexity rather than a
  constant: ten rows out of ten million nodes reads ten. See §7.1.
- **Everything else is still materialised per clause**, so its peak memory is its widest intermediate
  result. `ORDER BY`, `DISTINCT`, aggregation and `UNION` need their whole input by definition;
  `RETURN *` needs it to know its own columns; and a write materialises so that it can commit before
  delivering a row. [WIRE.md §0](WIRE.md) states the same distinction from the transport's side, where
  it is easiest to overclaim.
- **DISTINCT is a hash set over `Values.groupingKey`**, not over the values: `1` and `1.0` are the same
  Cypher value with different hash codes, and two nodes are equal by id rather than by contents, so the
  key normalises both. It was quadratic before the key existed.
- **Only the first label of a pattern drives the scan**; the rest are verified per candidate. The
  rarest would be the better choice, and the store does not expose per-label counts. A declared
  composite index is the way to say which pair matters, because without statistics the planner cannot
  work it out ([INDEX_TASKS.md IDX-8](INDEX_TASKS.md)).
- **No join reordering.** A pattern is expanded left to right from its chosen seed.
- **A node nothing can read is not read.** An anonymous `()` with no labels and no properties, in a part
  with no path variable, only says where the next step starts, so the expansion binds its id and skips
  the record and the property block; a path's intermediate nodes are read only when the path is bound.
  On a two-hop anonymous count over 20,000 nodes and 60,000 edges, that and the next item took the query
  from 336 ms to 210 ms (`CypherExecutionBenchmark`, median of five interleaved runs against the previous
  build).
- **A projection builds its row once.** Each item used to copy the whole row map (`Row.with`), so a row of
  k columns cost k maps; and every projected row also carried a copy of its source row, which only an
  `ORDER BY` reads. Without one it is no longer kept, nor narrowed away again after a `WITH`. On
  `UNWIND range(1, 200000) … WITH x, x * 2 + 1 AS y, x % 7 AS z WHERE z = 3 RETURN sum(y)` that is 79 ms
  to 22; on a two-`WITH` chain over 20,000 nodes, 20 ms to 15.
- **One row copy per step, not one per binding**, and no path assembled for a part that does not name
  one: the expansion used to copy the row map once for the relationship and again for the node, and
  copy both path lists for every candidate whether or not anything would read them.
- **A write query is materialised end to end**, and commits before its first row is delivered (§4.1).
  Nothing about a write streams, and `Cypher.stream` does not change that.
- **Writers serialise on one lock per store**, engine-wide. A long write query blocks every other
  writer in the process, including embedded callers. The binary port bounds the wait so it is reported
  rather than endured ([WIRE.md](WIRE.md)).

---

### 7.1 What streams, and what cannot

| Streams | Blocks, and why |
|---|---|
| `MATCH`, `WHERE`, `UNWIND` | `ORDER BY` — the first row out depends on the last row in |
| `WITH` / `RETURN` projection | `DISTINCT` — cannot know a value is new without the ones before it |
| `SKIP` / `LIMIT`, per stage | Any aggregate — same |
| | `RETURN *` / `WITH *` — the column set is the union of variable names over *every* row, and the header goes out first |
| | `UNION` (the deduplicating form). `UNION ALL` could stream and does not yet |
| | Every write clause — §5.2 |

**All or nothing per query part**, rather than the longest streaming prefix. A blocking clause buffers
its whole input whatever runs above it, so pushing only the prefix would bound nothing; the one thing
it could still buy is an early stop before the blocking clause, which is rare enough not to be worth a
second execution shape.

The fallback is not a degraded mode — it is what the whole engine did before this path existed, and
`LazyExecutionTest` runs the same queries both ways and compares them cell by cell.

**Push and not pull**, and the source is the reason rather than a preference: the unindexed seed is
`forEachNode`, a callback. Inverting it into an iterator needs a thread to run it on, or eight bytes
per live record materialised up front — which is precisely the case that would run out of memory.

**A deviation from openCypher, found while writing this and recorded rather than changed.** In
`WITH … SKIP … LIMIT … WHERE …` the clause order is openCypher's, but this engine has always *applied*
the `WHERE` **before** the `SKIP` and `LIMIT`, where openCypher applies it after. So
`WITH n LIMIT 3 WHERE n > 100` gives the first three rows above 100 here, and whatever of the first
three rows is above 100 elsewhere. The streaming path was written to match the list path rather than
the standard, because the two paths agreeing is the contract that stops a query changing its answer
depending on its shape — but the deviation is real and belongs to both.

---

### 7.2 Shortest paths are a search, not a filter

```
MATCH p = shortestPath((a:Station {name:'Airport'})-[:CONNECTS*..10]-(b:Station {name:'Zoo'}))
RETURN size(nodes(p)) AS nodes, size(relationships(p)) AS rels
```

`shortestPath(…)` and `allShortestPaths(…)` wrap one pattern part and change **how it is matched**. They
are a property of the part in the AST (`PatternPart.selector`) rather than a function over the paths it
produces, and that is the whole design decision: the depth-first enumeration behind `-[*1..10]-` is
exponential in the bound *by intent*, because there every path is the answer. Running it and keeping the
minimum would make a search that is linear in the graph quadratic in the number of routes through it.

So the two constructs run a **breadth-first search** instead — one expansion per node, distance fixed on
first sight, routes rebuilt backwards from the end. Three consequences worth stating:

- **Relationship uniqueness is free.** Distance strictly increases along any rebuilt route, so no node
  and therefore no relationship can appear in one twice. The `used` bookkeeping the depth-first walk
  carries has nothing to do here.
- **One row per pair of endpoints, not per query.** Both ends are *patterns*, so a search may reach
  several nodes that satisfy the end pattern, each at its own distance and each contributing its own
  shortest path. The search therefore cannot stop at the first node it matches — which is the obvious
  shortcut and the wrong answer.
- **`allShortestPaths` output is not bounded by the graph.** The walk is, but the number of minimal
  routes is not; it is enumerated rather than capped, because it is what the caller asked for.

Restrictions, all of them openCypher's rather than this build's, refused at parse time by name:

| | |
|---|---|
| Exactly one relationship | A search runs between two endpoints; a longer chain names no single pair |
| Minimum of 0 or 1 | The walk goes outward one distance at a time. A higher minimum means finding the shortest route and rejecting it for being short — a filter over every path, which the plain pattern already expresses |
| `MATCH` only | Refused in `CREATE` (it describes a search, not a graph to build) and in an expression, where it would otherwise fail on the arrow inside its own argument |

An inline map on the relationship — `-[:R* {open:true}]-` — restricts **which edges may be crossed**
rather than which finished paths survive, so an excluded edge cannot shorten the route.

`ShortestPathTest` runs both constructs against the depth-first enumeration over every pair in a diamond
graph: the search's length must equal the minimum of the enumeration's, and `allShortestPaths`' row count
must equal how many of the enumerated routes have that length. That is the assertion which would survive
a rewrite of the search.

---

## 8. The server and UI

```bash
java -jar graph-server/target/graph-server.jar /path/to/store 8080
java -jar graph-server/target/graph-server.jar --demo /tmp/demo 8080   # §8.3
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
| `POST /api/query` | `{"query": "...", "parameters": {...}}` → columns, rows, a `graph` of nodes and links, and — only when the query changed something — `stats`. A write without `--allow-write` is 403 |
| `GET /api/schema` | Labels and relationship types with live counts. `?refresh=true` forces a rescan |
| `GET /api/stats` | Node and relationship counts, labels, storage bytes, open views, and which database answered |
| `GET /api/databases` | Every database under the root, which is the default, and whether dropping and writing are enabled |
| `PUT /api/databases/{name}` | Creates one. `DELETE` drops it, and only with `--allow-drop` |
| `GET /api/indexes` | The declared composite indexes of one database, the store-wide unique keys beside them, and whether writing is enabled |
| `POST /api/indexes` | `{"label": "...", "key": "...", "unique": false}` declares one, backfill included. `DELETE /api/indexes/{label}/{key}` drops it. Both need `--allow-write` |
| `GET /` | The UI |

**A request of the wrong shape is a 400, never a 500.** A body that is not JSON, JSON that is not an object, a `query`
that is not a string, `parameters` that are not an object, an index `label`, `key` or `unique` of the wrong type — each
is `400` with `error: "json"`, `"empty"` or `"parameters"` and a message saying what to send. They used to escape
the handler as exceptions and reach the router as `500 Internal Server Error`, which tells a client to retry a request
that can never succeed. A backup of a database that does not exist is `404`, like every other route, not a `409`
"store" (`HttpRouteEdgeCaseTest`).

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

The signal is consulted on store touches, on CSV rows and, since this was found missing, **on every row
an `UNWIND` produces**. A query can multiply rows without touching the store at all —
`WITH range(1, 300) AS l UNWIND l AS x UNWIND l AS y RETURN count(*)` is a square — and aggregated,
nothing reaches the sink either, so neither existing check could fire and the deadline bounded nothing.
One gap is left and stated: a single expression that builds a huge list or string in one step
(`l + l`, doubled clause after clause) cannot be interrupted part way, because the work is one
allocation; `range()` alone is capped (§1.1).

### 8.1 `/api/schema` is a scan

The engine interns node labels and relationship types into **one** dictionary — `internEdgeType`
delegates to `internLabel` — so the dictionary can say that `KNOWS` exists but not that it is a
relationship type, and it still holds labels whose last node was deleted, because ids are never reused.
A sidebar built from it would mix the two and list things that match nothing.

So the counts come from the data: one pass over the live nodes, reading each node's labels and its
outgoing edges. Outgoing only, or every relationship would be counted twice. It is O(nodes + edges),
runs on a worker thread inside a snapshot view, and is cached for 60 s; the response carries
`elapsedMillis` and `cached` so the UI can say which answer it is showing.

**The index routes are the one schema surface the query language does not have.** There is no
`CREATE INDEX` in this subset: the planner *uses* a declared composite index
([INDEX.md §3.3](INDEX.md)) and cannot declare one, so before these routes a declaration was a Java
call and a browser could not make one at all. They are behind `--allow-write` rather than
`--allow-drop`, because dropping an index destroys something the store rebuilds from the data with one
call, and dropping a database does not. They are deliberately not folded into `/api/schema`: that is a
cached scan of the *data*, and a declaration is a read of one catalog page and is exact right now.

### 8.2 The browser UI

A React application under `graph-server/src/main/frontend`, bundled by Vite straight into
`target/classes/webroot` — which is what `StaticHandler` serves and what ends up inside the jar, so
`mvn package` cannot ship a stale bundle. npm is treated exactly as cargo is: absent means skipped,
with a message, and `-Dui.skip=true` forces the same path. Without a bundle the server still answers
every API route and the index page says so rather than returning a 404.

| | |
|---|---|
| Left | Labels and relationship types with counts, each one clickable into a query; below them the declared indexes, with a form to declare one and a Drop beside each; below that the inspector — the selected node's labels and properties, or the selected relationship's, with its incident relationships as links to walk |
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
- **The index panel writes a query rather than running one.** Clicking a declared index fills the
  editor with `MATCH (n:Person {email: 'value'})` and stops there, because the probe needs a value only
  the user has — running it would return nothing and read as a broken index. Dropping one asks for a
  confirmation rather than the typed name a database drop demands, and says outright when the
  declaration is also a unique constraint, because that constraint goes with it.
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
predates the query language being able to write, it is opt-in, it runs before the server binds, and it is independent
of `--allow-write`.

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

**Authentication, and transport security on one port.** The HTTP and binary ports require a login
([AUTH.md](AUTH.md)). The binary port can encrypt itself ([WIRE.md §7.1](WIRE.md)); this one — the HTTP
port, which serves this UI and `/api/query` — cannot, so put a proxy in front of anything beyond a host
you trust or a password crosses in cleartext — [OPERATIONS.md §9.1](OPERATIONS.md). What a principal may do is the grant and the flag
together: both have to permit, and the flag can only subtract.

---

## 9. Tests

| Class | Cases | |
|---|---|---|
| `CypherParserTest` | 30 | Tokens, precedence, ranges, every refusal naming itself with a caret, and that a label or property key named after a keyword keeps its spelling |
| `io/CsvRowReaderTest` | 15 | RFC 4180: quoted newlines, the `""` escape, BOM, `\r\n`, a custom terminator, and a record straddling the buffer |
| `LoadCsvTest` | 27 | §5.6 end to end: both row shapes, both execution paths, the reader closed on every exit including an early `LIMIT`, `DENY` by default, and periodic commit's partial-failure semantics |
| `NullSemanticsTest` | 17 | §2, on its own |
| `CypherSemanticsTest` | 28 | §2.1 and the rest of this round's semantic defects, one case each, every one failing on the code before it: list `+`, exact numeric comparison, orderability, code points, lone surrogates, null-bound pattern variables, rebinding, self-loops, MERGE on null and on a numeric list, SET item order, refusals by name |
| `CypherDifferentialTest` | 5 | Random predicates, groupings, orderings and variable-length patterns against an evaluator written separately over the Java API, and one equality through every access path with and without a declared index. Found the numeric-list probe (§7) |
| `integration/CypherFuzzTest` | 1 (+1 `fuzz`) | Grammar, mutated corpus and noise: every input ends in a documented outcome — a result or a classifiable exception — within its deadline; every successful read gives the same rows streamed and materialised; every rolled-back write leaves the graph as it was. Seeded, replayable by (seed, input), failures minimised. Found five defects |
| `CypherExecutionTest` | 44 | Every clause, end to end |
| `CypherVersusApiTest` | 12 | **The load-bearing one** — see below |
| `CypherWriteVersusApiTest` | 7 | Its inverse, and the load-bearing one for writes: run a write in Cypher, verify with the Java API, then `verify(FULL)` |
| `CypherWriteTest` | 37 | Every write clause end to end, each finishing with `verify(FULL)` |
| `CypherWriteTransactionTest` | 9 | §4.1: the transaction joined, the read view refused, a failed query leaving the segment files byte-identical, a failed query making the caller's transaction rollback-only, and a concurrent reader's snapshot unmoved |
| `LazyExecutionTest` | 14 | §7.1: what stops the scan early — counted at the store rather than timed — what correctly does not, and the two paths agreeing cell by cell |
| `integration/CypherCorpusTest` | 4 | The `test-data/` corpus: four openCypher dumps and 29 queries whose counts were derived elsewhere and proven against Neo4j. See §9.2 |
| `VarLengthPathTest` | 8 | §3.2, against a graph where the difference shows |
| `CypherSnapshotTest` | 4 | §4: views closed, joined, and a query atomic against a live writer |
| `GraphServerTest` | 25 | The HTTP contract, both result shapes, the schema scan and its cache, the id-precision regression, and the write flag with its 403 |
| `DemoDataTest` | 10 | §8.3: the graph is connected as claimed, its distances match the world, and it is the same every time |
| `GraphServerOptionsTest` | 23 | The command line, so an ignored flag fails a test rather than starting a server that does the wrong thing — including that `--allow-load-csv` and `--import-dir` are refused apart |
| `io/SandboxedFileCsvSourceTest` | 12 | **The security test for §5.6**: traversal, absolute paths, symlinks out, `http://`, and that a refusal never says whether the file exists. Every case asserts nothing was read |
| `model/graph.test.ts` | 12 | The merge rule: node identity across results, held links, and that every change advances the version |
| `model/colors.test.ts` | 5 | A colour is a function of a name, and the demo graph's labels do not collide |
| `e2e/graph-browser.spec.ts` | 30 | The UI in a browser against a real server — see §9.1 |

`CypherWriteVersusApiTest` is the write half, and it has a second oracle the read half does not need:
`verify(FULL)`. The invariant checkers — bidirectional edges, degree counters, adjacency chains, no
dangling references, no orphan blocks — are entirely outside the query layer, and they catch what a
hand-written assertion never would: a relationship recorded on one endpoint and not the other, a degree
counter that drifted, an index entry pointing at a freed slot.

`CypherVersusApiTest` is the one that matters. [TESTING.md](TESTING.md) opens by observing that every
defect this project has found came from a test comparing the engine against **something outside it**.
The query layer's version of that is: seed a random graph, and require every query's answer to equal
what the Java API computes directly. The graph is seeded rather than chosen, so it contains the shapes
an author would not have thought to write — nodes with several labels and none, missing properties,
parallel edges, self-loops, and a mix of materialised and lightweight relationships.

`CypherDifferentialTest` and `CypherFuzzTest` take the same idea one step further: the *queries* are
drawn as well as the graph. The differential's oracle shares no code with `Values` — three-valued logic
written out again, numbers compared as `BigDecimal` — because two implementations of one rule that agree
on thousands of random inputs is evidence a hand-written case is not. The fuzzer's contract is the one a
server depends on: the exceptions `GraphServer.respondToFailure` maps to a 4xx are the only ones a query
may end in, so an `IllegalArgumentException` from the store, a `NullPointerException` or a
`NumberFormatException` from a trusting parser is a finding whatever the input was.

```bash
mvn test -pl graph-cypher -am -Dtest=CypherFuzzTest -Dcypher.fuzz.seed=7 -Dcypher.fuzz.iterations=100000
mvn test -Pall-tests -pl graph-cypher -am -Dtest=CypherFuzzTest   # the long run, fresh seed, printed
```

The four it found on its first run of 3,000 inputs: a refresh after a write switching over a binding
that was null (`NullPointerException`); `vector.query` without an index and with an empty vector
(the engine's `IllegalStateException` and `IllegalArgumentException`); and a node deleted earlier in
the query expanded later (`GraphStoreException: No live node`). A longer run found a fifth on three
seeds: `SET x = {…}` with `x` null (`NullPointerException`; it is now the no-op `SET x.k = …` already
was, and so are `SET x:L` and `REMOVE x:L`). Since then 300,000 inputs over seeds 5 to 14 end in
documented outcomes.

### 9.2 The external corpus

`test-data/` holds four graph dumps written in plain openCypher and a catalogue of 29 queries with the
node and relationship counts each must return. Neither was written against this engine: the counts were
derived from the generation rules and then proven against a real Neo4j. That makes it the only test here
whose expectations this project did not author, which is a different kind of evidence from everything
in §9.

`CypherCorpusTest` imports each dump and runs its queries, classifying every one as **agreed**,
**refused by name**, or **wrong** — and only the last was ever a failure, because the subset is
documented.

**All four dumps import and all 29 queries agree.**

| | |
|---|---|
| `01-movies` | 29 nodes / 52 relationships. 7 of 7 queries agree |
| `02-transit` | 27 / 64. 7 of 7 |
| `03-social` | 188 / 570. 8 of 8 |
| `04-supply-chain` | 136 / 352. 7 of 7 |

So the bar is that, and not a threshold. It was a pair of counts while parts of the corpus were out of
reach — first twelve queries overall, then every query over an importable dataset — and each time the
number was raised the slack it allowed became a place a regression could hide. Now: every query agrees,
every dataset in the catalogue is covered (asserted separately, so a fifth dump cannot arrive
unnoticed), and each store is checked with `verify(FULL)` after its import. The *refused* outcome stays
in the classification because the subset is still a subset — a query added tomorrow may fall outside it,
and that should be reported rather than counted as a failure to parse valid Cypher.

**`04-supply-chain` is run twice.** It is `MERGE`-only and deliberately idempotent, so a second run must
not change a count or rewrite a property. That is a far sharper test of MERGE than any single statement:
one that quietly created duplicates would import perfectly the first time and double the graph the
second.

The corpus has earned itself three times, all on the two features it was blocking:

1. `t-07` failed as **wrong** rather than refused, because §5.5 listed `shortestPath` as refused by name
   and the parser in fact produced "expected a node pattern but found an identifier" — a spec claim no
   test had ever checked.
2. `t-07` then failed as **refused**, which is what prompted §7.2 and the breadth-first search behind it.
3. Two whole dumps could not be imported at all, one needing `CASE` (§5.4) and one `MERGE` (§5.3) — the
   two clauses this subset was furthest from openCypher on, identified by a corpus rather than by
   guesswork about what to build next.

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

_Git HEAD revision: `f2b6d5f`_
_Last updated: 2026-10-24 (client, server and CLI review: closed streams, budget leak, slot release order, shutdown grace, strict UTF-8, MCP argument shapes, atomic backups)_
