# Releasing

How a version of MetaLoom // Graph is cut, built, checked and published. There is no CI/CD pipeline that
releases: **`./build.sh --release` on a developer machine is the release process**, and this file is its
manual. The CI workflow (`.github/workflows/ci.yml`) only tests, and is deliberately read-only.

What may change between releases, and the gates that stop an accidental change, are two files of their
own: the Java API in [API_LOCK.md](API_LOCK.md), the on-disk format in [MIGRATION.md](MIGRATION.md).

---

## 1. What a release is

| Artefact | Where it comes from | Published as |
|---|---|---|
| Server, runnable | `graph-server/target/graph-server.jar` (shaded: engine, Cypher, Vert.x, UI) | `graph-server-<version>.jar` on the GitHub release |
| CLI, runnable | `graph-cli/target/graph-cli-cli.jar` (shaded) | `graph-cli-<version>-cli.jar` on the GitHub release |
| Libraries | `graph-core`, `graph-cypher`, `graph-protocol`, `graph-client` jars | on the GitHub release (not yet on Maven Central) |
| JVM image | `container/Containerfile` | `metaloom/graph-server:<version>` and `:latest` |
| Native image | `container/Containerfile.native` (GraalVM), optional | `metaloom/graph-server:<version>-native` and `:latest-native` |
| Helm chart | `helm/metaloom-graph` | `metaloom-graph-<version>.tgz` on the GitHub release |
| Notes | the version's section of `CHANGELOG.md` | the GitHub release text |

`build.sh --release` collects all of the files into `dist/<version>/` with a `SHA256SUMS`, and creates the
annotated tag `v<version>`. It pushes nothing.

