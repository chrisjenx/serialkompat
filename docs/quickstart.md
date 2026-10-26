# Quick start

Add the plugin, merge that first PR, then run `serialkompatCheck` and read the report.
It takes about five minutes, and it's for any Gradle project with `@Serializable`
models. The check covers [backward (new code reads old data)](concepts.md#backward-vs-forward)
and [forward (old code reads new data)](concepts.md#backward-vs-forward) compatibility.

## 1. Your first PR { #first-time-adoption }

serialkompat is on the Gradle Plugin Portal, so no repository setup is needed. Apply
the plugin in the module that holds your `@Serializable` models, and list the types to
check:

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

!!! warning "The check fails on the PR that adds serialkompat"
    The [baseline](concepts.md#the-baseline) is extracted by running your build *at*
    `baselineRef`, and that ref doesn't have the plugin yet. Skip the check for this one
    change (`./gradlew build -x serialkompatCheck`, and leave the GitHub Action out of
    that PR) and merge it. The gate then works on every later change.

Which failure you see on a first run depends on what the baseline ref has:

- **No serialkompat configuration** (the usual case: the change that adds the plugin).
  The baseline extraction can't run there, so the check fails with a baseline-extraction
  error. Skip the check, as above.
- **An explicit `types` list naming classes that don't exist there yet.** Those types
  are recorded as unanalysable in the baseline, so the check reports them as
  `CONTRACT_REMOVED` (`BREAK`). Skip the check, as above.
- **`OPT_IN` or `OPT_OUT` discovery, but no `@Serializable` types yet.** The baseline is
  empty. serialkompat treats an empty baseline as a misconfiguration by default;
  otherwise every type would look "newly added, therefore safe", and a real removal
  could slip through unnoticed.

For an empty baseline, opt out explicitly for the run where you expect it:

```kotlin title="build.gradle.kts"
serialkompat {
    discovery.set(com.chrisjenx.serialkompat.extractor.DiscoveryMode.OPT_OUT)
    failOnEmptyBaseline.set(false) // only while baselineRef predates these types
}
```

Once `baselineRef` (for example `origin/main`) contains the commit that added these
types, remove the override. `failOnEmptyBaseline` goes back to its default, `true`, and
catches a real misconfiguration again. The [CLI](tasks-cli.md#cli) has the same guard,
with `--allow-empty-baseline`.

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
| `BREAK` / `WARN` | Severity. `BREAK` fails the gate. `WARN` means the outcome depends on config, or decoding succeeds but the data silently changes. See [BREAK vs WARN](concepts.md#break-vs-warn) |
| `PROPERTY_REMOVED` / `CONFIG_READER_STRICTNESS` | The rule that fired. See [Rules](rules.md) |
| `com.example.Order` / `Json config` | What the finding is about: a type, or the shared `Json` config |
| `(backward)` | The direction that broke. `backward` means new code reading old data; `forward` means old code reading new data |
| indented line 1 | What changed, in plain language |
| `fix:` | A concrete suggestion for resolving or living with the break |

The same report is written as JSON to `build/serialkompat/report.json`. It is a
versioned document (`{schemaVersion, summary, findings: [...]}`) that tooling can
depend on. It can also be rendered as SARIF or GitHub annotations; see
[Report formats](report-formats.md).

Under Gradle, a `BREAK` fails the build and `WARN`s don't. The CLI's exit codes are in
[Tasks & CLI](tasks-cli.md#exit-codes).

## Next

- [Installation](installation.md): the GitHub Action and the CLI.
- [Concepts](concepts.md): the baseline, direction, and `BREAK` versus `WARN`.
- [Got a BREAK?](got-a-break.md): what to do when the gate fails.
- [Rules](rules.md): every rule, what it detects, and how config changes the verdict.
- [Configuration](configuration.md): direction, accepted breaks, renames, and scoping.
- [Report formats](report-formats.md): the JSON schema, SARIF, and GitHub annotations.
