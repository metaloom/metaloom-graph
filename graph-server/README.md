# graph-server

The HTTP front end: a Cypher endpoint over a store, and a browser UI that draws what it returns.

```bash
java -jar target/graph-server-0.0.1-SNAPSHOT.jar /path/to/store 8080
java -jar target/graph-server-0.0.1-SNAPSHOT.jar --demo /tmp/demo 8080   # with a graph to look at
../tools/demo-server.sh                                                  # the same, from the reactor
mvn -pl graph-server exec:java -Dexec.args="--demo /tmp/demo 8080"
```

## Demo data

`--demo` fills an **empty** store with an air route network and then serves it; `--demo-size` chooses how much —
`tiny`, `small` (the default, 58 real airports), `medium`, `large`, or a number of airports. A store that already has
nodes is left alone and said so, so the flag is safe to leave in a start script. `--help` lists everything.

The domain is deliberate. Air routes need no explanation, so the demo teaches the query language rather than the
dataset — and the domain happens to contain every awkward shape the engine and the browser have to handle: a hub is
`:Airport:Hub` (two labels), a freight airline is a `` `Cargo Carrier` `` (a label needing quotes), `IN_CITY` is a
lightweight edge with no record, two airlines fly one pair (parallel relationships), one sightseeing flight returns to
where it started (a self-relationship), and a hub has forty times the degree of a spoke (a supernode).

Airport names, codes and coordinates are real and distances are computed from them, so `ORDER BY r.distanceKm` gives
sensible answers. **The airlines and every route are invented.** Past the real airports the generator invents those
too, rather than inventing facts about real places. It is seeded, so a size always produces the same graph — which is
what lets the browser suite name Heathrow and expect an answer.

The jar is shaded — engine, query language, Vert.x, Netty, logging and the UI bundle in one file. It
needs a JVM and nothing else. What the routes are and how a result becomes both a table and a graph:
[spec/CYPHER.md §8](../spec/CYPHER.md).

**Read-only, and unauthenticated.** The query language cannot write, so this cannot modify a store
however it is addressed. It can read all of one. Bind it to localhost unless something else is
providing access control.

## The UI

A React application in [src/main/frontend](src/main/frontend), bundled by Vite into
`target/classes/webroot` — which is what the server serves and what goes into the jar, so there is no
copy step and no way to ship a stale bundle.

```bash
cd src/main/frontend
npm run dev     # hot reload, proxying /api to a server already running on 8080
npm run build   # what the Maven build runs
npm test        # the model's rules: merging, and colour-by-name
npm run e2e     # Playwright against a real server over the demo store
```

`npm run dev` is the loop to use while changing the UI. The alternative — rebuild and reload against a
running server — has a trap: Vert.x copies classpath resources into `.vertx/file-cache-*` and serves
the copy, so a server started before the rebuild keeps serving the previous bundle.
`tools/demo-server.sh` starts the server with that cache off, because it is the development and test
loop; a plain `java -jar` does not.

## Building without npm

The UI is optional in the same way the Rust layer is. `-Dui.skip=true`, or no `npm` on the machine,
and the build skips it with a message; the jar then has no `webroot`, every API route still works, and
the index page says which build this is instead of returning a 404. Both paths are covered by
`GraphServerTest.testTheUiIsServed`.

## Tests

| | |
|---|---|
| `src/test/java/.../GraphServerTest` | The HTTP contract: both result shapes, the schema scan and its cache, the id-precision regression, and the two UI builds |
| `src/test/java/.../demo/DemoDataTest` | The generated graph: that it is connected the way it claims, that its distances match the world, and that it is the same graph every time |
| `src/test/java/.../GraphServerOptionsTest` | The command line, parsed as a value so a silently ignored flag fails a test rather than starting a server that does the wrong thing |
| `src/main/frontend/src/model/*.test.ts` | Run by `mvn test`. The rules that are logic rather than appearance |
| `src/main/frontend/e2e/` | Playwright. Opt-in: it needs a browser and starts a server |