**The jars under `target/` have version-free names** (`<finalName>` in the server and CLI poms, `outputFile`
for the CLI's shaded jar). So no script, no Containerfile and no document names a version, and bumping it is
a pom change. Only `dist/` puts the version back into file names, because that is where files leave the
machine. The built jars carry the version in `Implementation-Version`, which is how
`container/build-containers.sh` refuses a jar left over from before a bump, and how the MCP `initialize`
answer reports it.

## 2. Versions

`MAJOR.MINOR.PATCH`, with a pre-release suffix while the interfaces are not frozen:
`0.1.0-alpha.1`, `0.1.0-alpha.2`, …, then `-beta.N`, `-rc.N`, then `0.1.0`. Between releases the poms carry
the next version with `-SNAPSHOT` (`0.1.0-alpha.2-SNAPSHOT`).

The version is written down in exactly these places, and `build.sh` compares every one of them with the
root pom (a warning on an ordinary build, a refusal on `--release`):

| Place | Field |
|---|---|
| `pom.xml` and every `graph-*/pom.xml` | `<version>` of the project and of the parent reference |
| `helm/metaloom-graph/Chart.yaml` | `version` **and** `appVersion` — kept equal: chart `0.1.0-alpha.1` deploys image `0.1.0-alpha.1` |
| `graph-server/src/main/frontend/package.json` (and the lock file) | `version` |
| `CHANGELOG.md` | a `## [<version>] - <date>` section |

`appVersion` is never `latest`: the same chart version must always deploy the same image. A pre-release
chart version is skipped by `helm install` from a repository unless asked for with `--devel` or `--version`,
which is the right default for an alpha.

## 3. Cutting a release, step by step

1. **Set the version** in every place in §2. For the poms:
   `sed -i 's|<version>OLD</version>|<version>NEW</version>|' pom.xml graph-*/pom.xml` — the project's own
   version is the only `<version>` with that value in those files. In `Chart.yaml`, also add a line to the
   changelog comment above `version`.
2. **Write the CHANGELOG section**: what is new, what changed, what broke, the compatibility statement, and the
   known issues, under `## [NEW] - YYYY-MM-DD` (the date of the release — `build.sh --release` refuses
   `Unreleased`).
3. **Check the API lock.** If the API changed since the last release, the accepted baselines are part of the
   diff under review; under `policy: stable`, no removals. [API_LOCK.md](API_LOCK.md).
4. **Check the format.** If anything on disk changed since the last release, the store format version was
   bumped, a migration step exists, and a golden fixture for the new version is committed beside the old ones.
   [MIGRATION.md](MIGRATION.md).
5. **Commit.** A release is built from a commit; `--release` refuses a dirty tree.
6. **Build it:** `./build.sh --release` (add `--native` for the GraalVM image, `--helm` for the k3d chart test).
   What it runs is §4. Nothing is published yet, so a failure here costs nothing but a fix and another run.
7. **Publish**, with the commands `build.sh` prints at the end: push the tag, push the images, create the
   GitHub release as a pre-release from `dist/<version>/`.
8. **Look at it once from outside**: `docker pull metaloom/graph-server:<version>`, run it, open the UI.
9. **Open the next development version**: the poms to `<next>-SNAPSHOT`, commit. The chart, the UI and the
   CHANGELOG move at the next release's step 1, so between releases an ordinary build warns that they disagree.

A version is released once. If something is wrong after step 7, the fix is the next version, not a moved tag.

## 4. What `build.sh` runs

| Step | Default build | `--release` |
|---|---|---|
| Version consistency (§2) | warns | refuses on a mismatch, a SNAPSHOT, `Unreleased`, a dirty tree or an existing tag |
| `mvn clean verify -Dmetaloom.graph.native.required=true` | yes (`--skip-tests`: `package` only) | yes, no skipping |
| `mvn test -pl graph-core -Pcrash` | — | yes |
| GraalVM binaries | `--native` | `--native` |
| JVM image (`container/build-containers.sh jvm`) | yes, version tag only | yes, version tag and `latest` |
| `helm lint` and `helm template` | yes (warns without helm) | yes (refuses without helm) |
| Smoke test (`tools/smoke.sh`, §5) | yes | yes |
| Chart on k3d (`helm/test/run.sh`) | `--helm` | `--helm` |
| `dist/<version>/`, `SHA256SUMS`, `RELEASE_NOTES.md` | — | yes |
| Annotated tag `v<version>` | — | yes, local only |

`native.required=true` is the one flag that matters most: without it a machine with no `cargo` produces a
pure-Java jar silently, and a release image without the Rust layer passes every test. The smoke test then
checks the image, not the build, for the same thing.

**`latest` moves only on a release.** An ordinary build tags the image with the version alone, and
`helm/test/run.sh --build` uses a throwaway `helmtest` tag with `LATEST=false`.

`MAVEN_ARGS` replaces the default `-o` (offline) for every Maven call.

## 5. The smoke test

`tools/smoke.sh [image]` runs the built image the way a user does, because `mvn test` proves the code and
cannot prove the image: that the jar inside is the one just built, that the native layer loads under the
image's libc, that the UI is bundled, that the shipped defaults are what a user gets, and that the CLI
shipped beside the server can take a dump the server can be rebuilt from.

| # | Check |
|---|---|
| 1 | Starts with the shipped defaults plus `GRAPH_ALLOW_WRITE` and `GRAPH_ALLOW_BACKUP`; `/ready` and `/health` answer |
| 2 | The image's `org.opencontainers.image.version` label (and the jar's manifest, where `unzip` exists) is the pom's version |
| 3 | An anonymous query is refused with 401 — authentication is on by default |
| 4 | `admin` logs in with `GRAPH_INITIAL_PASSWORD` |
| 5 | `/api/stats` reports the native layer loaded (`ABI …`), and the UI bundle is present |
| 6 | 250 nodes and 249 relationships written and read back, including a variable-length walk |
| 7 | An online backup through `metaloom-graph-cli … --server` with `GRAPH_TOKEN` |
| 8 | A server started with nothing enabled refuses a write |
| 9 | The dump restored offline into a fresh volume by the CLI in the same image |
| 10 | The restored store serves the same counts |
| 11 | The demo graph over the binary protocol, by `tools/WireSmoke.java` (dependency-free) |

Everything it creates is named `smoke-<pid>-*` and removed on exit; `SMOKE_KEEP=1` leaves it for a look.

## 6. Not yet

- **Maven Central.** `io.metaloom:maven-parent` still configures the retired OSSRH staging endpoint
  (`s01.oss.sonatype.org`); publishing the libraries there needs the Central Portal (its own plugin, or the
  parent's JReleaser profile) and a decision about which modules are published.
- **A release pipeline.** CI tests both the native and the pure-Java path on every push; it builds no images
  and publishes nothing. Moving `build.sh --release` into a workflow is the obvious step once releases are
  regular.
- **Images for `linux/arm64`**, which first needs the native library cross-compiled
  ([PROGRESS.md](PROGRESS.md), Phase 12).

---

_Git HEAD revision: `8f8caad`_
_Last updated: 2026-10-25 (first release process: build.sh, smoke test, version-free jars, image tags)_
