# AUTH.md — identity, and what an identity is allowed to do

This is the specification of **who** a request is from and **what** they may do: the users, roles and
grants in `<root>/.auth/`, how each front end establishes a principal, and where the check happens.

Until this existed, [OPERATIONS.md §10](OPERATIONS.md) listed "no authentication on any port" as the
largest remaining gap, and [DATABASES.md §6](DATABASES.md) named the consequence exactly: "there is no
way to open one database for writing and keep another read-only; that would need an identity to attach
the distinction to, and there is none." This is that identity.

> **Do not put here**: the frame layout and the handshake's mechanics ([WIRE.md](WIRE.md)), the
> operational posture and the port table ([OPERATIONS.md §9](OPERATIONS.md)), the root directory
> layout and the name allow-list ([DATABASES.md](DATABASES.md)), the MCP transports
> ([MCP.md](MCP.md)), or the meter catalogue ([METRICS.md](METRICS.md)).

---

## 0. Status

| | |
|---|---|
| **Identity store** | `<root>/.auth/AUTH` — a binary file, CRC32C at the tail, rewritten whole under a lock |
| **Modules** | `graph-core` (`core.api` for the model, `core.auth` for the codec and the KDF), `graph-server` (enforcement, sessions), `graph-cli` (`auth` commands), `graph-cypher` (the AST mapping) |
| **Ports covered** | HTTP 8080, binary 8081, MCP 8083. **Monitoring 8082 is deliberately exempt** — §7 |
| **Default** | **On.** `--no-auth` / `GRAPH_AUTH_ENABLED=false` turns it off. The programmatic constructors default to off |
| **Hashing** | PBKDF2-HMAC-SHA512, 210,000 iterations, 16-byte salt, 32-byte key. No new dependency |
| **Scope of a grant** | `(privilege, database)`, with `*` for every database |
| **Transport security** | **On the binary port, optionally** — `--wire-tls-cert`, a second listener, off by default ([WIRE.md §7.1](WIRE.md)). On HTTP and MCP, **none**: credentials cross in cleartext unless a proxy terminates TLS in front of them. §9 |

---

## 1. The model

Three nouns and one verb.

- A **user** has a name, a password hash, an enabled flag, any number of **roles**, and any number of
  direct grants.
- A **role** has a name and any number of grants. Roles exist because grants are given to jobs rather
  than to people, and because revoking a job is one edit rather than one per person.
- A **grant** is a `Privilege` and a database name, or `*`. `Grant.permits(privilege, database)` is
  the whole of the matching rule.
- A **principal** is what a request carries: a name, whether it was authenticated, and
  `may(privilege, database)`.

A user's effective grants are their own plus every role's. There is no deny rule and no precedence
order — a grant is additive, and the absence of one is the refusal. A deny rule would need an
ordering between it and the grants, and every ordering anyone picks surprises somebody.

### 1.1 The privileges

Fifteen, in `io.metaloom.graph.core.api.Privilege`. They span three surfaces rather than one, which is
the first thing to know: some name a Cypher clause, some an HTTP route, some an MCP tool.

| Privilege | Write | Gates |
|---|---|---|
| `MATCH` | | `MATCH`, `UNWIND`, `WITH`, `RETURN`, `CALL` |
| `CREATE` | yes | `CREATE` |
| `MERGE` | yes | `MERGE` (which also requires `MATCH`) |
| `SET` | yes | `SET`, including `SET n:Label` |
| `REMOVE` | yes | `REMOVE`, including `REMOVE n:Label` |
| `DELETE` | yes | `DELETE`, `DETACH DELETE` |
| `LOAD_CSV` | | `LOAD CSV` |
| `CREATE_INDEX` | yes | `POST /api/indexes`, MCP `create_index` |
| `DROP_INDEX` | yes | `DELETE /api/indexes/{label}/{key}`, MCP `drop_index` |
| `INTROSPECT` | | `/api/schema`, `/api/stats`, `/api/indexes`, and the MCP schema, stats, store and index-listing tools |
| `CREATE_DATABASE` | yes | `PUT /api/databases/{name}` |
| `DROP_DATABASE` | yes | `DELETE /api/databases/{name}` |
| `BACKUP` | | `GET /api/databases/{name}/backup` |
| `MANAGE_USERS` | yes | Create (with no roles), drop, enable, disable a user; set another user's password — except one who holds an administrative privilege the caller lacks. **Global only** |
| `MANAGE_ROLES` | yes | Create and drop a role; grant and revoke. **Global only** |

