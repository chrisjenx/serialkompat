# serialkompat

[![CI](https://github.com/chrisjenx/serialkompat/actions/workflows/ci.yml/badge.svg)](https://github.com/chrisjenx/serialkompat/actions/workflows/ci.yml)
[![License](https://img.shields.io/badge/license-Apache%202.0-blue.svg)](LICENSE)
[![Kotlin](https://img.shields.io/badge/kotlin-2.3-blue.svg?logo=kotlin)](https://kotlinlang.org)

**A backward/forward compatibility gate for [kotlinx-serialization](https://github.com/Kotlin/kotlinx.serialization) `@Serializable` models — like [`buf breaking`](https://buf.build/docs/breaking/), but for JSON.**

> 🚧 **Status: early development.** The design is settled ([docs/design](docs/design)) and the project is being built in the open, one reviewed PR at a time. The public API is not yet published. Watch the [issues](https://github.com/chrisjenx/serialkompat/issues) and [milestones](https://github.com/chrisjenx/serialkompat/milestones) to follow along.

## Why

`kotlinx-serialization-json` is a joy to use, but it gives you **no safety net for schema evolution**. Rename a property, drop a default, make a field non-null — and you may have silently broken every old client still sending the old shape, or made years of persisted JSON undecodable. Today the only defence is a pile of hand-written round-trip tests.

`serialkompat` makes wire-compatibility a **gate**: it reads the JSON schema straight out of your `@Serializable` models, compares it against a baseline, and fails CI on backward/forward-incompatible changes — with the rules grounded in how kotlinx-serialization *actually* behaves.

## How it works

```
@Serializable  ─▶  Extractor  ─▶  Snapshot  ─▶  Differ  ─▶  Classifier  ─▶  Report
   types          (runtime        (canonical    (deltas)    (rules +        (findings
                   descriptor       model)                    severity)       + exit code)
                   walk, JVM)
```

- **Extraction** walks the compiled `SerialDescriptor` graph, so it sees exactly what goes on the wire — real JSON keys (post-`@SerialName`/`namingStrategy`), optionality (`isElementOptional`), nullability, enums, and `SerializersModule`-resolved polymorphism.
- **Baseline** is extracted **live from a git ref** (e.g. your target branch) — there is no hand-maintained baseline file to forget to update or accidentally overwrite. (An append-only published schema history for long-horizon persisted-data checks is on the roadmap.)
- **Classification** is direction-aware (`BACKWARD` / `FORWARD` / `FULL`) and **config-aware** — it reads your actual `Json { }` settings, because whether a change is safe depends on `ignoreUnknownKeys`, `namingStrategy`, `encodeDefaults`, and friends.
- **Every rule is verified against real kotlinx-serialization** via a round-trip oracle: serialize with the old model, decode with the new one, and assert the classifier predicted what actually happened.

## Intended usage (target API — not yet published)

```kotlin
// build.gradle.kts of a module holding @Serializable wire/persisted contracts
plugins {
    kotlin("multiplatform")
    kotlin("plugin.serialization")
    id("io.github.chrisjenx.serialkompat") version "0.1.0"
}

serialkompat {
    json      = "com.example.wire.WireJson.instance" // reads your real Json { } config
    direction = FULL
    baseline  = gitRef("origin/main")
    failOn    = BREAKING
}
```

```console
$ ./gradlew serialkompatCheck
> OrderEvent.note → renamed to 'memo'  [PROPERTY_NO_DELETE, BACKWARD, BREAK]
  Old payloads use "note"; new code can't read them.
  Fix: add @JsonNames("note"), or record an accepted break.
```

`serialkompatCheck` wires into the `check` lifecycle, so it runs on every build and on CI — nothing to remember.

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

See the [design doc](docs/design) for the full rule matrix and semantics.

## Modules

| Module | What |
|---|---|
| `serialkompat-core` | Pure-Kotlin `Snapshot` model, differ, classifier, rule set, report. No I/O. |
| `serialkompat-extractor` | Runtime `SerialDescriptor` → `Snapshot` extraction (JVM). |
| `serialkompat-gradle` | The Gradle plugin (`serialkompatCheck`). |

## Building

```console
./gradlew build          # compile, test, format check (spotless), API check (BCV)
./gradlew spotlessApply  # auto-format
./gradlew apiDump        # update public-API baselines after an intended API change
./gradlew koverHtmlReport
```

Requires JDK 17+. Uses the Gradle wrapper (Gradle 9.6.1), Kotlin 2.3.

## Contributing

Contributions welcome — this project is built test-first. See [CONTRIBUTING.md](CONTRIBUTING.md) and our [Code of Conduct](CODE_OF_CONDUCT.md). Good starting points are issues labelled [`good first issue`](https://github.com/chrisjenx/serialkompat/labels/good%20first%20issue).

## License

[Apache License 2.0](LICENSE) © 2026 Chris Jenkins and serialkompat contributors.
