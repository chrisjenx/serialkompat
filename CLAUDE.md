# CLAUDE.md

Guidance for Claude Code (and humans) working in this repo.

## What this is

`serialkompat` is a backward/forward compatibility gate for kotlinx-serialization `@Serializable` models — a `buf breaking` for JSON. It extracts the JSON wire schema from compiled `SerialDescriptor`s, diffs it against a baseline extracted live from a git ref, and fails CI on incompatible changes.

**The authoritative design is [`docs/design/2026-06-30-serialkompat-design.md`](docs/design/2026-06-30-serialkompat-design.md). Read it before non-trivial work.** If a change deviates from the design, update the design in the same PR and call it out.

## Golden rules

- **Test-first, always.** RED → GREEN → REFACTOR. Write a failing test that fails for the right reason, then the minimum code to pass. No production code without a driving test.
- **Rules are verified against the real library.** A classification rule is not done until a round-trip oracle test backs it: serialize a payload with the old model, decode with the new one (and vice versa) using real kotlinx-serialization under the declared `Json` config, and assert the classifier predicted the actual outcome.
- **Never break the gate's guarantees:** the extractor must never throw on a model it can't fully analyse (record it as an opaque/coverage-gap node instead), and unanalysable ≠ safe.
- One logical change per PR; keep CI green.

## Commands

```console
./gradlew build            # compile + test + spotlessCheck + apiCheck + checkRulesDoc/checkRulesProof (the full local gate)
./gradlew test             # tests only
./gradlew spotlessApply    # auto-format (run before committing)
./gradlew apiDump          # regenerate public-API baselines after an INTENTIONAL api change
./gradlew koverHtmlReport  # coverage -> build/reports/kover
```

CI (`.github/workflows/ci.yml`) runs `./gradlew build koverXmlReport -x :serialkompat-annotations:build` on ubuntu, JDK 17 and 21, plus a `macos-latest` job for `:serialkompat-annotations:build`. `Secret Scan` (`gitleaks.yml`) runs gitleaks over the full history on PRs and pushes to `main`. `Docs` (`docs.yml`) runs `dokkaGenerate` + `mkdocs build --strict` on PRs touching `docs/**`, `mkdocs.yml`, `requirements-docs.txt` or `main.py` (and deploys on push to `main`).

## Modules

| Module | Responsibility | Notes |
|---|---|---|
| `serialkompat-core` | `Snapshot` model, `Differ`, `Classifier`, rule set, `Report` | Pure Kotlin, **no I/O**, no kotlinx-serialization runtime. `explicitApi()`. |
| `serialkompat-extractor` | Runtime `SerialDescriptor` → `Snapshot` | JVM. `explicitApi()`. Applies the serialization plugin for test fixtures. |
| `serialkompat-gradle` | The Gradle plugin (`serialkompatCheck`) | `java-gradle-plugin`. |
| `serialkompat-cli` | Standalone `serialkompat diff <baseline> <current>` (non-Gradle / cross-repo) | Application; excluded from `apiCheck`. |
| `serialkompat-annotations` | `@SerialkompatIgnore` / `@SerialkompatChecked` discovery markers | **Kotlin Multiplatform** (only KMP module). `explicitApi()`. `RUNTIME` retention so the extractor scanner reads them from bytecode. |

Keep the diff/classify engine (`-core`) decoupled from extraction and from where baselines come from — that decoupling is load-bearing (see design §3).

## Conventions & gotchas

- **JVM target is 17** (set in the root `build.gradle.kts`); the build runs on JDK 17+ with `javac --release 17` — do not add a toolchain that would trigger a JDK download.
- **Library modules use `explicitApi()`** — declare visibility and public return types. Public declarations get KDoc.
- **Public API changes** must be reflected by committing the updated `*.api` file (`./gradlew apiDump`). The `.api` diff is part of review. Run `apiDump` and `apiCheck` as **separate** Gradle invocations — combining them (`./gradlew apiDump apiCheck`) fails with a BCV "implicit dependency" validation error.
- **Formatting** is Spotless + ktlint (`.editorconfig`). Run `spotlessApply`.
- **In compiled Gradle plugin code** (not `.gradle.kts`), task-configuration lambdas receive the task as a parameter — use `it`/a named param (`register("x") { task -> task.group = ... }`), not an implicit receiver.
- **Configuration cache is on.** Avoid capturing `Project` at execution time; be wary of plugins that aren't config-cache compatible.
- **`serialkompat-annotations` (KMP) builds Apple targets, which need a macOS host.** `ci.yml`'s ubuntu job excludes it (`-x :serialkompat-annotations:build`) and a separate `macos-latest` job is its sole `apiCheck`/native gate; `release`/`snapshot` publish jobs run on macOS so klibs are complete. These are deliberate — don't "simplify" them. Linux silently *disables* Apple targets (no error), so a green ubuntu build never proves that module complete.
- **The Gradle wrapper is upgraded only via `./gradlew wrapper`**, never by hand-editing `distributionUrl` or swapping the jar — a partial bump leaves `gradlew`/`gradlew.bat` on the old version. Dependabot can't run the task, so `gradle-wrapper` is ignored in `.github/dependabot.yml`; `.github/workflows/gradle-wrapper.yml` owns wrapper PRs.
- Never commit secrets. Publishing credentials live in CI secrets only.

## Publishing & deferred work (tracked as issues)

- `detekt` static analysis (#26; pending Kotlin 2.4 compatibility check).
- Gradle Plugin Portal publishing (#24). The plugin + marker go to Maven Central only, so consumers need `mavenCentral()` in `pluginManagement { repositories }`.

Maven Central publishing is **live** for SNAPSHOTs (vanniktech `maven-publish` on the four library modules incl. `serialkompat-annotations`; `Snapshot` workflow on push to `main`). The CI secrets are set.

## Releasing

Releases are **the maintainer's call** — never dispatch `Release` unless explicitly asked. When asked:

1. **Notes PR:** rename `## [Unreleased]` in `CHANGELOG.md` to `## [X.Y.Z] - YYYY-MM-DD`, add an empty `## [Unreleased]` above it, merge.
2. **Dispatch** `gh workflow run release.yml --ref main -f version=X.Y.Z`. Preflight refuses a final release without that CHANGELOG section; the GitHub release body is that section (`scripts/changelog-section.sh`) plus generated PR notes. It publishes to Central, tags `vX.Y.Z`, moves the floating `v<major>` tag (`v0` while 0.x), and opens a version-bump PR (`gradle.properties` → next `-SNAPSHOT`, README install snippet → `X.Y.Z`).
3. **Merge the bump PR** (it's authored by `GITHUB_TOKEN`, so its CI is dispatched by the workflow). Its merge redeploys the docs site, whose `{{ skversion }}` comes from the newest `vX.Y.Z` tag.
4. **Verify:** `gh release view vX.Y.Z`, and the artifacts on Maven Central (can take ~30 min to appear).

Semver while 0.x: a breaking change to the DSL, snapshot format or public API bumps the minor. Details: README → Publishing.

See the [issues](https://github.com/chrisjenx/serialkompat/issues) and [milestones](https://github.com/chrisjenx/serialkompat/milestones) for the build order.
