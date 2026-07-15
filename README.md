# serialkompat

[![CI](https://github.com/chrisjenx/serialkompat/actions/workflows/ci.yml/badge.svg)](https://github.com/chrisjenx/serialkompat/actions/workflows/ci.yml)
[![Docs](https://img.shields.io/badge/docs-chrisjenx.github.io-blue.svg)](https://chrisjenx.github.io/serialkompat/)
[![License](https://img.shields.io/badge/license-Apache%202.0-blue.svg)](LICENSE)
[![Kotlin](https://img.shields.io/badge/kotlin-2.4-blue.svg?logo=kotlin)](https://kotlinlang.org)

**A backward/forward compatibility gate for [kotlinx-serialization](https://github.com/Kotlin/kotlinx.serialization) `@Serializable` models — like [`buf breaking`](https://buf.build/docs/breaking/), but for JSON.**

📖 **[Full documentation → chrisjenx.github.io/serialkompat](https://chrisjenx.github.io/serialkompat/)** — [quick start](https://chrisjenx.github.io/serialkompat/quickstart/) · [rules](https://chrisjenx.github.io/serialkompat/rules/) · [CI setup](https://chrisjenx.github.io/serialkompat/ci/) · [API](https://chrisjenx.github.io/serialkompat/api/)

> 🚧 **Status: early development, built in the open one reviewed PR at a time.** `-SNAPSHOT`s publish to Maven Central on every push to `main`; there is no stable release yet and the plugin is not on the Gradle Plugin Portal ([resolve via `mavenCentral()`](https://chrisjenx.github.io/serialkompat/setup/#gradle-plugin)). Follow along in the [issues](https://github.com/chrisjenx/serialkompat/issues) and [milestones](https://github.com/chrisjenx/serialkompat/milestones).

You delete a field. Payloads in queues, caches, and old app versions still carry it:

```diff
 @Serializable
 data class OrderEvent(
     val id: String,
     val amountCents: Long,
-    val note: String? = null,
 )
```

```console
$ ./gradlew serialkompatCheck
serialkompat: 1 active finding(s) (1 breaking, 0 warning), 0 acknowledged

  BREAK  PROPERTY_REMOVED  com.example.wire.OrderEvent  (backward)
    field 'note' was removed from com.example.wire.OrderEvent
    fix: Removing a field drops its data for tolerant readers; keep it (or bridge a rename with @JsonNames) until nothing uses it; else bump major.
```

## Why

`kotlinx-serialization-json` has no safety net for schema evolution. Rename a property, drop a default, make a field non-null — every old client sending the old shape breaks, and persisted JSON can become undecodable. The usual defence is hand-written round-trip tests.

`serialkompat` makes wire compatibility a CI gate: it reads the JSON schema from your compiled `@Serializable` models, diffs it against a baseline, and fails on incompatible changes. The rules are grounded in how kotlinx-serialization actually behaves.

## How it works

```
@Serializable  ─▶  Extractor  ─▶  Snapshot  ─▶  Differ  ─▶  Classifier  ─▶  Report
   types          (runtime        (canonical    (deltas)    (rules +        (findings
                   descriptor       model)                    severity)       + exit code)
                   walk, JVM)
```

- **Extraction** walks the compiled `SerialDescriptor` graph, so it sees exactly what goes on the wire — real JSON keys (post-`@SerialName`/`namingStrategy`), optionality (`isElementOptional`), nullability, enums, and `SerializersModule`-resolved polymorphism.
- **Baseline** is extracted **live from a git ref** (e.g. your target branch) — no hand-maintained baseline file. For long-horizon persisted-data checks there is also an append-only [schema history](https://chrisjenx.github.io/serialkompat/recipes/) (`serialkompatRecord` / `serialkompatCheckHistory`).
- **Classification** is direction-aware (`BACKWARD` / `FORWARD` / `FULL`) and **config-aware** — it reads your actual `Json { }` settings, because whether a change is safe depends on `ignoreUnknownKeys`, `namingStrategy`, `encodeDefaults`, and friends.
- **Every rule is verified against real kotlinx-serialization** via a round-trip oracle: serialize with the old model, decode with the new one, and assert the classifier predicted what actually happened.

## Usage

See the [quick start](https://chrisjenx.github.io/serialkompat/quickstart/) for the 5-minute path and [setup](https://chrisjenx.github.io/serialkompat/setup/) for the CLI and GitHub Action. Until the plugin is on the Gradle Plugin Portal, resolve it via `mavenCentral()` in `pluginManagement` — see [setup](https://chrisjenx.github.io/serialkompat/setup/#gradle-plugin).

```kotlin
// build.gradle.kts of a module holding @Serializable wire/persisted contracts
import com.chrisjenx.serialkompat.core.CompatibilityDirection

plugins {
    kotlin("jvm")
    kotlin("plugin.serialization")
    id("com.chrisjenx.serialkompat")
}

serialkompat {
    // Root @Serializable types whose JSON wire contract must stay compatible.
    types.set(listOf("com.example.wire.OrderEvent", "com.example.wire.Payment"))
    // Optional: read your real Json { } config (naming strategy, discriminator, …).
    jsonInstance.set("com.example.wire.WireJson.instance")
    baselineRef.set("origin/main")               // optional; unset auto-detects the default branch
    direction.set(CompatibilityDirection.FULL)   // BACKWARD / FORWARD / FULL
    failOnBreaking.set(true)
}
```

Two tasks are registered:

- **`serialkompatExtract`** — dumps the current schema to `build/serialkompat/current.snapshot`.
- **`serialkompatCheck`** — recomputes the baseline from `baselineRef` (a throwaway worktree, no committed baseline to go stale), diffs, and fails on an unacknowledged breaking change. Wired into `check`, so it runs on every build and on CI. Applying the plugin without configuring `types` is a no-op.

The report also renders as JSON (`build/serialkompat/report.json`), SARIF, and GitHub annotations — see [report formats](https://chrisjenx.github.io/serialkompat/report-formats/).

## CI (GitHub Action)

A composite action runs the gate, posts a **sticky PR comment**, and adds inline annotations for the findings (the Gradle task stays CI-agnostic — it emits a JSON report and an exit code; the action does the GitHub-specific posting):

```yaml
# .github/workflows/serialkompat.yml
jobs:
  serialkompat:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v5
        with: { fetch-depth: 0 }        # git-ref-live needs history for the baseline
      - uses: actions/setup-java@v5
        with: { distribution: temurin, java-version: 17 }
      - uses: chrisjenx/serialkompat@v1  # ref: origin/<base> resolved from the PR
```

## What counts as breaking?

A change's severity depends on **direction** and your **reader config**. A few examples under `FULL` with a strict reader:

| Change | Backward (new reads old) | Forward (old reads new) |
|---|:---:|:---:|
| Add optional field | ✅ safe | ⚠️ breaks unless `ignoreUnknownKeys` |
| Add required field | ❌ break | ⚠️ |
| Rename key (no `@JsonNames`) | ❌ break | ❌ break |
| Make field nullable | ✅ safe | ⚠️ old readers choke on `null` |
| Enum: add value | ✅ safe | ⚠️ old readers reject it |
| Enum: remove value | ❌ break | ✅ safe |

See the [rules reference](https://chrisjenx.github.io/serialkompat/rules/) for the full 21-rule matrix and config-aware semantics, and the [deep dive](https://chrisjenx.github.io/serialkompat/deep-dive/) for how extraction, classification, and the git-ref baseline work.

## Modules

| Module | What |
|---|---|
| `serialkompat-core` | Pure-Kotlin `Snapshot` model, differ, classifier, rule set, report. No I/O. |
| `serialkompat-extractor` | Runtime `SerialDescriptor` → `Snapshot` extraction (JVM). |
| `serialkompat-gradle` | The Gradle plugin (`serialkompatCheck`). |
| `serialkompat-cli` | Standalone `serialkompat diff <baseline> <current>` for non-Gradle / cross-repo use. |
| `serialkompat-annotations` | `@SerialkompatIgnore` / `@SerialkompatChecked` discovery markers (Kotlin Multiplatform). |

## Building

```console
./gradlew build          # compile, test, format check (spotless), API check (BCV)
./gradlew spotlessApply  # auto-format
./gradlew apiDump        # update public-API baselines after an intended API change
./gradlew koverHtmlReport
```

Requires JDK 17+. Uses the Gradle wrapper (Gradle 9.6.1), Kotlin 2.4.0, and kotlinx-serialization 1.11.0.

## Publishing

The library modules publish to **Maven Central** via the [vanniktech `maven-publish`](https://github.com/vanniktech/gradle-maven-publish-plugin) plugin. Credentials are never committed — they are read from CI secrets:

| Repo secret | Maps to (`ORG_GRADLE_PROJECT_…`) |
|---|---|
| `MAVEN_CENTRAL_USERNAME` / `MAVEN_CENTRAL_PASSWORD` | `mavenCentralUsername` / `mavenCentralPassword` |
| `SIGNING_KEY_ID` / `SIGNING_KEY` / `SIGNING_KEY_PASSWORD` | `signingInMemoryKeyId` / `signingInMemoryKey` / `signingInMemoryKeyPassword` |
| `APP_ID` / `APP_PRIVATE_KEY` | GitHub App used by the release job to tag, release, and bump the version |

- **Release** (`Release` workflow, `workflow_dispatch` with a version): validates → tests → `publishAndReleaseToMavenCentral` → tags `vX.Y.Z` + GitHub release → moves the floating major tag (`v1`) → bumps `gradle.properties` to the next `-SNAPSHOT`. The floating `vN` tag is what Action consumers pin (`uses: chrisjenx/serialkompat@v1`); it moves only on stable (non-prerelease) releases.
- **Snapshot**: pushes to `main` publish `-SNAPSHOT`s automatically.

Locally, `./gradlew publishToMavenLocal` publishes to `~/.m2` (signing uses your `signing.*` Gradle properties). Gradle Plugin Portal publishing (for `plugins { id("com.chrisjenx.serialkompat") }` resolution) is not yet configured.

### GitHub Actions Marketplace

The composite action (`action.yml`) already carries the Marketplace metadata (name, description, `branding`), and the release workflow keeps the floating major tag current — so listing it is a **one-time manual step** on the first stable release (GitHub can't automate the Marketplace toggle):

1. Cut a stable release via the **Release** workflow (this creates the `vX.Y.Z` release and moves `v1`).
2. On that release's page (**Releases → Edit**), tick **"Publish this Action to the GitHub Marketplace"** and accept the Marketplace Developer Agreement (repo owner, first time only).
3. Pick the primary/secondary categories and save. Later releases can each be published to the Marketplace from the same checkbox, but `uses: chrisjenx/serialkompat@v1` already resolves the moment `v1` exists — Marketplace listing is discoverability, not a functional prerequisite.

## Contributing

Contributions welcome — this project is built test-first. See [CONTRIBUTING.md](CONTRIBUTING.md) and our [Code of Conduct](CODE_OF_CONDUCT.md). Good starting points are issues labelled [`good first issue`](https://github.com/chrisjenx/serialkompat/labels/good%20first%20issue).

## License

[Apache License 2.0](LICENSE) © 2026 Chris Jenkins and serialkompat contributors.
