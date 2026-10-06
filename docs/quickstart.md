# Quick start

Apply the plugin, run `serialkompatCheck`, and read the report. It takes about five
minutes.

## 1. Apply the plugin

serialkompat is published to Maven Central, not the Gradle Plugin Portal. Add Maven
Central to your plugin repositories so Gradle can resolve the plugin:

```kotlin title="settings.gradle.kts"
pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral() // (1)!
    }
}
```

1. Required: without it, `id("com.chrisjenx.serialkompat")` won't resolve. See
   [Setup → Gradle plugin](setup.md#gradle-plugin).

Then apply the plugin in the module that holds your `@Serializable` models, and list
the types to check:

```kotlin title="build.gradle.kts"
plugins {
    kotlin("jvm")
    kotlin("plugin.serialization")
    id("com.chrisjenx.serialkompat") version "{{ skversion }}"
}

serialkompat {
    types.set(listOf("com.example.Order")) // (1)!
}
```

1. Fully-qualified names of the `@Serializable` root types to check. Everything
   reachable from them, such as nested types and sealed subtypes, is checked too.

## 2. Run the check

```console
$ ./gradlew serialkompatCheck
```

`serialkompatCheck` is wired into `check`, so `./gradlew build` runs it too. It:

1. Extracts the current wire schema from your compiled `@Serializable` types.
2. Extracts the **baseline**, the schema you compare against. It checks out
   `baselineRef` in a temporary git worktree and runs the extraction there. If you
   don't set `baselineRef`, it uses your repository's default branch.
3. Diffs the two schemas, classifies every change against real kotlinx-serialization
   behavior, and fails the build on any `BREAK` finding.

!!! note "Your first run"
    The baseline is extracted by running your build *at* `baselineRef`, so the plugin
    must already be applied and configured on that ref. On the change that first adds
    serialkompat, the baseline ref doesn't have it yet, so the baseline extraction
    fails. Skip the check for that one change (`./gradlew build -x serialkompatCheck`,
    and leave the GitHub Action out of that PR) and merge it. The gate then works on
    every later change. See
    [First-time adoption](recipes.md#first-time-adoption) for the details.

## 3. Read the report

A breaking change prints a console report like this:

```text
serialkompat: 2 active finding(s) (1 breaking, 1 warning), 0 acknowledged

  BREAK  PROPERTY_REMOVED  com.example.Order  (backward)
    field 'note' was removed from com.example.Order
    fix: Removing a field drops its data for tolerant readers; keep it (or bridge a rename with @JsonNames) until nothing uses it; else bump major.

  WARN  CONFIG_READER_STRICTNESS  Json config  (backward)
    Json ignoreUnknownKeys changed true -> false
    fix: A stricter reader now rejects previously-tolerated unknown keys.
```

Each finding reads top to bottom:

| Part | Meaning |
|---|---|
| `BREAK` / `WARN` | Severity. `BREAK` fails the gate. `WARN` means the outcome depends on config, or decoding succeeds but the data silently changes |
| `PROPERTY_REMOVED` / `CONFIG_READER_STRICTNESS` | The rule that fired. See [Rules](rules.md) |
| `com.example.Order` / `Json config` | What the finding is about: a type, or the shared `Json` config |
| `(backward)` | The direction that broke. `backward` means new code reading old data; `forward` means old code reading new data |
| indented line 1 | What changed, in plain language |
| `fix:` | A concrete suggestion for resolving or living with the break |

The same report is written as JSON to `build/serialkompat/report.json`. It is a
versioned document (`{schemaVersion, summary, findings: [...]}`) that tooling can
depend on. It can also be rendered as SARIF or GitHub annotations; see
[Report formats](report-formats.md).

## Exit codes

Under Gradle, `serialkompatCheck` passes when there are no active `BREAK` findings
(`WARN`s don't fail it). An active `BREAK` fails the task, so the build exits
non-zero.

The standalone [CLI](setup.md#cli) has a finer-grained contract:

| Code | Meaning |
|---|---|
| `0` | No breaking findings (there may still be `WARN`s) |
| `1` | At least one active `BREAK` finding |
| `2` | Usage error (bad arguments, unreadable snapshot, etc.) |

## Next

- [Setup](setup.md): the CLI and GitHub Action, plus the main `serialkompat { }` options.
- [Rules](rules.md): every rule, what it detects, and how config changes the verdict.
- [Configuration](configuration.md): direction, accepted breaks, renames, and scoping.
- [Report formats](report-formats.md): the JSON schema, SARIF, and GitHub annotations.