Four of these repay explanation.

**`MERGE` has its own name** because "may upsert, may not delete" is a real loader role and is not
expressible as a combination of the others. It requires `MATCH` as well, because it matches before it
creates: a principal who could `MERGE` without reading would learn whether a pattern existed by
watching whether one appeared.

**`CREATE_INDEX` and `DROP_INDEX` are separate**, though creating and dropping look symmetric.
Dropping an index costs queries time. Dropping a **unique constraint** — the same declaration with a
flag, see [INDEX.md](INDEX.md) — lets the next write put two live elements on one value. One is a
cost, the other is a correctness event.

**`MANAGE_USERS` does not imply `MANAGE_ROLES`.** This is the most important split in the list. "May
reset a forgotten password" is a helpdesk task; "may grant myself `MERGE ON *`" is privilege
escalation. Folding them together would mean everyone trusted with the first could silently do the
second.

The split only holds if no `MANAGE_USERS` route reaches the second by another door, and two did:

- **Creating a user in a role is granting.** `POST /api/users` with a non-empty `roles` also needs
  `MANAGE_ROLES`; without it the helpdesk could create a second account in `admin` and log in as it.
  A user with no roles is still the helpdesk's to create.
- **Resetting a password is becoming that user.** `POST /api/users/{name}/password` for somebody else
  is refused, naming the privilege, when the target holds an administrative (global-only) privilege
  the caller does not — the helpdesk resets a reader's password, not the admin's. Disabled users
  count, because re-enabling one is another `MANAGE_USERS` call. Resetting a reader's password does
  hand the helpdesk that reader's access for a moment; that is the job the privilege exists for.

`AuthorizationTest.testManagingUsersDoesNotAllowCreatingAUserInARole` and
`testManagingUsersDoesNotAllowTakingOverAnAdministrator` pin both.

**`INTROSPECT` is separate from `MATCH`** because either without the other is a real role: a
monitoring principal that may see sizes and not contents, and a query principal that may read data
without being handed a map of the whole store — `/api/schema` is a full scan of every label and type
([CYPHER.md §8.1](CYPHER.md)).

Two privileges are deliberately **absent**. There is no `LIST_DATABASES`: `GET /api/databases` is
*filtered* to the databases a principal holds any privilege on rather than refused, which is better
behaviour and one fewer name. There is no `RESTORE`, because there is no restore route — the CLI
restores offline under the store `LOCK` ([BACKUP.md §6](BACKUP.md)). The name is reserved.

### 1.2 Names, not numbers

A grant is stored on disk and sent on the wire as the constant's **name**. There is no second registry
of codes to keep in step, a refusal explains itself without a lookup table, and the file holds tens of
entries so the size difference is irrelevant.

**An unknown privilege name is refused wherever it is read, never skipped.** A build that half
understood a file and then rewrote it would silently drop a grant, and losing authority quietly is as
bad as gaining it.

---

## 2. What a query requires

`Ast.Query.requiredPrivileges()` — the third policy question answered from the tree, alongside
`isWrite()` and `readsFiles()`, and for their reason: a refusal decided there costs no worker thread,
no transaction and no read view.

It is an **exhaustive `switch` over the sealed `Ast.Clause` with no `default`**, where its two
neighbours are `instanceof` chains. That is deliberate. Adding a twelfth clause is a compile error
until its privilege is declared, and no test asserts exhaustiveness because a test could only check the
clauses it had been told about — which is the set that is never the problem. `isWrite()` answering
"no" for an unclassified clause is a wrong answer about which transaction to open; this answering
"nothing required" would be a clause that authorises itself.

`UNWIND`, `WITH` and `RETURN` map to `MATCH` rather than to nothing. They authorise no access of their
own, but a query made only of them still occupies a worker thread and a deadline, so `MATCH` is the
floor for "may issue a read query at all".

One consequence is surprising and correct: `LOAD CSV … CREATE` requires `{LOAD_CSV, CREATE}` and **no**
`MATCH`, while `LOAD CSV … RETURN row` requires `{LOAD_CSV, MATCH}`. The importing form reads nothing
out of the graph; the previewing form hands rows back to the caller. A loader can run the import and
not the preview, which is the right way round.

