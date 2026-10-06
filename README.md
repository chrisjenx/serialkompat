# serialkompat

[![CI](https://github.com/chrisjenx/serialkompat/actions/workflows/ci.yml/badge.svg)](https://github.com/chrisjenx/serialkompat/actions/workflows/ci.yml)
[![Docs](https://img.shields.io/badge/docs-chrisjenx.github.io-blue.svg)](https://chrisjenx.github.io/serialkompat/)
[![License](https://img.shields.io/badge/license-Apache%202.0-blue.svg)](LICENSE)
[![Kotlin](https://img.shields.io/badge/kotlin-2.4-blue.svg?logo=kotlin)](https://kotlinlang.org)

**A backward/forward compatibility gate for [kotlinx-serialization](https://github.com/Kotlin/kotlinx.serialization) `@Serializable` models — like [`buf breaking`](https://buf.build/docs/breaking/), but for JSON.**

📖 **[Full documentation → chrisjenx.github.io/serialkompat](https://chrisjenx.github.io/serialkompat/)** · [quick start](https://chrisjenx.github.io/serialkompat/quickstart/) · [rules](https://chrisjenx.github.io/serialkompat/rules/) · [CI setup](https://chrisjenx.github.io/serialkompat/ci/) · [API](https://chrisjenx.github.io/serialkompat/api/)

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

`kotlinx-serialization-json` has no safety net for schema evolution. Rename a property, drop a default, or make a field non-null, and every old client still sending the old shape breaks. Persisted JSON can become undecodable. The usual defence is hand-written round-trip tests.

serialkompat turns wire compatibility into a CI gate. It reads the JSON schema from your compiled `@Serializable` models, diffs it against a baseline, and fails the build on incompatible changes. The rules are grounded in how kotlinx-serialization actually behaves.

## How it works

```
@Serializable  ─▶  Extractor  ─▶  Snapshot  ─▶  Differ  ─▶  Classifier  ─▶  Report
   types          (runtime        (canonical    (deltas)    (rules +        (findings
                   descriptor       model)                    severity)       + exit code)
                   walk, JVM)
```

- **Extraction** walks the compiled `SerialDescriptor` graph, so it sees exactly what goes on the wire: the real JSON keys (after `@SerialName` and `namingStrategy`), optionality, nullability, enums, and polymorphism resolved through your `SerializersModule`.
- **The baseline** is the "old" schema you compare against. It is extracted **live from a git ref**, such as your target branch, so there is no baseline file to maintain. For long-lived persisted data there is also an append-only [schema history](https://chrisjenx.github.io/serialkompat/recipes/#persisted-data-horizon-multi-version-history) (`serialkompatRecord` / `serialkompatCheckHistory`).
- **Classification** is direction-aware and config-aware. *Backward* compatible means new code can read old data. *Forward* compatible means old code can read new data. `FULL` checks both. It also reads your actual `Json { }` settings, because whether a change is safe depends on `ignoreUnknownKeys`, `namingStrategy`, `encodeDefaults`, and friends.
- **Every rule is verified against real kotlinx-serialization** by a round-trip oracle test: serialize with the old model, decode with the new one, and assert the classifier predicted what actually happened.

## Usage

The plugin is published to Maven Central, not the Gradle Plugin Portal, so add `mavenCentral()` to your plugin repositories:

```kotlin
// settings.gradle.kts
pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}
```

Then apply it to the module that holds your `@Serializable` wire or persisted models:

```kotlin
// build.gradle.kts
import com.chrisjenx.serialkompat.core.CompatibilityDirection

plugins {
    kotlin("jvm")
    kotlin("plugin.serialization")
    id("com.chrisjenx.serialkompat") version "0.1.0"
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

The two tasks you'll use most:

- **`serialkompatExtract`** writes the current schema to `build/serialkompat/current.snapshot`.
- **`serialkompatCheck`** extracts the baseline from `baselineRef` in a temporary git worktree, diffs it against the current schema, and fails on any unacknowledged breaking change. It is wired into `check`, so it runs on every `./gradlew build`.

If you set neither `types` nor a [discovery mode](https://chrisjenx.github.io/serialkompat/configuration/#discovery-modes), the plugin does nothing.

The report also renders as JSON (`build/serialkompat/report.json`), SARIF, and GitHub annotations; see [report formats](https://chrisjenx.github.io/serialkompat/report-formats/). For a 5-minute walkthrough, read the [quick start](https://chrisjenx.github.io/serialkompat/quickstart/). For the CLI and every option, see [setup](https://chrisjenx.github.io/serialkompat/setup/).

## CI (GitHub Action)

The composite action runs the gate, posts a **sticky PR comment** with the findings, and adds annotations to the workflow run. The Gradle task itself stays CI-agnostic: it writes a JSON report and sets the exit code.

```yaml
# .github/workflows/serialkompat.yml
on: pull_request
jobs:
  serialkompat:
    runs-on: ubuntu-latest
    permissions:
      pull-requests: write              # needed to post the sticky comment
    steps:
      - uses: actions/checkout@v5
        with: { fetch-depth: 0 }        # the baseline is extracted from git history
      - uses: actions/setup-java@v5
        with: { distribution: temurin, java-version: 17 }
      - uses: chrisjenx/serialkompat@v0
        with:
          ref: origin/${{ github.base_ref }}
```

`@v0` is a floating tag that tracks the latest stable 0.x release. See [CI setup](https://chrisjenx.github.io/serialkompat/ci/) for the action's inputs and for other CI systems.

## What counts as breaking?

Severity depends on the **direction** and on your **reader config**. A few examples, with default `Json { }` settings:

| Change | Backward (new reads old) | Forward (old reads new) |
|---|:---:|:---:|
| Add optional field | ✅ safe | ❌ break, unless the old reader has `ignoreUnknownKeys` |
| Add required field | ❌ break | ❌ break, unless the old reader has `ignoreUnknownKeys` |
| Rename key (no `@JsonNames`) | ❌ break | ❌ break |
| Make field nullable | ✅ safe | ❌ break: old readers reject `null` (⚠️ warn with `explicitNulls = false`) |
| Enum: add value | ✅ safe | ❌ break: old readers reject the new value |
| Enum: remove value | ❌ break | ✅ safe |

See the [rules reference](https://chrisjenx.github.io/serialkompat/rules/) for the full rule matrix and how your config changes each verdict. The [deep dive](https://chrisjenx.github.io/serialkompat/deep-dive/) explains extraction, classification, and the git-ref baseline.

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

Requires JDK 17+. Uses the Gradle wrapper (Gradle 9.8.0), Kotlin 2.4.20, and kotlinx-serialization 1.11.0.

## Publishing

`serialkompat-core`, `-extractor`, `-gradle` (with its plugin marker), and `-annotations` publish to **Maven Central** through the [vanniktech `maven-publish`](https://github.com/vanniktech/gradle-maven-publish-plugin) plugin. Gradle Plugin Portal publishing is not configured. Credentials live only in repository secrets:

| Repo secret | Maps to (`ORG_GRADLE_PROJECT_…`) |
|---|---|
| `MAVEN_CENTRAL_USERNAME` / `MAVEN_CENTRAL_PASSWORD` | `mavenCentralUsername` / `mavenCentralPassword` |
| `SIGNING_KEY_ID` / `SIGNING_KEY` / `SIGNING_KEY_PASSWORD` | `signingInMemoryKeyId` / `signingInMemoryKey` / `signingInMemoryKeyPassword` |

**Release.** Dispatch the `Release` workflow from `main` with a version: `X.Y.Z`, or `X.Y.Z-suffix` for a prerelease. It runs these jobs in order:

1. **Validate.** Fails before anything ships if the run isn't on `main`, any of the five secrets is missing, the version is malformed, or tag `vX.Y.Z` already exists.
2. **Test.** `./gradlew build` on JDK 17 and 21, on macOS so the KMP klibs are complete.
3. **Publish.** `publishAndReleaseToMavenCentral`.
4. **Tag and release.** Tags `vX.Y.Z` on the tested commit and creates the GitHub release, marked as a prerelease if the version has a suffix. Stable releases also move the floating major tag that Action users pin (`v0` for 0.x, `v1` for 1.x).
5. **Bump.** Opens a PR moving `gradle.properties` to the next patch `-SNAPSHOT`, because `main` is branch-protected. This needs **Settings → Actions → General → "Allow GitHub Actions to create and approve pull requests"**. Without it the release still completes, and the job warns you to bump the version by hand.

Tagging, the GitHub release, and the bump PR all use the workflow's own `GITHUB_TOKEN`.

**Snapshot.** Pushes to `main` that touch module sources or build files publish a `-SNAPSHOT`. The `Snapshot Publish` workflow refuses to run if `gradle.properties` holds a non-SNAPSHOT version.

Locally, `./gradlew publishToMavenLocal` publishes to `~/.m2`. Signing uses your `signing.*` Gradle properties.

### GitHub Actions Marketplace

`action.yml` already carries the Marketplace metadata (name, description, `branding`). Listing it is a **one-time manual step** after the first stable release, because GitHub can't automate the Marketplace toggle:

1. Cut a stable release with the **Release** workflow. This creates the `vX.Y.Z` release and moves the major tag.
2. On that release's page (**Releases → Edit**), tick **"Publish this Action to the GitHub Marketplace"** and accept the Marketplace Developer Agreement (repo owner, first time only).
3. Pick the primary and secondary categories, then save.

Listing is for discoverability only. `uses: chrisjenx/serialkompat@v0` works as soon as the `v0` tag exists.

## Contributing

Contributions welcome — this project is built test-first. See [CONTRIBUTING.md](CONTRIBUTING.md) and our [Code of Conduct](CODE_OF_CONDUCT.md). Good starting points are issues labelled [`good first issue`](https://github.com/chrisjenx/serialkompat/labels/good%20first%20issue).

## License

[Apache License 2.0](LICENSE) © 2026 Chris Jenkins and serialkompat contributors.
