# DATABASES.md — several stores under one root

One server, many databases. A database is a {@code GraphStore} and nothing more; what this adds is the
part that is easy to get wrong — turning a name that arrived over a network into a directory, opening
each store at most once, and closing them together.

> **Do not put here**: the storage format ([FILE_FORMAT.md](FILE_FORMAT.md)), the query language
> ([CYPHER.md](CYPHER.md)), the frame layout ([WIRE.md](WIRE.md)), or operational sizing
> ([OPERATIONS.md](OPERATIONS.md)).

---

## 0. Status

| | |
|---|---|
| **Layout** | `<root>/<name>/` — one directory per database, each a complete store |
| **Registry** | `GraphDatabases` in graph-core |
| **Selection, HTTP** | `?database=<name>` on `/api/query`, `/api/schema`, `/api/stats` |
| **Selection, binary** | **Per connection**, in the `HELLO` frame. Protocol version 2 |
| **Lifecycle** | `GET /api/databases`, `PUT /api/databases/{name}`, `DELETE /api/databases/{name}` |
| **Dropping** | Off unless the server was started with `--allow-drop` |
| **Default** | `default`, or whatever `--database=` names. Created at startup if absent |

---

## 1. The layout

```
/data/graphs/            <- the root, what the server is pointed at
  default/               <- a database
    MANIFEST
    seg/  wal/  dict/  LOCK
  sales/                 <- another, entirely independent
    MANIFEST
    seg/  wal/  dict/  LOCK
```

Nothing is shared between them: separate WAL, separate segments, separate dictionaries, separate
`LOCK`. Two databases in one process are two `GraphStore` instances, and the engine did not have to
change to support that — the `LOCK` file was already per store.

**A directory that is itself a store is refused**, with the commands to move it:

```
/data/mygraph is a store directory, not a root of databases. Each database now lives in its own
subdirectory. Move it into one:
  mkdir /data/mygraph.tmp
  mv /data/mygraph /data/mygraph.tmp/default
  mv /data/mygraph.tmp /data/mygraph
```

Refused rather than adopted or migrated. Adopting the root as the default database would leave one
database without a directory of its own — the layout would be inconsistent exactly where it matters —
and moving somebody's data on their behalf, on a disk that might be full, is not a decision a startup
path gets to make. `GraphDatabases.isStoreDirectory` decides by the presence of `MANIFEST`, which is
the one file the engine writes first and refuses to open without.

---

## 2. The name is the security boundary

A database name arrives from a port with **no authentication** and is about to become a path
component. `GraphDatabases.validate` is therefore an **allow-list**: letters, digits, `-` and `_`, at
most 64 characters, not starting with `-`.

