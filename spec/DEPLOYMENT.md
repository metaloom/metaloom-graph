# Deployment

Running MetaLoom // Graph as a container and on Kubernetes.

Configuration itself — every flag and every variable — is [OPERATIONS.md §3.1](OPERATIONS.md#31-configuring-graph-server).
The security posture every decision here follows from is [OPERATIONS.md §9](OPERATIONS.md#9-security-posture).

---

## 1. TL;DR

```bash
./build.sh                                   # tests, jars, image, chart lint, smoke test; spec/RELEASE.md
# or by hand:
mvn -o clean package -DskipTests             # native: mvn -o -Pgraalvm clean verify -DskipTests
container/build-containers.sh jvm            # or: native, or: both — tagged <pom version> (and latest)
docker run --rm -d --name g -e GRAPH_DEMO=true -e GRAPH_ALLOW_BACKUP=true -p 8080:8080 -p 8082:8082 \
    metaloom/graph-server:latest              # build-containers.sh prints this line with the exact tag
docker exec g metaloom-graph-cli database backup default --server=http://localhost:8080 --out=- > g.dump

helm install g helm/metaloom-graph --set graph.demo=true
helm/test/run.sh --build                     # the whole thing on a throwaway k3d cluster
```

---

## 2. The image

Two of them, each carrying **two** programs — the server and the backup tool:

| | Base | Size | Start | Why |
|---|------|------|-------|-----|
| `Containerfile` | `eclipse-temurin:25-jre` | ~300 MB | seconds | The default |
| `Containerfile.native` | `debian:stable-slim` | ~90 MB | ~110 ms | GraalVM; see [GRAALVM.md](GRAALVM.md) |

Build context is the **repository root**, because the Containerfiles `ADD` build outputs out of
`graph-server/target` and `graph-cli/target`. `.dockerignore` and `.containerignore` are byte-identical copies —
podman reads the second name — and they exist because docker does **not** read `.gitignore`, so without them the
context would include `/data`, which is a real store and can be gigabytes.

**`verify`, not `package`, for the native images.** native-image is bound to the verify phase in both poms, so
`mvn -Pgraalvm package` finishes happily and produces no binary at all, which reads as a broken profile.

### 2.1 Not Alpine, and this is a correctness decision

`graph-core/native-rust-lib/rust-toolchain.toml` pins `x86_64-unknown-linux-gnu`, and `NativeCapabilities` unpacks
that `.so` to `java.io.tmpdir` and `dlopen`s it. On musl the `dlopen` fails, the capability status becomes
`"failed to load"`, and the server carries on happily on the pure-Java fallback — silently giving up the one thing the
JVM cannot approach, which is **12.4x on scattered reads** via io_uring. No error, no crash, just a slower database.

`GET /api/stats` reports `nativeLayer`, and the value on a correctly built image is **`ABI 2`**. That single assertion
is what proves the base image is right; `not built` means the jar was built with `-Dnative.skip=true`, and
`failed to load` means the base image or `/tmp` is wrong.

Alpine becomes viable once the crate is also built for `x86_64-unknown-linux-musl` under a second classifier —
`fromClasspath()` already keys on one, so the resource layout supports it. That is a bigger change than an image.

**Native plus Alpine is the one combination that cannot work at all**: the GraalVM binary is linked against glibc.

### 2.2 `/tmp` is load-bearing

It has to be **writable and not `noexec`**, because that is where the native library lands before it is `dlopen`ed.
The image sets `-Djava.io.tmpdir=/tmp` explicitly, and the chart mounts an `emptyDir` there precisely so that
`readOnlyRootFilesystem: true` does not quietly cost the native layer.

### 2.3 The backup tool is inside the image

Both images carry `metaloom-graph-cli` on the `PATH` — the JVM image as a launcher over
`/graph/graph-cli.jar`, the native image as its own compiled binary. A backup that needs a second artifact
downloaded, version-matched and mounted is a backup that gets taken once; and in the native image there is no JVM,
so without the compiled CLI that container could not be backed up from the inside **at all**.

```bash
# Out of a running server. Only the dump goes to stdout, so this pipe is clean.
docker exec g metaloom-graph-cli database backup default --server=http://localhost:8080 --out=- > g.dump

# Back into a volume, with nothing serving it. No --path (GRAPH_ROOT is /data) and no --force (it is empty).
docker run --rm -i -v graphdata:/data metaloom/graph-server:latest \
    metaloom-graph-cli database restore default --in=- < g.dump
```

That second one works because there is no `ENTRYPOINT` in either image — a deliberate property, and the same one
that makes `docker run <image> --help` work for the server.

**`--server` while a server is running, `--path` only when none is.** `GRAPH_ROOT=/data` is read by *both*
programs, so a bare `database backup` in a serving container tries to open `/data` directly and is refused: the
server holds the store's exclusive `LOCK`, which is the engine's guarantee that one process writes. The error says
so and names `--server`. Restore is offline-only regardless ([BACKUP.md §6](BACKUP.md)), which is why it is spelled
as a second container rather than as an `exec`.

`GRAPH_ALLOW_BACKUP` gates the online half and is **off** in both images. It is separate from `GRAPH_ALLOW_WRITE`
because a backup is a read that hands over every byte of a database in one request, from a port with no
authentication — a different decision from "answer queries".

### 2.4 The build script checks what the build left out

Both the UI and the native layer are optional at build time — the Maven build routes around a missing `npm` or
`cargo` — and neither absence is visible at run time: the server starts and answers every API route, and is simply
worse. `build-containers.sh` therefore inspects the jar and says so, because packaging that into an image is how it
stops being noticed at all.

---

## 3. The chart

`helm/metaloom-graph`. One `StatefulSet`, one `Service`, one headless `Service`, a `ServiceAccount`, and optionally an
`Ingress` and a `ServiceMonitor`. **No `Secret`**, because there is no credential in this system — `NOTES.txt` says so
rather than leaving an absence to be interpreted.

### 3.1 StatefulSet, not Deployment

The store holds a `LOCK` file, allows exactly one writer process, and memory-maps its segments. A Deployment's default
`RollingUpdate` starts the replacement pod **before** the old one terminates, so for a few seconds two processes hold
one ReadWriteOnce volume: the new one fails to take the lock and crash-loops while the old one is still serving. That
reads as a broken image rather than as the ordering mistake it is.

`OrderedReady` with one replica means the old pod is fully gone first. `terminationGracePeriodSeconds: 30` must exceed
the server's own budget — 5s draining running queries, 2s letting abandoned ones unwind, then a checkpoint.

**`replicaCount` other than 1 is refused at template time**, with a message naming the `LOCK` file. That is the honest
encoding of "no concurrent writers": scale by giving a graph its own release, not its own replica.

### 3.2 Probes are on the monitoring port

`/health` and `/ready`, never the query port. A query load heavy enough to saturate HTTP would otherwise time out the
liveness probe and get a merely-busy server killed, turning a slow database into a restart loop.

`/ready` turns 503 as soon as shutdown begins, which is what takes the pod out of the Service's endpoints **before**
the listener closes; without it every rolling update drops the connections that arrive in the gap. A `tcpSocket`
fallback exists for an older image (`probes.useHealthEndpoint=false`) and cannot express that distinction at all.

A **startup probe** buys recovery time. Recovery is a single redo pass and is normally milliseconds, but after a crash
during a large bulk load it can take minutes — and a liveness probe firing during it produces a crash loop that can
never finish recovering.

### 3.3 Three values that encode a decision

| Value | Default | Why |
|-------|---------|-----|
| `graph.allowWrite` | `false` | The deployment's ceiling. With `auth.enabled` a caller also needs the privilege and both must permit; with it off, this is the only thing between a ClusterIP Service and your data |
| `wire.host` | `127.0.0.1` | The Service port exists but nothing outside the pod reaches it until this is widened. Defaulting it to `0.0.0.0` in the *image* would undo that for everyone |
| `wire.tls.enabled` | `false` | Off because it cannot be on: a chart that generated a certificate would generate one nothing trusts, and the key has to come from a Secret the operator names. §3.4 |
| `securityContext.readOnlyRootFilesystem` | `true` | Only correct **paired with the `/tmp` emptyDir**. Alone it silently removes the native layer |

`persistence.size` defaults to 10Gi, sized from [reports/SCALE.md](reports/SCALE.md) — 10M nodes and 20M relationships
measured at 6.1 GiB, plus the log and room to compact. **It cannot be changed by `helm upgrade`**: a StatefulSet's
`volumeClaimTemplate` is immutable once the object exists. Size it now or plan a migration.

### 3.4 TLS on the binary port

```bash
kubectl create secret tls graph-tls --cert=tls.crt --key=tls.key      # or let cert-manager write it
helm upgrade graph ./helm/metaloom-graph \
  --set wire.tls.enabled=true --set wire.tls.secretName=graph-tls
```

That opens **8084 as well as 8081** and mounts the Secret read-only at `/tls`. Set `wire.tls.only=true` to close 8081,
after — not before — `graph_wire_connections{tls="false"}` has stopped rising. [WIRE.md §7.1](WIRE.md) has the design.

Four things the chart decides, each of which could reasonably have gone the other way:

- **It does not create the Secret.** A generated certificate is one nothing trusts, and taking key material through
  `values.yaml` would put a private key in `helm get values` and in whatever holds the release. Point it at
  cert-manager, at your CA, or at `kubectl create secret tls`.
- **`kubernetes.io/tls` keys by default.** `tls.crt` and `tls.key`, which is what cert-manager and every ACME client
  write, so the common case needs no translation. `wire.tls.certKey` and `privateKeyKey` cover the rest.
- **Mounted `0440`, not `0400`.** A Secret volume's files are owned by root with the group set to
  `podSecurityContext.fsGroup` — 0 here — while the process runs as uid 1000. Owner-only would leave the key
  unreadable by the process that needs it, and the symptom is a pod that starts and then cannot bind.
- **It affects the binary port only.** TLS for the HTTP port belongs on the Ingress (`ingress.tls`), which is where a
  platform already provisions and renews certificates. The binary port is the one an Ingress cannot terminate, because
  it is a raw TCP protocol.

**The certificate is read once, at bind.** A renewal takes effect when the pod restarts — with cert-manager's default
renewal window that is a scheduled restart rather than an outage, but it is a restart, and nothing here reloads.

---

## 4. The k3d harness

`helm/test/run.sh` deploys the chart on a throwaway cluster and checks that what comes up is a working graph. k3d and
kubectl are downloaded into `helm/test/.bin`, and `KUBECONFIG` points inside `helm/test/.work` — it never touches
`~/.kube/config`.

```bash
helm/test/run.sh --core       # lint and render only: no cluster, no docker
helm/test/run.sh --build      # build the jar and image first, then the full run
helm/test/run.sh --keep       # leave the cluster up afterwards
helm/test/run.sh --down       # delete it
```

Nine phases; **48 assertions, all passing** against `metaloom/graph-server:0.1.0-alpha.1` as of this writing, with
authentication on as it is in every default install: the chart gets `auth.initialPassword`, and every phase logs in
as `admin` (again after each restart, since sessions are in memory). Three of them are the ones worth having:

- **Phase 7 deletes the pod and asserts the same node count comes back.** Everything else here could be true of a
  chart for a stateless service. This is the phase that tests what this project *is*.
- **Phase 6 asserts `graph_store_nodes` equals `/api/stats`.** A scrape full of plausible zeroes would not tell you the
  cached tier had come unwired from the store the API reads; this does.
- **Phase 9 uninstalls and reinstalls against the retained PVC.** A `volumeClaimTemplate`'s PVC deliberately outlives
  the release, which is the behaviour somebody relies on the first time they uninstall a chart by mistake.

`dump_diagnostics` runs **before** the cluster is deleted — afterwards there is nothing left to ask.

---

## 5. What is not here

- **Authentication is on in the image and configurable in the chart**, and there is a Secret now — see the section
  above. **TLS is available on the binary port** (§3.4) and not on the HTTP or MCP ports, which expect an Ingress or a
  mesh in front of them; without one a password crosses those in cleartext. See
  [OPERATIONS.md §9.1](OPERATIONS.md#91-the-network-which-has-the-same-posture-and-a-wider-blast-radius).
- **No certificate reload.** A renewed Secret is not picked up until the pod restarts (§3.4).
- **No backup in the chart.** The tool is in the image (§2.3) and `kubectl exec <pod> -- metaloom-graph-cli
  database backup default --server=http://localhost:8080 --out=- > g.dump` works today, given
  `graph.allowBackup`. What is not here is a `CronJob`, a retention policy or anywhere to put the dump —
  scheduling is not something this chart should decide, and a chart that half-decided it would be worse than
  one that says so.
- **No NetworkPolicy.** It would be the right place to bound which workloads may reach the Service at all, and what it
  should allow depends entirely on the cluster.
- **No horizontal scaling, and there will not be.** One writer process is a property of the storage engine.

---

---

## Authentication in the image and the chart

Both images set `GRAPH_AUTH_ENABLED=true`, which is the server's own default and the one setting here
whose unsafe value is what somebody gets by not thinking about it. Neither bakes a password: a password
in an image is a password every copy of that image shares.

The chart now ships a **Secret**, which `NOTES.txt` used to state it did not — that statement was true
and is not any more:

| Value | Effect |
|---|---|
| `auth.enabled` (`true`) | Sets `GRAPH_AUTH_ENABLED`. The monitoring port stays open either way |
| `auth.initialPassword` | Rendered into `<release>-auth` and referenced with `secretKeyRef` |
| `auth.existingSecret` | Reference a Secret you manage; takes precedence, and no Secret is created |
| `auth.secretKey` (`initial-password`) | The key inside whichever Secret is used |
| `auth.sessionTimeoutMillis` | Idle timeout; a 12 hour absolute cap applies as well |

**`secretKeyRef` and never `value:`.** An env var set with a literal is visible in
`kubectl describe pod` and in `/proc/<pid>/environ` to anything sharing the namespace, which for a
credential is the whole point of using a Secret at all.

With no password configured, the server generates one on first start and prints it to standard output —
which in a pod **is** the container log. So it is exposed the moment it is printed, `kubectl logs` is
where to read it, and rotating it afterwards is the sensible next step. `NOTES.txt` prints the exact
`kubectl logs` and `auth reset-admin` commands rather than leaving that to be worked out.

Two consequences of the session table being per process, both stated in `NOTES.txt`:

- **A restart logs everyone out.** Sessions are in memory; that is the trade for having no shared store.
- **More than one replica needs a client pinned to a pod**, because a token issued by one is not
  recognised by another. The chart's `replicas: 1` default was already the right shape for a
  single-writer engine and is now load-bearing for a second reason.

`helm/test/run.sh` deploys the chart on k3d and runs a real pipeline. Its offline phase now renders the chart with TLS
on, with TLS-only, and with `wire.tls.enabled` and no secret name — asserting the refusal, the mount mode, and that
adding a certificate leaves the cleartext port open. The cluster phases log in and run authenticated queries, and
phase 5 also asserts an anonymous query is refused with 401.

**It caught a crash loop in every default install, and only it could have.** Helm reads a YAML integer of a million
or more as a float and prints it as `1.8e+06`, so the default `auth.sessionTimeoutMillis: 1800000` reached the server
as a value it refuses to parse, and the pod never became ready. `helm lint` and `helm template` are both happy with
that output. Every numeric value in the StatefulSet now goes through `int64`, and the offline phase asserts that no
rendered value is in scientific notation — with the defaults and with large values set by hand.

_Last updated: 2026-10-25 (build.sh and version tags in the TL;DR; images tagged with the pom version)_