**Enforcement is per front end, not in the engine or the query language.** That is the same line the
capability flags already draw — [CYPHER.md §5.1](CYPHER.md): "Embedded Java callers are unaffected: the
flag is a property of the servers, not of the language." An embedded caller holding a `GraphStore`
already has the directory, and [OPERATIONS.md §9](OPERATIONS.md) says anything that can open the
directory has full access.

---

## 3. The identity file

```
<root>/.auth/            mode 0700
        AUTH             users, roles and grants
        AUTH.tmp         a write in progress; deleted on open
        LOCK             empty; locked only across a read-modify-write
```

### 3.1 Why `.auth` cannot collide with a database

`GraphDatabases.validate` is an allow-list of letters, digits, `-` and `_`, refusing a leading `-`
([DATABASES.md §2](DATABASES.md)). `.` is not in the set, so `.auth` is unreachable as a database name
from every entry point. Independently, `GraphDatabases.names()` lists only directories holding a
`MANIFEST`, which `.auth/` never has. Two mechanisms, neither of which had to change.

A root holding only `.auth/` is therefore still "empty" as far as `open()` is concerned, and still
gets a `default` database created in it. That is correct, and there is a test asserting it so that
nobody later "fixes" `names()` to count directories.

### 3.2 The bytes

```
magic     "MLGAUTH\0"                     8 bytes, so the body starts 8-aligned
u16       formatVersion = 1
u16       reserved (0)
u32       flags (0)
u64       generation                      +1 on every successful write
varInt    roleCount
  per role:  string name
             varInt grantCount
               per grant: string privilege, string database
varInt    userCount
  per user:  string name
             u8     userFlags              bit 0 = disabled
             u64    createdAtEpochMillis
             u64    passwordChangedAtEpochMillis
             string kdfId                  "pbkdf2-sha512"
             u32    iterations
             varInt saltLength, salt bytes
             varInt hashLength, hash bytes
             varInt roleCount,  string x n role names
             varInt grantCount             direct grants, same shape as a role's
u32       crc32c over everything from the magic to here
```

**KDF parameters are per user, not per file**, so raising the cost rehashes a user on their next
successful login rather than rewriting everyone at once.

### 3.3 Binary rather than text

Every other format in this project is binary for reasons that do not apply here — this file is not
hot, not mapped and not id-addressed — so the choice was made on other grounds:

1. A text parser has *more* undefined corners than a length-prefixed one, and they are corners that
   matter for a **name compared for equality**: encoding, BOM, line endings, quoting, trailing
   whitespace, Unicode normalisation. This project's doctrine is allow-list over deny-list, and a
   length-prefixed record has no quoting rule to get wrong.
2. One hostile-input idiom for the whole repository: `HostileInputTest`,
   `WireDecoderHostileInputTest` and `DumpHostileInputTest` all assert the same shape over `byte[]`.
3. A base64 hash in a text file invites hand-editing, and a hand-edited credential file is a
   silent-corruption path with a security outcome.
4. It is the smaller change: `DumpFormat` gives the shape, and `core.util.VarInt` and `core.util.Crc`
   are already in the module.

Readability is paid for explicitly, by `metaloom-graph-cli auth list`.

### 3.4 Hostile input

`AuthCodec.decode(byte[])` is a pure function with no filesystem, written over a `byte[]` for the
reason `WireValueCodec` is: so it can be fuzzed without a transport.

- **Every count is checked against the bytes actually remaining before anything is allocated.** Seven
  defects of exactly this kind were found in this project's storage parsers and three more in the wire
  decoder, and every one was a length field that was believed.
- Caps: 1 MiB file, 4096 users, 256 roles, 512 grants per subject, 64-byte salt, 64-byte hash,
  iterations in `1..10_000_000`.
- **User and role names go through `GraphDatabases.validate`** — the same allow-list a database name
  faces. One validator, so a name here can never become a path component or an argv-looking `-r`.
- A duplicate user or role name is refused; a user naming a role that does not exist is refused, not
  ignored. The CRC is verified before any field is acted on. Nothing recurses.

### 3.5 Writing, and two processes that both want to

`AuthStore.mutate` takes `FileChannel.tryLock()` on `.auth/LOCK` — **short-lived**, unlike a store's
`LOCK` which is held for the store's lifetime — re-reads the file inside the lock, compares
`generation` against the one the edit was based on, refuses on a mismatch, then writes `AUTH.tmp`,
forces it, `ATOMIC_MOVE`s it over `AUTH` and fsyncs the directory. A crash leaves the old file intact;
`open()` deletes a stray `AUTH.tmp`.

