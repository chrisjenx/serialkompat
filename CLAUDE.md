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
./gradlew build            # compile + test + spotlessCheck + apiCheck (the full local gate)
./gradlew test             # tests only
./gradlew spotlessApply    # auto-format (run before committing)
./gradlew apiDump          # regenerate public-API baselines after an INTENTIONAL api change
./gradlew koverHtmlReport  # coverage -> build/reports/kover
```

CI (`.github/workflows/ci.yml`) runs `./gradlew build koverXmlReport` on JDK 17 and 21.

## Modules

| Module | Responsibility | Notes |
|---|---|---|
| `serialkompat-core` | `Snapshot` model, `Differ`, `Classifier`, rule set, `Report` | Pure Kotlin, **no I/O**, no kotlinx-serialization runtime. `explicitApi()`. |
| `serialkompat-extractor` | Runtime `SerialDescriptor` → `Snapshot` | JVM. `explicitApi()`. Applies the serialization plugin for test fixtures. |
| `serialkompat-gradle` | The Gradle plugin (`serialkompatCheck`) | `java-gradle-plugin`. |

Keep the diff/classify engine (`-core`) decoupled from extraction and from where baselines come from — that decoupling is load-bearing (see design §3).

## Conventions & gotchas

- **JVM target is 17** (set in the root `build.gradle.kts`); the build runs on JDK 17+ with `javac --release 17` — do not add a toolchain that would trigger a JDK download.
- **Library modules use `explicitApi()`** — declare visibility and public return types. Public declarations get KDoc.
- **Public API changes** must be reflected by committing the updated `*.api` file (`./gradlew apiDump`). The `.api` diff is part of review.
- **Formatting** is Spotless + ktlint (`.editorconfig`). Run `spotlessApply`.
- **In compiled Gradle plugin code** (not `.gradle.kts`), task-configuration lambdas receive the task as a parameter — use `it`/a named param (`register("x") { task -> task.group = ... }`), not an implicit receiver.
- **Configuration cache is on.** Avoid capturing `Project` at execution time; be wary of plugins that aren't config-cache compatible.
- Never commit secrets. Publishing credentials live in CI secrets only.

## Deferred (tracked as issues)

- `detekt` static analysis (pending Kotlin 2.3 compatibility check).
- Dokka API-docs site.
- Publishing to Maven Central + Gradle Plugin Portal (needs CI secrets).

See the [issues](https://github.com/chrisjenx/serialkompat/issues) and [milestones](https://github.com/chrisjenx/serialkompat/milestones) for the build order.
