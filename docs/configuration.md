# Configuration

This page lists every option in the `serialkompat { }` block, with its default and
what it does. For the minimal setup, see [Quick start](quickstart.md). For the CLI
and GitHub Action, see [Setup](setup.md).

## `serialkompat { }` reference

| Property | Type | Default | Purpose |
|---|---|---|---|
| `types` | `ListProperty<String>` | `[]` (required under `EXPLICIT` discovery) | FQNs of `@Serializable` root types to check |
| `discovery` | `Property<DiscoveryMode>` | `EXPLICIT` | How checked types are found when `types` is empty: `EXPLICIT` (only `types`), `OPT_OUT` (everything discovered minus `@SerialkompatIgnore`), `OPT_IN` (only `@SerialkompatChecked`) |
| `jsonInstance` | `Property<String>` | unset | FQN of a `Json` instance describing the wire (e.g. `com.example.WireJson.instance`); unset = default `Json` |
| `baselineRef` | `Property<String>` | auto-detected | Git ref the current schema is checked against. Unset ⇒ auto-detect the default branch (`origin/HEAD` → `origin/main` → `origin/master` → local `main`/`master`) |
| `direction` | `Property<CompatibilityDirection>` | `FULL` | `BACKWARD`, `FORWARD`, or `FULL` |
| `failOnBreaking` | `Property<Boolean>` | `true` | A `BREAK` finding fails the build |
| `failOnEmptyBaseline` | `Property<Boolean>` | `true` | Empty baseline fails the build (prevents silently masking removed types); set `false` for first adoption |
| `include` | `ListProperty<String>` | `[""]` | Serial-name prefixes in scope (`""` = all) |
| `exclude` | `ListProperty<String>` | `[]` | Serial-name prefixes excluded |
| `acceptedBreaks` | `ListProperty<String>` | `[]` | Sanctioned breaks, format `"<serialName> <RULE> [DIRECTION]"` |
| `renames` | `MapProperty<String,String>` | `{}` | Declared serial-name moves old→new (avoids a remove+add pair reading as a break) |
| `history.dir` | `DirectoryProperty` | `serialkompat/history` | Source-controlled dir of recorded per-version snapshots for the transitive check ([Recipes](recipes.md#persisted-data-horizon-multi-version-history)) |
| `history.sinceVersion` | `Property<String>` | unset | Retention: only check against versions `>=` this (semver) |
| `history.depth` | `Property<Int>` | unset | Retention: only check against the newest N recorded versions (unset or `<= 0` = no limit) |
| `history.maxAge` | `Property<Duration>` | unset | Retention: only check against versions recorded within this window. Combining bounds is most-permissive (union) |
| `reports.json.required` | `Property<Boolean>` | `true` | Write the JSON report ([Report formats](report-formats.md)) |
| `reports.json.outputLocation` | `RegularFileProperty` | `build/serialkompat/report.json` | Where the JSON report is written |
| `reports.sarif.required` | `Property<Boolean>` | `false` | Write the SARIF 2.1.0 report |
| `reports.sarif.outputLocation` | `RegularFileProperty` | `build/serialkompat/report.sarif` | Where the SARIF report is written |

A few terms used on this page:

- **Baseline:** the "old" schema you compare against. serialkompat doesn't store it
  in a file. It checks out `baselineRef` in a temporary git worktree and extracts the
  schema from there on every run, so there is nothing to regenerate or let go stale.
- **Backward compatible:** new code can read data written by old code.
- **Forward compatible:** old code can read data written by new code.

The extension works with the configuration cache.

!!! note "Which tasks use which options"
    `renames` and `failOnEmptyBaseline` apply only to the pairwise check
    (`serialkompatCheck` / `serialkompatCheckAgainst`). The transitive history
    check (`serialkompatCheckHistory`) ignores both.

## Discovery modes

`discovery` only matters when `types` is empty. It decides which of the
discovered `@Serializable` types get checked:

| Mode | Checked types | Use when |
|---|---|---|
| `EXPLICIT` (default) | Only `types` | You maintain an explicit list of root types |
| `OPT_OUT` | Everything discovered, minus types annotated `@SerialkompatIgnore` | Most types are wire contracts; a few (internal-only, unstable) opt out |
| `OPT_IN` | Only types annotated `@SerialkompatChecked` | Gradual adoption. Nothing is checked until you annotate it |

Discovery scans your module's compiled classes for class-level `@Serializable`.
The annotations live in a small multiplatform artifact:

```kotlin
dependencies {
    implementation("com.chrisjenx:serialkompat-annotations:{{ skversion }}")
}
```

Put `com.chrisjenx.serialkompat.annotations.SerialkompatIgnore` or
`com.chrisjenx.serialkompat.annotations.SerialkompatChecked` on the
`@Serializable` class itself:

```kotlin
@Serializable
@SerialkompatChecked
data class OrderEvent(val id: String)
```

**Precedence.** These rules apply in this order, in every mode:

1. A non-empty `types` list always wins. `discovery` is only consulted when
   `types` is empty.
2. Annotations filter the **scanned** set only. Types listed in a classpath
   manifest (`META-INF/serialkompat/serializable-types.txt`) skip annotation
   filtering and are always included in `OPT_OUT` and `OPT_IN`.
3. `include` and `exclude` prefixes apply after discovery, in all modes.

```kotlin title="build.gradle.kts"
serialkompat {
    discovery.set(com.chrisjenx.serialkompat.extractor.DiscoveryMode.OPT_OUT)
}
```

**Kotlin Multiplatform:** a KMP module works with discovery and extraction as long
as it declares a `jvm()` target. Extraction reads compiled JVM serializers, so a JVM
target is required. Annotate your models in `commonMain`. `serialkompat-annotations`
is itself multiplatform, so the annotations are available there.

## Annotated example

!!! note
    serialkompat isn't on the Gradle Plugin Portal yet, so `plugins { id(…) }` won't
    resolve on its own. See [Setup](setup.md#gradle-plugin) for the `pluginManagement`
    block that points Gradle at Maven Central.

```kotlin title="build.gradle.kts"
import com.chrisjenx.serialkompat.core.CompatibilityDirection

plugins {
    kotlin("jvm")
    kotlin("plugin.serialization")
    id("com.chrisjenx.serialkompat") version "{{ skversion }}"
}

serialkompat {
    types.set(listOf( // (1)!
        "com.example.wire.OrderEvent",
        "com.example.wire.Payment",
    ))
    jsonInstance.set("com.example.wire.WireJson.instance") // (2)!
    baselineRef.set("origin/main") // (3)!
    direction.set(CompatibilityDirection.FULL) // (4)!
    failOnBreaking.set(true) // (5)!
    failOnEmptyBaseline.set(true) // (6)!
    include.set(listOf("com.example.wire")) // (7)!
    exclude.set(listOf("com.example.wire.internal")) // (8)!
    renames.put("com.example.wire.LegacyOrder", "com.example.wire.OrderEvent") // (9)!
    acceptedBreaks.set(listOf( // (10)!
        "com.example.wire.Payment PROPERTY_REMOVED BACKWARD",
    ))
}
```

1. The root types to check. Nested types and sealed subtypes reachable from these
   are included automatically, so you don't list every type in the graph.
2. Points at a `Json { ... }` instance in your code. The classifier then judges
   changes against your *actual* wire config (`ignoreUnknownKeys`,
   `encodeDefaults`, `explicitNulls`, and so on), not kotlinx-serialization's
   defaults. The instance must be reachable on the module's runtime classpath. If
   it can't be loaded, serialkompat prints a warning and falls back to the default
   `Json` config. Leave it unset only if you really serialize with a plain `Json`.
   A per-property `@EncodeDefault` overrides `encodeDefaults`, and serialkompat
   reads it from your compiled classes (see [Rules](rules.md)).
3. The git ref whose schema is the baseline. Any ref `git` resolves works: a
   branch, tag, or commit SHA. **Optional.** Leave it unset and serialkompat
   auto-detects your default branch. It tries `origin/HEAD`, then
   `origin/main`/`origin/master`, then a local `main`/`master`, so a repo whose
   default branch is `master` works without configuration. Set it to pin a
   specific ref. To override it for one run without editing this file, use the
   `serialkompatCheckAgainst` task with `-Pserialkompat.ref=<ref>`.
   (`serialkompatCheck` ignores that property.)
4. See [Choosing a direction](#choosing-a-direction) below.
5. When `false`, `BREAK` findings are reported but don't fail the build. This is
   useful for a short audit period. It isn't recommended long-term.
6. Guards against a silent no-op. If the baseline comes back with no types (wrong
   ref, types not on that ref yet), that is almost always a misconfiguration, not
   "everything is compatible". Set `false` only while adopting serialkompat, when
   the baseline ref really predates these types.
7. Limits the check to serial names that start with this prefix. The default,
   `[""]` (an empty string), matches everything.
8. Prefixes to drop even when they match `include`. `exclude` wins. Use it for
   intentionally unstable types (internal-only, no cross-version contract).
9. Declares that the serial name `LegacyOrder` became `OrderEvent`. serialkompat
   then compares the two types' contents. Without this, the differ sees the old
   type removed (`CONTRACT_REMOVED`, a `BREAK`) and an unrelated new type added.
10. The format is `"<serialName> <RULE> [DIRECTION]"`. `DIRECTION` is optional.
    Omit it to accept the break in every direction you check, or give `BACKWARD`
    or `FORWARD` to accept it in only one. Each entry matches only findings with
    that serial name and rule. Other findings on the same type still fail.

## Choosing a direction

`direction` tells the classifier which reader/writer pairing must keep working
after the change. Pick it from how the schema is actually used:

| Direction | Guarantees | Use when |
|---|---|---|
| `BACKWARD` | New code can read data written by old code | Rolling deploys — a newer service version must decode messages/events produced by instances still running the old version |
| `FORWARD` | Old code can read data written by new code | Persisted data with slow migrations, or mixed-version consumers — an older reader (a replica, a cached job, a client that hasn't upgraded yet) must decode records a newer writer just produced |
| `FULL` | Both | Public APIs, shared wire formats, or persisted data with no controlled rollout order — the safest default when you don't control both ends |

`FULL` is the default. Keep it unless you know only one direction matters.

You need `FULL` for a queue whose producers and consumers deploy independently, or
for long-lived stored rows that code from any past version may read. A rolling
deploy of a single service, where old instances drain within minutes, only needs
`BACKWARD` for that window.

!!! warning
    `BACKWARD` and `FORWARD` each drop half the guarantee. Choose one deliberately,
    never as a way to silence findings.

## Report formats

serialkompat writes a JSON report by default. Use the nested `reports { }` block to
turn on SARIF (for IDEs and dashboards) or to move the output files:

```kotlin title="build.gradle.kts"
serialkompat {
  reports {
    json { required.set(true) }               // default -> build/serialkompat/report.json
    sarif { required.set(true) }              // opt-in  -> build/serialkompat/report.sarif
  }
}
```

Each format also has an `outputLocation` you can set, for example
`json { outputLocation.set(layout.buildDirectory.file("reports/serialkompat.json")) }`.

The block applies to the pairwise `serialkompatCheck` and `serialkompatCheckAgainst`.
The history check follows the same `required` switches, but always writes to its own
fixed paths: `build/serialkompat/report-history.json` and `report-history.sarif`.

See [Report formats](report-formats.md) for the JSON schema, SARIF details, GitHub
annotations, and the CLI `--format` equivalent.

## Next

- [Rules](rules.md) — the full rule table each `direction` and `Json` config draws from.
- [CI setup](ci.md) — wiring the check (and `-Pserialkompat.ref`) into a pipeline.
- [Report formats](report-formats.md) — the JSON schema, SARIF, and GitHub annotations, and how to select them.