Neither the server nor the CLI needs the store `LOCK` for any of this, which is what makes
`auth reset-admin` work against a root a live server has open. The server's side of that is
`AuthStore.reloadIfChanged()` — a stat of size and mtime, then `generation` — called **on the login
path only**, never on a session lookup. So "reset the password while the server runs, then log in"
works, which is the first thing an operator will try.

---

## 4. Passwords

PBKDF2-HMAC-SHA512, **210,000 iterations** (the OWASP figure for SHA-512), a 16-byte `SecureRandom`
salt and a 256-bit derived key. From the JDK, so no dependency was added.

- Compared with `MessageDigest.isEqual`.
- **An unknown user costs the same as a known one** — verification runs against a fixed dummy hash
  before failing — or the login endpoint is a user enumerator.
- Canonical text form, which is what `auth list` prints:
  `pbkdf2-sha512$210000$<base64url salt>$<base64url hash>`.
- **An empty password is refused.** `PasswordHash.of` throws for one, which every path that sets a
  password goes through — `AuthStore.createUser`, `setPassword`, `bootstrap`, `resetAdmin`, and so the
  HTTP routes (400), the CLI and `GRAPH_INITIAL_PASSWORD`. `verify` answers false for an empty attempt
  *after* the derivation, so it costs what any wrong password costs, and even against a hash an older
  build made from `""`.

### 4.1 The cost is also an amplifier

210,000 iterations is on the order of 100 ms of CPU, and both `POST /api/login` and the wire `AUTH`
frame arrive on an event loop. Three consequences, all of them load-bearing:

- Verification runs on `executeBlocking`, never inline on the loop.
- A semaphore of `availableProcessors()` bounds concurrent verifications; past it HTTP answers 503 and
  the wire answers `OVERLOADED`. **One semaphore, in `AuthPolicy`**, shared by both ports, because the
  CPU being rationed is the process's. The wire's verification runs on Vert.x's default worker pool,
  not its query pool, so a burst of handshakes does not queue queries behind it.
- The failure throttle rejects **before** the derivation runs, per user name and per source address.
  A throttle that ran the KDF first would be the amplifier rather than the defence.

### 4.2 GraalVM

`SecretKeyFactory.getInstance` is a JCA service lookup that reaches provider classes reflectively, and
a missing registration fails at **run** time under `--no-fallback`, not at build time — so a green
`mvn verify` proves nothing about it. The registrations live in **graph-core's**
`reachability-metadata.json`, which is what both native binaries inherit; `tools/native-image.sh`
already relies on that for the CLI. See [GRAALVM.md](GRAALVM.md).

---

## 5. Grants and the capability flags

**A request needs the privilege AND the server-wide flag. The flags can only subtract.**

The order at every gate is **flag first**, then privilege: the flag is a property of the deployment,
costs nothing and needs no identity.

1. **Turning authentication on must never widen what a server can do.** If a grant could override
   `--allow-write`, then enabling auth on a read-only server would enable writes. That is the one
   failure mode this ordering exists to make impossible.
2. **The images and the chart stay correct as written.** `GRAPH_ALLOW_WRITE=false` means this
   container cannot write, whoever logs in, and [OPERATIONS.md §9.1](OPERATIONS.md)'s blast-radius
   statement survives unchanged.
3. **They answer different questions, asked by different people at different times.** Whether a
   *deployment* may do X at all is an operator's start-time decision, auditable in a manifest and
   immutable at runtime. Whether a *user* may do X here is an admin's runtime decision in `.auth/`.
   The project already has this two-tier shape: `--allow-load-csv` plus `--import-dir` is a
   deployment ceiling under which a query-level policy object, `CsvSource`, operates.

The two refusals are **distinguishable**, because their remedies are held by different people:

| Refused by | HTTP | Wire | The message is for |
|---|---|---|---|
| the flag | 403, `readonly` / `loadcsv` — wording unchanged | `READ_ONLY` | an operator: start it with `--allow-write` |
| the grant | 403, `forbidden` | `UNAUTHORIZED` | an admin: your roles do not grant MERGE on 'sales' |

"I granted MERGE and it is still refused" would otherwise be the commonest support question.
Enabling authentication does not change any flag's default; they all stay off.

---

## 6. Establishing a principal

### 6.1 HTTP

