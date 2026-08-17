# Deployment

Running MetaLoom // Graph as a container and on Kubernetes.

Configuration itself — every flag and every variable — is [OPERATIONS.md §3.1](OPERATIONS.md#31-configuring-graph-server).
The security posture every decision here follows from is [OPERATIONS.md §9](OPERATIONS.md#9-security-posture).

---

## 1. TL;DR

```bash
mvn -o clean package -DskipTests
container/build-containers.sh jvm            # or: native, or: both
docker run --rm -e GRAPH_DEMO=true -p 8080:8080 -p 8082:8082 metaloom/graph-server:latest

helm install g helm/metaloom-graph --set graph.demo=true
helm/test/run.sh --build                     # the whole thing on a throwaway k3d cluster
```

---

## 2. The image

Two of them, from one jar and one binary:

| | Base | Size | Start | Why |
|---|------|------|-------|-----|
| `Containerfile` | `eclipse-temurin:25-jre` | ~300 MB | seconds | The default |
| `Containerfile.native` | `debian:stable-slim` | ~90 MB | ~110 ms | GraalVM; see [GRAALVM.md](GRAALVM.md) |

Build context is the **repository root**, because the Containerfiles `ADD` build outputs out of
`graph-server/target`. `.dockerignore` and `.containerignore` are byte-identical copies — podman reads the second name
— and they exist because docker does **not** read `.gitignore`, so without them the context would include `/data`,
which is a real store and can be gigabytes.

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

### 2.3 The build script checks what the build left out

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
| `graph.allowWrite` | `false` | No port has authentication. On a ClusterIP Service, enabling this hands everything in the cluster that can resolve the name the ability to delete data |
| `wire.host` | `127.0.0.1` | The Service port exists but nothing outside the pod reaches it until this is widened. Defaulting it to `0.0.0.0` in the *image* would undo that for everyone |
| `securityContext.readOnlyRootFilesystem` | `true` | Only correct **paired with the `/tmp` emptyDir**. Alone it silently removes the native layer |

`persistence.size` defaults to 10Gi, sized from [reports/SCALE.md](reports/SCALE.md) — 10M nodes and 20M relationships
measured at 6.1 GiB, plus the log and room to compact. **It cannot be changed by `helm upgrade`**: a StatefulSet's
`volumeClaimTemplate` is immutable once the object exists. Size it now or plan a migration.

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

Nine phases; **35 assertions, all passing** as of this writing. Three of them are the ones worth having:

- **Phase 7 deletes the pod and asserts the same node count comes back.** Everything else here could be true of a
  chart for a stateless service. This is the phase that tests what this project *is*.
- **Phase 6 asserts `graph_store_nodes` equals `/api/stats`.** A scrape full of plausible zeroes would not tell you the
  cached tier had come unwired from the store the API reads; this does.
- **Phase 9 uninstalls and reinstalls against the retained PVC.** A `volumeClaimTemplate`'s PVC deliberately outlives
  the release, which is the behaviour somebody relies on the first time they uninstall a chart by mistake.

`dump_diagnostics` runs **before** the cluster is deleted — afterwards there is nothing left to ask.

---

## 5. What is not here

- **No authentication, in the image or the chart.** Consistent with the engine; see
  [OPERATIONS.md §9.1](OPERATIONS.md#91-the-network-which-has-the-same-posture-and-a-wider-blast-radius).
- **No backup.** The engine has none ([OPERATIONS.md §5](OPERATIONS.md#5-backup-and-restore)), so neither does the
  chart. A volume snapshot of a stopped pod is the only correct procedure today.
- **No NetworkPolicy.** It would be the right place to bound an unauthenticated Service, and what it should allow
  depends entirely on the cluster.
- **No horizontal scaling, and there will not be.** One writer process is a property of the storage engine.

---

_Last updated: 2026-08-16_
