# Changelog

All notable changes to MetaLoom // Graph. The format follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and versions follow [Semantic Versioning](https://semver.org/) — with the caveat that a `0.x` pre-release promises
less than SemVer's `1.0`; see *Compatibility* below. How a release is cut: [spec/RELEASE.md](spec/RELEASE.md).

## [0.1.0-alpha.1] - Unreleased

The first release: an **alpha**, for trying the engine on real data and telling us what breaks. It is
durable, transactional and heavily tested, but nothing about its interfaces is frozen yet.

### What is in it

- **Storage engine** (`graph-core`) — an embedded graph store for Java 25 on the Foreign Function and Memory
  API. An element id decodes arithmetically into its file and byte offset, so a read by id costs no index
  probe. Nodes with multiple labels, relationships with or without properties, typed properties including
  blobs, lists and float vectors.
- **Durability** — a write-ahead log, fsynced before any data page is touched (`FSYNC_ON_COMMIT`, the default),
  and a single redo pass on recovery. `GROUP_COMMIT` and `ASYNC` trade a window of recent commits for throughput.
- **Transactions** — `begin` / `commit` / `rollback` and auto-commit; MVCC snapshot isolation for readers
  and writers, optional `SERIALIZABLE` validation; concurrent writers validated at commit. A statement that
  fails part way inside a transaction makes it rollback-only rather than committing half of itself.
- **Indexes** — label and property B+trees, composite indexes, unique constraints, and an HNSW vector index.
- **Space reclamation** — `reclaim()` returns empty pages to the file system without invalidating ids;
  `compact()` relocates records and rewrites every internal reference.
- **Cypher** (`graph-cypher`) — an openCypher subset that reads and writes: `MATCH` with variable-length paths
  and shortest paths, `WHERE`, `WITH`, `UNWIND`, `CASE`, aggregation, `ORDER BY` / `SKIP` / `LIMIT`, parameters,
  `CREATE` / `MERGE` / `SET` / `REMOVE` / `DELETE`, `LOAD CSV`, vector queries. [spec/CYPHER.md](spec/CYPHER.md)
  lists exactly what is supported and every deliberate deviation.
- **Server** (`graph-server`) — one runnable jar with the HTTP API and browser UI (8080), a streaming binary
  protocol with an optional TLS listener (8081 / 8084), Prometheus metrics with `/health` and `/ready` (8082),
  and an MCP port for AI agents (8083, off by default). Several databases under one root.
- **Security** — authentication on by default, per-database roles and grants, writes refused unless the
  server is started with `--allow-write`.
- **Java client** (`graph-client`) — streams rows with backpressure and depends on neither the engine nor
  the query language.
- **Backup** (`graph-cli`) — an online logical dump and an offline restore, from a CLI shipped inside the images.
- **Native layer** — an optional Rust library for io_uring reads, hole punching and fast codecs, with a
  pure-Java fallback for every capability.
- **Deployment** — container images (`metaloom/graph-server:0.1.0-alpha.1`, and a GraalVM `-native`
  variant), and a Helm chart (`0.1.0-alpha.1`).

### Compatibility

This is an alpha, so the following may still change without a deprecation period. Each has a process in
place from this release on, so that a change is a deliberate act rather than an accident:

- **The Java API** of `graph-core` (`io.metaloom.graph.core.api`), `graph-cypher`, `graph-protocol` and
  `graph-client` is **locked by a build-time test** against committed baselines. During the alpha a change
  is allowed once it has been accepted explicitly; from `1.0` on, only additions will be.
  See [spec/API_LOCK.md](spec/API_LOCK.md).
- `GraphStore`, `Transaction`, `ReadTransaction` and `NeighbourCursor` are **sealed**: they are implemented by
  the engine only, which is what lets later versions add methods to them without breaking callers. To decorate a
  store (counting, tracing, metrics), extend `ForwardingGraphStore`, which forwards every method and gains each
  new one in the same change.
- **The on-disk format** is store format version 4, recorded in each store's manifest. From this release on,
  every change to it bumps that version and ships a migration step and a golden test fixture. A build never
  upgrades a store on open: an older store is refused with the command that migrates it
  (`metaloom-graph-cli database migrate <db> --path=<root>`), which runs offline, copy-on-write, verifies the
  result with `verify(FULL)` and keeps the original beside it. `database info` prints a store's version without
  opening it. See [spec/MIGRATION.md](spec/MIGRATION.md). The logical dump (`database backup` / `restore`)
  still works across any two versions, at the cost of new element ids.
- **The binary protocol** is version 4, negotiated at connect; a mismatch fails cleanly before any
  credential is sent.

### Platform

- JDK 25. Linux on x86-64 is the platform that is built and tested. The jar carries the native library for
  `linux-x86_64` only; anywhere else the engine runs on its pure-Java path.
- The container images are `linux/amd64`.

### Known limitations

- No `OPTIONAL MATCH`, list comprehensions, map projections or `EXISTS {}` subqueries — refused by name.
- No TLS on the HTTP and MCP ports: put them behind a proxy, or a password crosses in cleartext.
- No incremental or physical backup and no point-in-time recovery; the dump is a logical snapshot, and
  element ids are new after a restore.
- The Cypher executor materialises each clause, so a query's peak server memory is its widest
  intermediate result. Streaming bounds the client's memory, not the server's.
- One writer process per store (a lock file), so the Helm chart runs a single replica.

### Known issues

- Some HTTP routes (`/api/stats`, `/api/schema`, `/api/indexes`, backup) can perform the first open of a
  store on the event loop, stalling other requests while it opens.
- MCP queries are not counted as in-flight work at shutdown, so a store can close under one; the query
  then fails with an exception rather than completing.
- Retiring an empty segment does not exclude readers; a reader holding a stale id into that segment
  would get an `IllegalStateException` rather than a stale-id error. Not reproduced.
- Under heavy contention on one record, `inTransaction` can exhaust its retries. `inStatement` and
  single-statement writes cannot.