`POST /api/login` takes `{user, password}` and returns an opaque token: 32 `SecureRandom` bytes as
base64url. It is set as a cookie — `HttpOnly; SameSite=Strict; Path=/`, and `Secure` **only** when the
request arrived over TLS or carried `X-Forwarded-Proto: https`, because this server terminates no TLS
**on the HTTP port** — that port expects a proxy in front of it, and an unconditional `Secure` would
make the cookie unusable on localhost. The binary port's own TLS listener ([WIRE.md §7.1](WIRE.md)) is
unrelated: no cookie crosses it, and no HTTP does. The token is also in the
response body, for `Authorization: Bearer` callers.

The session table is keyed by **SHA-256 of the token**, not the token, so a heap dump does not hand out
live sessions. Idle timeout 30 minutes, absolute cap 12 hours, table bounded — an unbounded map behind
a password gate is still an unbounded map.

**Grants are resolved per request, not frozen into the session**, and the enabled flag is re-checked
per request, so a revoke or a disable takes effect immediately rather than at the next login. Freezing
at login is the more common bug. An edit made by **another process** — the CLI's `auth` commands
against a running server — is picked up too: resolving a session re-stats `.auth/AUTH` at most once a
second and re-reads it when it changed, where before only a login did, so a user disabled on disk kept
their session until its idle timeout.

**A query's privileges are checked before its database is resolved.** The other order answered 404 for
a missing name and 403 for a present one, which listed the databases to anybody with a login; now a
caller who holds nothing there gets 403 either way, and only somebody who may look learns that a name
is missing. The store is resolved on the worker, not the event loop.

Three routes sit **outside** the filter: `POST /api/login`, `POST /api/logout` and `GET /api/whoami`.
`whoami` must answer without a session, because it is how the UI discovers whether to render a login
page at all. **So does the static bundle** — the filter is mounted on `/api/*` only, or the login page
could not load.

CSRF rests on `SameSite=Strict` **plus** every `POST` under `/api/` requiring
`Content-Type: application/json` (parameters such as `charset` allowed), which an HTML form cannot
produce cross-origin without a preflight; anything else is 415 before the body is read. The second
half is the one that matters on a `--no-auth --allow-write` server, where there is no cookie for
`SameSite` to withhold and a `text/plain` form body can be made to be valid JSON. Only `POST`, because
a cross-origin `PUT` or `DELETE` always needs a preflight; `POST /api/logout` is exempt, carrying no
body. The UI sends `application/json` on every `POST` with a body. Until this check existed the
sentence above was a claim, not a property.

### 6.2 The binary port

Protocol version 4 and two new frames; the mechanics are in [WIRE.md](WIRE.md). The property worth
repeating here is why credentials are not in `HELLO`: **a password is only ever sent to a server that
asked for it.** A client holding credentials against a server with authentication off completes the
handshake without ever transmitting them.

Three further properties, mechanics in [WIRE.md §1](WIRE.md): the database named in `HELLO` is resolved
only **after** `AUTH`, so an unauthenticated peer cannot learn which names exist; the grants are
**re-resolved at every query**, as HTTP's are per request, and a user disabled or dropped meanwhile has
the connection closed; and the verification shares the HTTP login's bound (§4.1).

### 6.3 MCP

`Authorization: Bearer` on all three transports. The websocket cannot decline an upgrade — Vert.x 5
removed `accept()`/`reject()` from `ServerWebSocket` — so an unauthenticated `/mcp/ws` is closed with
1008, exactly as the wrong-path branch already does. The session is **re-resolved at every message**
on the websocket, as it is at every POST on the SSE transport, and a message arriving after the session
ended — logout, timeout, a disable or a drop — closes the socket with 1008. Resolving once at the
upgrade let a disabled user's agent keep driving the port for as long as its socket stayed up.

Every store tool is checked against the database **its `database` argument names**, not the server's
default, and `graph_list_databases` lists only what the caller holds something on — the same filter as
`GET /api/databases` ([MCP.md §5](MCP.md)).

The write gate's two enforcement points ([MCP.md §5](MCP.md)) become privilege checks and keep their
reasoning: `tools/list` omits what the principal cannot use, and `tools/call` re-checks before
dispatch, because listing without dispatch-checking trusts the client's memory and dispatch-checking
without listing wastes the model's time.

---

## 7. The monitoring port is exempt, deliberately

8082 answers `/metrics`, `/health` and `/ready` with no credential. A kubelet probe and a Prometheus
scrape do not carry one, and a probe that fails because a secret was misconfigured restarts the pod —
turning an authentication mistake into a crash loop.

