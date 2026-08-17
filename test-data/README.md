# cypher-data

Four independent graph fixtures written in [openCypher](https://opencypher.org/), a CSV
catalogue of queries over them with the node and relationship counts each query must
produce, and a one-command runner that proves the two agree against a real Neo4j.

```
datasets/01-movies/        29 nodes / 52 rels    hand written, one single CREATE
datasets/02-transit/       27 nodes / 64 rels    hand written, many small statements
datasets/03-social/       188 nodes / 570 rels   generated with UNWIND + modular arithmetic
datasets/04-supply-chain/ 136 nodes / 352 rels   MERGE only, idempotent
queries.csv                                      30 checks across the four datasets
run-verification.sh                              boot Neo4j, import, verify, tear down
verify.py                                        the checker itself
```

Each dataset folder has its own `README.md` describing the schema, the generation rules
and the queries that target it.

## Run it

```bash
./run-verification.sh                       # all four datasets
./run-verification.sh --dataset 03-social   # just one (repeatable)
./run-verification.sh --keep                # leave Neo4j up on http://localhost:7474
```

The script pulls `neo4j:5.26-community` if it is missing, starts a throwaway container,
waits for bolt, and hands over to `verify.py`. It removes the container on exit unless
`--keep` was given, and exits non-zero if any check fails. Overridable via the environment:
`NEO4J_IMAGE`, `NEO4J_PASSWORD`, `HTTP_PORT`, `BOLT_PORT`.

Neo4j Community serves a single user database, so the datasets are processed **in
sequence** — wipe, import, check — rather than side by side. With `--keep`, the graph left
resident is the last dataset of the run.

Expected output, and the current state of this repository:

```
30 checks, 30 passed, 0 failed  (details in verification-results.csv)
```

`verification-results.csv` is rewritten on every run with the expected and actual counts
per query.

## queries.csv

| column | meaning |
|---|---|
| `dataset` | folder under `datasets/` the query runs against |
| `query_id` | stable id, also used in the dataset READMEs |
| `description` | what the query selects |
| `cypher` | a single-line query returning exactly two columns, `nodes` and `rels` |
| `expected_nodes` | expected value of the `nodes` column |
| `expected_relationships` | expected value of the `rels` column |

Every query returns its own counts, so one uniform assertion covers the whole catalogue:

```cypher
MATCH (p:Person)-[r:ACTED_IN]->(m:Movie)
RETURN count(DISTINCT p) + count(DISTINCT m) AS nodes, count(r) AS rels
```

Two shapes are worth copying when adding rows:

- **Node-only queries** return `0 AS rels`.
- **Same-label traversals** (`User → User`, `Station → Station`) must not sum
  `count(DISTINCT a) + count(DISTINCT b)` — the endpoints can be the same node and would be
  counted twice. Use the union form instead, as `t-06`, `s-05` and `s-08` do:

  ```cypher
  ... WITH collect(DISTINCT a) + collect(DISTINCT b) AS ns, collect(DISTINCT r1) + collect(DISTINCT r2) AS rs
  UNWIND ns AS n WITH rs, count(DISTINCT n) AS nodes
  UNWIND rs AS r RETURN nodes, count(DISTINCT r) AS rels
  ```

The expected counts were derived from the dumps and the generation rules first and then
proven against a live database — not read back out of one.

## openCypher compliance

The dumps and the queries stay inside openCypher 9. Nothing in `datasets/` or `queries.csv`
is Neo4j specific; everything Neo4j specific (containers, `cypher-shell`, wiping between
datasets) lives in `run-verification.sh` and `verify.py`.

**Used:** `CREATE`, `MERGE` + `ON CREATE SET`, `MATCH`, `WHERE`, `WITH`, `UNWIND`,
`RETURN`, `CASE`, pattern predicates, variable-length patterns, `shortestPath`, list
literals and list indexing, and the openCypher functions `count`, `collect`, `sum`, `size`,
`range`, `toString`, `nodes`, `relationships`. Property values are limited to string,
integer, float, boolean and list.

**Deliberately avoided:** `CREATE INDEX` / `CREATE CONSTRAINT` (schema DDL is outside the
openCypher grammar), `CALL { … }` and `EXISTS { … }` subqueries, quantified path patterns,
`CALL db.*`, APOC, `elementId()`, temporal types — dates are plain strings such as
`'2025-01-08'` — and `LOAD CSV`, which this engine now implements but which would tie a dump to
a file layout outside itself.

Because of that, importing a dump into another openCypher engine needs no rewriting, but
also gets no indexes: on a much larger dataset you would add engine-specific index DDL
outside these files.

## Adding a dataset

1. Create `datasets/NN-name/data.cypher` plus a `README.md` in the same shape as the
   existing ones.
2. Add rows to `queries.csv` with `dataset = NN-name`, each returning `nodes` and `rels`,
   with counts you derived rather than measured.
3. Run `./run-verification.sh --dataset NN-name`. The runner discovers datasets from the
   `dataset` column of the CSV, so there is nothing else to register.
