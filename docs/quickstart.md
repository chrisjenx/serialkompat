# Quick start

Apply the plugin, run `serialkompatCheck`, read a report. Under five minutes.

## 1. Apply the plugin

serialkompat isn't on the Gradle Plugin Portal yet — `plugins { id(...) version "..." }`
won't resolve. Until it is, add Maven Central as a plugin repository and resolve
the plugin by version there:

```kotlin title="settings.gradle.kts"
pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral() // (1)!
    }
}
```

1. Required until serialkompat is published to the Gradle Plugin Portal — see
   [Setup → Gradle plugin](setup.md#gradle-plugin) for the full resolution note.

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

1. FQNs of the `@Serializable` root types to check. Everything reachable from
   these (nested types, sealed subtypes) is walked automatically.

## 2. Run the check

```console
$ ./gradlew serialkompatCheck
```

`serialkompatCheck` is wired into `check`, so `./gradlew build` runs it too. It:

1. Extracts the current wire schema from your compiled `@Serializable` types.
2. Extracts the baseline schema live from `baselineRef` (default `origin/main`)
   via a temporary git worktree — no baseline file to maintain.
3. Diffs the two, classifies every change against real kotlinx-serialization
   behavior, and fails the build if anything is `BREAK` (see
   [exit codes](#exit-codes) below).

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

Reading a finding, top to bottom:

| Part | Meaning |
|---|---|
| `BREAK` / `WARN` | Severity — `BREAK` fails the gate, `WARN` is config-dependent or a silent semantic change |
| `PROPERTY_REMOVED` / `CONFIG_READER_STRICTNESS` | The rule that fired — see [Rules](rules.md) |
| `com.example.Order` / `Json config` | The contract (type, or the shared `Json` config) the finding is about |
| `(backward)` | The direction that broke — new code reading old data, old data reading new code, or both |
| indented line 1 | What changed, in plain language |
| `fix:` | A concrete suggestion for resolving or living with the break |

The full machine-readable version lands at `build/serialkompat/report.json`
(`{summary, findings: [...]}`) — useful for custom CI annotations or the
[GitHub Action](setup.md#github-action)'s sticky PR comment.

## Exit codes

| Code | Meaning |
|---|---|
| `0` | No breaking findings (there may still be `WARN`s) |
| `1` | At least one active `BREAK` finding |
| `2` | Usage error (bad config, unreadable snapshot, etc.) |

## Next

- [Setup](setup.md) — the CLI and GitHub Action paths, plus the full `serialkompat { }` DSL.
- [Rules](rules.md) — every rule, what it detects, and how config flips the verdict.
- [Configuration](configuration.md) — direction, accepted breaks, renames, scoping.