That is not a reason to publish it. [METRICS.md](METRICS.md) already says a scrape carries database
names, node counts and store sizes, and that guidance is unchanged: bound it with a NetworkPolicy or
do not expose it.

---

## 8. Authentication disabled

`--no-auth` / `GRAPH_AUTH_ENABLED=false`. Every request then carries `Principal.ANONYMOUS`, whose
`may(...)` returns true.

A null object rather than a null check, because `may(...)` is then the same call at every enforcement
site and a route added later cannot forget a check that was never written as a branch. The project
already prefers this shape: `CsvSource.DENY`, `GraphMetrics.NOOP`.

The asymmetry with `CsvSource.DENY` is deliberate. The safe default for *a policy the caller forgot to
set* is refusal. The safe default for *authentication the operator switched off* is permission,
because otherwise `--no-auth` would mean "nothing works". What makes that safe is that `ANONYMOUS` is
producible only on the auth-disabled path, and that `may(...)` returning true **does not reach the
capability flags**, which are checked first and separately — so a `--no-auth` server behaves exactly
as it did before any of this existed.

`ANONYMOUS` reports **no** grants rather than every grant: a UI rendering "you have been granted
DROP_DATABASE" to somebody nobody granted anything to would be a lie of a particular kind, and
`authenticated()` being false already tells a caller which world it is in.

`POST /api/login` on such a server answers 400 rather than 404, so a client can tell "wrong password"
from "this server has no accounts".

---

## 9. What this does not cover

Stated plainly, because a security document that implies capabilities the system lacks is worse than
none.

- **No transport security on the HTTP or MCP ports.** A password crosses those in cleartext, so it is
  exposed to anything on the path. Put a proxy in front of them, or keep them on loopback. The binary
  port can encrypt itself — [WIRE.md §7.1](WIRE.md) — and that is where the encryption landed first
  because it is the port no ingress can terminate for you.
- **No client-certificate authentication.** The TLS listener does not ask for one and would not look at
  it; a principal comes from the `AUTH` frame and nowhere else. `AUTH-8`.
- **No certificate rotation without a restart.** The PEM is read once at bind.
- **No audit log.** There is no record of who ran what. The slow query log records queries, not
  principals.
- **No external identity provider.** No OIDC, no LDAP, no mTLS. One mechanism, one password.
- **No authorization below a database.** No per-label, per-property or per-row rule. That would have
  to be enforced inside `GraphAccess`, on every element a query touches, rather than once before it
  runs.
- **No encryption at rest**, for the identity file or anything else. The hashes are hashes; everything
  else in it is plaintext.
- **Sessions are per process.** A second replica does not honour a token the first issued, and a
  restart logs everyone out.
- **Embedded callers are unaffected.** Anything that can open the directory still has full access.

Deferred work items are in [TASKS.md](TASKS.md) as `AUTH-1` to `AUTH-8`.

---

## 10. Key Classes Reference

| Class | Package | Purpose |
|-------|---------|---------|
| `Privilege` | `io.metaloom.graph.core.api` | The fifteen names, with `isWrite()` and `isGlobalOnly()` |
| `Grant` | `io.metaloom.graph.core.api` | A privilege on a database or `*`; parses and prints `MERGE ON sales` |
| `Principal` | `io.metaloom.graph.core.api` | Who a request is from; `may(...)`; the `ANONYMOUS` null object |
| `User` / `Role` | `io.metaloom.graph.core.api` | The stored records |
| `AuthStore` | `io.metaloom.graph.core.api` | Opens `.auth/`, authenticates, and mutates under the lock |
| `AuthCodec` | `io.metaloom.graph.core.auth` | The byte format, and the whole of the hostile-input surface |
| `PasswordHash` | `io.metaloom.graph.core.auth` | PBKDF2 derivation, verification and the canonical text form |
| `AuthException` / `AuthFormatException` | `io.metaloom.graph.core.api` | Refusals, under `GraphStoreException` |
| `Ast.Query.requiredPrivileges()` | `io.metaloom.graph.cypher.ast` | The clause-to-privilege mapping |
| `AuthPolicy` | `io.metaloom.graph.server.auth` | Resolves a principal per request; the disabled factory |
| `SessionTable` | `io.metaloom.graph.server.auth` | Opaque tokens, hashed keys, two timeouts |
| `LoginThrottle` | `io.metaloom.graph.server.auth` | Per-user and per-address failure counters, ahead of the KDF |
| `Bootstrap` | `io.metaloom.graph.server.auth` | First-start admin, the generated password, and printing it |

