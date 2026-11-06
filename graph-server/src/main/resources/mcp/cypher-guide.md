# Cypher in MetaLoom // Graph

This engine implements a **subset** of openCypher. Everything below is supported; anything not listed
probably is not, and unsupported syntax is **refused at parse time by name**, so you get
"FOREACH is not supported" rather than a confusing syntax error.

## Read this first

- **Map projections (`n {.name}`) do not exist.** Write a map literal: `{name: n.name}`.
- **Pattern predicates (`WHERE (a)-->(b)`) do not exist.** Write `WHERE EXISTS { (a)-->(b) }`.
- **A missing label is not an error.** `MATCH (n:Persno)` returns zero rows rather than complaining.
  Call `graph_schema` first and use the label names it gives you.
- **Writes may be off.** If `graph_query` refuses a `CREATE`, the server was started without
  `--allow-write`. Do not retry; say so.
- **Use `$name` parameters** rather than interpolating values into the query text.

## Clauses

```
MATCH (a:Label {key: $value})-[r:TYPE]->(b) WHERE a.n > 1 RETURN a, r, b
MATCH (a:Label) OPTIONAL MATCH (a)-[:TYPE]->(b) RETURN a, b        -- b is null where nothing matched
MATCH (a) CALL (a) { MATCH (a)-->(f) RETURN count(f) AS c } RETURN a, c   -- also CALL { WITH a ... }
WITH a, count(*) AS c WHERE c > 2 RETURN a.name AS name, c ORDER BY c DESC SKIP 0 LIMIT 10
UNWIND $list AS x RETURN x
CREATE (a:Label {key: 'v'})-[:TYPE]->(b:Other)
MERGE (a:Label {key: 'v'}) ON CREATE SET a.made = true ON MATCH SET a.seen = true
SET a.key = 1, a:NewLabel, a += $map
REMOVE a.key, a:Label
DETACH DELETE a
CALL vector.query('Label', 'embedding', $vector, 10) YIELD node, score
... UNION [ALL] ...
```

`RETURN` is required unless the query writes. `RETURN DISTINCT` and `RETURN *` both work.

## Patterns

- Node: `(v:Label:Second {key: value})` — every part optional.
- Relationship: `-[v:TYPE|OTHER*1..3 {key: value}]->`, `<-[...]-`, or `-[...]-` for either direction.
- Variable length: `*`, `*2`, `*1..3`, `*..3`.
- Shortest path: `shortestPath((a)-[:TYPE*]->(b))` and `allShortestPaths(...)`, in `MATCH` only, over
  exactly one relationship pattern.
- **`CREATE` refuses** variable-length ranges, undirected arrows and missing or alternated types —
  none of them says what to build.

## Expressions

Operators, loosest binding first: `OR`, `XOR`, `AND`, `NOT`, comparison
(`= <> < <= > >=`, `IN`, `STARTS WITH`, `ENDS WITH`, `CONTAINS`, `IS NULL`, `IS NOT NULL`),
`+ -`, `* / %`, `^`, unary `-`, then property access, indexing and `:Label` as a predicate.

`CASE WHEN ... THEN ... ELSE ... END` is available, in both forms.

- List comprehensions: `[x IN list WHERE x > 0 | x * 2]`.
- Quantifiers: `all(x IN list WHERE ...)`, `any`, `none`, `single`.
- Subquery expressions: `EXISTS { (a)-[:T]->() }`, `COUNT { MATCH (a)-->(b) WHERE ... }`,
  `COLLECT { MATCH (a)-->(b) RETURN b.name }`. Read-only; outer variables are visible inside.

## Temporal values

`date('2020-01-31')`, `localtime('12:00')`, `time('12:00+01:00')`, `localdatetime('2020-01-31T12:00')`,
`datetime('2020-01-31T12:00[Europe/Vienna]')`, `duration('P1M2DT3H')`, also from maps
(`date({year: 2020, month: 1})`) and with no argument for now. Components: `d.year`, `d.month`, `dt.hour`,
`dur.days`. Arithmetic: temporal ± duration; difference with `duration.between(a, b)`. Truncation:
`date.truncate('month', d)`. They are stored as typed values, so `WHERE n.at >= date('2020-01-01')` compares
dates. A datetime without a zone is UTC.

Comments are `//` and `/* */`. Backticks quote a name with spaces. Keywords are case-insensitive;
identifiers are not.

## Aggregation

`count(*)`, `count(x)`, `sum`, `avg`, `min`, `max`, `collect`, each accepting `DISTINCT`.
Grouping is implicit: the non-aggregated items in the projection are the grouping key.

## Null semantics

Comparison with `null` yields `null`, not false. `WHERE` keeps a row only when the predicate is
`true`. Use `IS NULL` / `IS NOT NULL` rather than `= null`.

## Not implemented

`FOREACH`, map projections, pattern comprehensions, pattern predicates, list slicing,
`CALL { … } IN TRANSACTIONS`, `OPTIONAL CALL`, user-defined procedures, spatial types.

`LOAD CSV` is implemented, but **not over this port**: it names a file rather than anything in the
graph, and loading one is an operator action. It is refused here whatever the server was started
with. Do not retry it.

## Performance

- A `WHERE` on an **indexed** `(label, key)` pair is a lookup; on an unindexed one it is a full scan.
  `graph_list_indexes` says which is which, and `graph_stats` says how big the store is.
- Queries have a server-side timeout. If one is abandoned, narrow it rather than retrying it.