A deny-list would have to anticipate `..`, `/`, `\`, a leading `/`, a bare `.`, NUL, a Windows device
name, percent-encoding and whatever the filesystem does with Unicode normalisation. An allow-list has
to anticipate nothing. `GraphDatabasesTest` fires all of those at it, and asserts afterwards that
nothing was created anywhere.

Validation happens **before** the name is resolved against the root, in every entry point —
`database`, `create`, `drop`, `exists`, `pathOf` — rather than once at a boundary somebody could route
around later.

| Failure | HTTP | Wire |
|---|---|---|
| Not a legal name | **400** | `NO_SUCH_DATABASE` |
| A legal name that is not there | **404** | `NO_SUCH_DATABASE` |

The HTTP split is worth keeping: 400 says the name is not a name, 404 says the database is not there,
and a UI reacts differently — one is a typo in a field, the other is a database somebody dropped. The
wire folds them into one code because a client that asked for a database it cannot have takes the same
next step either way.

---

## 3. Lifecycle

**Stores open lazily and stay open.** Opening one maps every segment file and takes its `LOCK`, so a
root of fifty databases with one in use costs one open store, not fifty. `GET /api/databases` reports
counts only for databases that are already open, for the same reason — listing must not map fifty sets
of segment files.

**Nothing is created implicitly.** Asking for a database that does not exist is an error, not a
create. The name reaches this code from an unauthenticated port, and a typo that quietly makes a
database is a typo that fills a disk.

**Dropping is opt-in.** `DELETE /api/databases/{name}` returns 403 unless the server was started with
`--allow-drop`, and the response says so. It is the only route in the server that destroys data — the
query language beside it cannot delete a single node — and it sits on a port with no authentication.
The UI asks the operator to type the database's name, not to click OK.

**The last database cannot be dropped.** A root with none cannot be reopened without one being made,
and a server holding no database at all has no useful behaviour.

A drop closes the store *before* unlinking the directory. Unlinking a mapped file leaves the mapping
valid and the store convinced it still has somewhere to write, which is a stranger failure than a
refusal.

---

## 4. Selection

### HTTP: a query parameter

```
POST /api/query?database=sales
GET  /api/schema?database=sales
GET  /api/stats?database=sales
```

A parameter rather than a path segment, because scoping a query to a database is a property of the
query and not a different resource. `/api/databases` is the exception — there the database genuinely
is the resource, so it is a plural collection with the name in the path.

Absent or empty means the server's default. **Schema and stats are scoped too**: a picker that
switched the query but not the sidebar would show one database's labels beside another's rows.

### Binary: per connection, at the handshake

`HELLO` carries the name; `WELCOME` echoes what the server resolved.

```java
try (GraphClient client = GraphClient.connect("localhost", 8081, "sales");
     RowStream rows = client.query("MATCH (n) RETURN n")) {
    ...
}
client.database();   // "sales" — what the server resolved, not what was asked for
```

Fixed for the life of the connection, so a stream never has to say which database it belongs to and
the server resolves the store once instead of per query. Querying two databases at once means two
connections.

That is not only simplicity. **A read view belongs to one store**, and a connection that could change
store mid-flight would be a connection whose open views outlive their subject.

`GraphClient.DEFAULT_DATABASE` is the empty string, meaning "whichever database the server calls its
default". A client that hard-coded `"default"` would break the moment a server was started with
`--database=sales`, and it would break by querying the wrong data rather than by failing.

**This is protocol version 2.** A version 1 client meets a clean `UNSUPPORTED_VERSION` naming both
numbers, which is what the handshake is for. Nothing had shipped on version 1.

---

## 5. The UI

A picker in the top bar, showing each database with its node count and which is the default, plus
**+ New** and — only when the server reports `allowDrop` — **Drop**.

**Switching clears the canvas.** Not caution: an element id is only unique within a store, so two
databases can legitimately hand back the same id for different nodes. Merging their graphs would draw
one circle for two records with nothing on screen to say so.

The UI validates a new name with the same rule the server enforces, before sending it. The server
validates too — it has to, since it is reachable without this page — but a caller who is told at the
keystroke does not have to send a request to find out that a space is not allowed.

The initial database comes from `GET /api/databases`, never from a hard-coded `"default"`.

---

## 6. Operational notes

- `--database=<name>` sets the default and creates it at startup if it is not there.
- `--demo` seeds **the default database**, and only if it is empty.
- The retention watchdog on the binary port checks **per database**, not summed: retention is a
  property of one store's version chain, and a reader on a busy database must not be abandoned because
  a different one is holding pages ([WIRE.md §4](WIRE.md)).
- Each open database costs its own mapped segments, WAL and `LOCK`. Sizing is per database, and the
  wire server's memory bound ([WIRE.md §5](WIRE.md)) is per server.
- There is **no cross-database query**. A query runs against one store, and the engine has no notion
  of a second one.
- There is **no per-database authentication**, because there is no authentication at all
  ([OPERATIONS.md §9.1](OPERATIONS.md)). Anything that can reach the port can reach every database
  under the root.

---

## 7. Tests

| | |
|---|---|
| `GraphDatabasesTest` | The registry: layout, separation proved by writing to one and reading the other, lazy open, drop, the legacy refusal, and the traversal cases — which are the security test for the whole feature |
| `DatabaseRoutesTest` | The HTTP surface: listing, `?database=` genuinely reaching a different store, 400 vs 404, create, drop, and drop refused without the flag |
| `WireServerTest` | Two connections to two databases from one process seeing different data; an unknown database refused at the handshake; a hostile name refused before it becomes a path |
| `GraphServerOptionsTest` | `--database=` validated at parse time, `--allow-drop` off by default |

---

_Git HEAD revision: `f9cc6653`_
_Last updated: 2026-08-15 (written against protocol version 2)_