---

## 11. Test Setup

Servers start in-process on port 0 and close in `@AfterEach`; there are no abstract base classes, and
`@RegisterExtension TempStore stores = new TempStore()` supplies a temporary root.

| Class | Module | Tag | Covers |
|---|---|---|---|
| `PrivilegeTest`, `GrantTest`, `PrincipalTest` | graph-core | unit | Wildcards, the global-only refusal, name parsing |
| `AuthCodecTest` | graph-core | unit | Round trip, version refusal, unknown privilege, duplicate name, CRC |
| `AuthHostileInputTest` | graph-core | unit | Truncation, garbage, huge counts, every single-bit mutation |
| `PasswordHashTest` | graph-core | unit | Verification, salting, the text form, the unknown-user cost, the empty password refused |
| `PasswordHashCostTest` | graph-core | slow | One verification takes 20 ms to 1 s — catches `iterations` set to 1 |
| `AuthStoreTest` | graph-core | unit | Bootstrap, CRUD, `generation`, the lock, reload, and that `.auth/` is not a database |
| `PrivilegeMappingTest` | graph-cypher | unit | The clause table, and agreement with `isWrite()` and `readsFiles()` |
| `AuthenticationTest` | graph-server | unit | Login, cookies, Bearer, expiry, the exempt routes, the exempt port, a disable made on disk by another process |
| `AuthorizationTest` | graph-server | unit | Every route with and without its privilege; the escalation tests (grants, a user created in a role, an administrator's password); empty passwords; 403 rather than 404 for an ungranted database |
| `AuthFlagCompositionTest` | graph-server | unit | The AND rule, both refusals, and `--no-auth` equivalence |
| `WireAuthTest` | graph-server | unit | The handshake, both error codes, no database resolved before AUTH, the shared verification bound, grants re-resolved per query, and no leaked event loop |
| `McpAuthTest` | graph-server | unit | All three transports; a refusal is a JSON-RPC error; a tool checked against the database it names; the filtered database list; a websocket re-checked per message |
| `BootstrapTest` | graph-server | unit | The printed password, the ignored variable, the refusals |
| `AuthCliTest` | graph-cli | unit | Every `auth` subcommand, offline-only, stdout/stderr split |
| `auth.test.ts`, `api.test.ts` | frontend | vitest | `may()` and the wildcard; 401 and 403 mapping |
| `e2e/login.spec.ts` | frontend | playwright | The form, a wrong password, logout, a reduced-privilege user |

---

## 12. Progress Assessment

- [x] `Privilege`, `Grant`, `Principal`, `User`, `Role` in `core.api`
- [x] `Ast.Query.requiredPrivileges()` and its exhaustive switch
- [x] `AuthCodec`, `PasswordHash`, `AuthNames`, `AuthStore` and the `.auth/` directory
- [x] GraalVM reachability metadata for the JCA provider, and the native smoke test that catches its absence
- [x] `metaloom-graph-cli auth reset-admin | list | user | role | grant | revoke`
- [x] Server options, bootstrap, and the generated password printed once to stdout
- [x] HTTP authentication: sessions, login, logout, whoami, the throttle ahead of the derivation
- [x] HTTP authorization on every route, the filtered database list, and the user and role routes
- [x] Protocol version 4, the `AUTH` frames, `UNAUTHENTICATED`/`UNAUTHORIZED`, and the client
- [x] MCP: bearer on all three transports, and both enforcement points extended to privileges
- [x] The browser UI: login screen, users-and-roles panel, grant-driven affordances
- [x] The default flipped on, and the images, chart and Playwright harness that depend on it
- [x] **TLS on the binary port** — an optional second listener, verified by default at the client. `AUTH-1`
- [ ] **TLS on the HTTP and MCP ports** — still the largest gap when they are exposed without a proxy. `AUTH-1`
- [ ] An audit log (`AUTH-2`), an external provider (`AUTH-3`), sub-database rules (`AUTH-4`), a `RESTORE`
      privilege (`AUTH-5`), a shared session store (`AUTH-6`), login meters (`AUTH-7`), client certificates
      (`AUTH-8`)

---

_Git HEAD revision: `58cd33e`_
_Last updated: 2026-08-18 (TLS on the binary port)_
