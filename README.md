# serialkompat

[![CI](https://github.com/chrisjenx/serialkompat/actions/workflows/ci.yml/badge.svg)](https://github.com/chrisjenx/serialkompat/actions/workflows/ci.yml)
[![Docs](https://img.shields.io/badge/docs-chrisjenx.github.io-blue.svg)](https://chrisjenx.github.io/serialkompat/)
[![License](https://img.shields.io/badge/license-Apache%202.0-blue.svg)](LICENSE)
[![Kotlin](https://img.shields.io/badge/kotlin-2.4-blue.svg?logo=kotlin)](https://kotlinlang.org)

**Catch breaking JSON and CBOR changes before they ship.** A CI gate for [kotlinx-serialization](https://github.com/Kotlin/kotlinx.serialization) `@Serializable` models, like [`buf breaking`](https://buf.build/docs/breaking/) but for JSON and [CBOR](https://chrisjenx.github.io/serialkompat/cbor/).

1. **You change a model.** Rename a field, drop a default, add a required property.
2. **Old data is still out there.** Older app versions, queues, caches and databases hold the previous shape.
3. **The PR fails, with a fix.** serialkompat diffs against your target branch and says what breaks and how to fix it.

```diff
 @Serializable
 data class Order(
     val id: String,
     val amountCents: Long,
-    val note: String? = null,
 )
```

```console
$ ./gradlew serialkompatCheck
serialkompat: 1 active finding(s) (1 breaking, 0 warning), 0 acknowledged

  BREAK  PROPERTY_REMOVED  com.example.Order  (backward)
    field 'note' was removed from com.example.Order
    fix: Removing a field drops its data for tolerant readers; keep it (or bridge a rename with @JsonNames) until nothing uses it; else bump major.

BUILD FAILED
```

- **No baseline file.** The old schema is extracted live from your target branch on every run.
- **Both directions.** New code reading old data, and old code reading new data.
- **Proven, not guessed.** Every rule is round-trip tested against real kotlinx-serialization, under your `Json { }` / `Cbor { }` config.

Not a runtime validator, migration tool or JSON-schema linter.

## Install

The plugin is on the [Gradle Plugin Portal](https://plugins.gradle.org/plugin/com.chrisjenx.serialkompat), so no repository setup is needed. In the module that holds your `@Serializable` models:

```kotlin
// build.gradle.kts
plugins {
    kotlin("jvm")
    kotlin("plugin.serialization")
    id("com.chrisjenx.serialkompat") version "0.3.1"
}

serialkompat {
    types.set(listOf("com.example.Order"))  // root @Serializable types to guard
}
```

`serialkompatCheck` runs as part of `./gradlew check`. The [quick start](https://chrisjenx.github.io/serialkompat/quickstart/) walks through the first PR (the check has to be skipped on the PR that adds the plugin).

## How it works

1. Your PR runs `serialkompatCheck`.
2. It extracts the **old schema** from your target branch (`baselineRef`) in a temporary git worktree.
3. It extracts the **new schema** from your compiled classes, by reading each serializer's own `SerialDescriptor`.
4. It **diffs** the two.
5. It **classifies** each change per direction (backward and forward) under your `Json { }` or `Cbor { }` config.
6. Every rule is **proven** by an oracle test against real kotlinx-serialization.

Details: [Architecture](https://chrisjenx.github.io/serialkompat/architecture/).

## Docs

| | |
|---|---|
| [Quick start](https://chrisjenx.github.io/serialkompat/quickstart/) | Add the plugin, merge, read the report |
| [Installation](https://chrisjenx.github.io/serialkompat/installation/) | Multi-module, KMP, and the standalone CLI |
| [Concepts](https://chrisjenx.github.io/serialkompat/concepts/) | Baseline, backward vs forward, BREAK vs WARN |
| [GitHub Action](https://chrisjenx.github.io/serialkompat/ci/) | PR comments and annotations |
| [Got a BREAK?](https://chrisjenx.github.io/serialkompat/got-a-break/) | Fix, rename or accept it |
| [Adopting gradually](https://chrisjenx.github.io/serialkompat/adopting/) | Roll out on an existing codebase |
| [Configuration](https://chrisjenx.github.io/serialkompat/configuration/) | Every option |
| [Tasks & CLI](https://chrisjenx.github.io/serialkompat/tasks-cli/) | Tasks, `-P` properties, CLI flags, exit codes |
| [Rules](https://chrisjenx.github.io/serialkompat/rules/) | What counts as breaking, per direction |
| [Report formats](https://chrisjenx.github.io/serialkompat/report-formats/) | Console, JSON, SARIF, GitHub |
| [CBOR](https://chrisjenx.github.io/serialkompat/cbor/) | The same gate for CBOR |
| [Troubleshooting](https://chrisjenx.github.io/serialkompat/troubleshooting/) | Symptom, cause, fix |

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
./gradlew build          # compile, test, spotless, API check (BCV), docs gates, docs-sample tests
./gradlew spotlessApply  # auto-format
./gradlew apiDump        # update public-API baselines after an intended API change
./gradlew koverHtmlReport
```

Requires JDK 17+. Toolchain versions are pinned in `gradle/wrapper/gradle-wrapper.properties` and `gradle/libs.versions.toml`. Maintainers: see [RELEASING.md](RELEASING.md).

## Contributing

Contributions welcome — this project is built test-first. See [CONTRIBUTING.md](CONTRIBUTING.md) and our [Code of Conduct](CODE_OF_CONDUCT.md). Good starting points are issues labelled [`good first issue`](https://github.com/chrisjenx/serialkompat/labels/good%20first%20issue).

## License

[Apache License 2.0](LICENSE) © 2026 Chris Jenkins and serialkompat contributors.
