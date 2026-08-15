# graph-server

The HTTP front end: a Cypher endpoint over a store, and a browser UI that draws what it returns.

```bash
java -jar target/graph-server-0.0.1-SNAPSHOT.jar /path/to/store 8080
../tools/demo-server.sh                    # a seeded demo graph, if you have no store yet
mvn -pl graph-server exec:java -Dexec.args="/path/to/store 8080"
```

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
the copy, so a server started before the rebuild keeps serving the previous bundle. `DemoServer`
disables that cache; a plain `GraphServer` does not.

## Building without npm

The UI is optional in the same way the Rust layer is. `-Dui.skip=true`, or no `npm` on the machine,
and the build skips it with a message; the jar then has no `webroot`, every API route still works, and
the index page says which build this is instead of returning a 404. Both paths are covered by
`GraphServerTest.testTheUiIsServed`.

## Tests

| | |
|---|---|
| `src/test/java/.../GraphServerTest` | The HTTP contract: both result shapes, the schema scan and its cache, the id-precision regression, and the two UI builds |
| `src/test/java/.../DemoServer` | Not a test — the seeded graph the browser suite drives, and the shortest way to see the UI |
| `src/main/frontend/src/model/*.test.ts` | Run by `mvn test`. The rules that are logic rather than appearance |
| `src/main/frontend/e2e/` | Playwright. Opt-in: it needs a browser and starts a server |
