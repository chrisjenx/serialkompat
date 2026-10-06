# Recipes

Each recipe starts with a problem you might have, then shows the fix. For the full
DSL, see [Configuration](configuration.md). To install the CLI or the Action, see
[Setup](setup.md).

## Cross-repo diff with the CLI

**Problem:** two services, or two checkouts of a monorepo, don't share a Gradle
build. They still need to agree on the wire format.

**Fix:** extract a snapshot on each side with the Gradle plugin, then compare the
two files with the standalone CLI. No single Gradle build has to see both sides.

```console
$ ./gradlew serialkompatExtract   # in repo/checkout A -> build/serialkompat/current.snapshot
$ cp build/serialkompat/current.snapshot /tmp/producer.snapshot

$ ./gradlew serialkompatExtract   # in repo/checkout B -> build/serialkompat/current.snapshot
$ cp build/serialkompat/current.snapshot /tmp/consumer.snapshot

$ serialkompat diff /tmp/producer.snapshot /tmp/consumer.snapshot
```

`serialkompat diff <baseline.snapshot> <current.snapshot>` treats the first file as
the old schema and the second as the new one. That matches the Gradle check, where
`baselineRef` is the old side and your current code is the new side.

Useful flags:

- `--direction=BACKWARD|FORWARD|FULL` narrows the check (default `FULL`).
- `--no-fail` prints findings without failing.
- `--format=console|json|sarif|github` picks the output format. For example,
  `--format=sarif > report.sarif` writes a SARIF log, and `--format=github` emits
  inline annotations on a CI runner that doesn't use the Action (see
  [Report formats](report-formats.md)).

Exit codes: `0` ok, `1` breaking, `2` usage error.

## First-time adoption

**Problem:** you turn the gate on and the first run fails. The baseline is
extracted by running your build at `baselineRef`, and that ref doesn't match what
you're checking yet. Which failure you see depends on what the ref has:

- **No serialkompat configuration** (the usual case: the change that adds the
  plugin). The baseline extraction can't run there, so the check fails with a
  baseline-extraction error.
- **An explicit `types` list naming classes that don't exist there yet.** Those
  types are recorded as unanalysable in the baseline, so the check reports them
  as `CONTRACT_REMOVED` (`BREAK`).
- **`OPT_IN` or `OPT_OUT` discovery, but no `@Serializable` types yet.** The
  baseline is empty. serialkompat treats an empty baseline as a misconfiguration
  by default; otherwise every type would look "newly added, therefore safe", and
  a real removal could slip through unnoticed.

**Fix for the first two:** skip the check on the change that introduces
serialkompat (`./gradlew build -x serialkompatCheck`, and leave the GitHub Action
out of that PR). Once it's merged into `baselineRef`, every later change is
checked normally.

**Fix for an empty baseline:** for the run where you expect it, opt out explicitly:

```kotlin title="build.gradle.kts"
serialkompat {
    discovery.set(com.chrisjenx.serialkompat.extractor.DiscoveryMode.OPT_OUT)
    failOnEmptyBaseline.set(false) // only while baselineRef predates these types
}
```

Once `baselineRef` (for example `origin/main`) contains the commit that added these
types, remove the override. `failOnEmptyBaseline` goes back to its default, `true`,
and catches a real misconfiguration again.

## Gradual adoption with discovery modes

**Problem:** you don't know the full set of wire types yet, or you don't want to
commit to all of them at once. Writing an explicit `types` list up front is
friction.

**Fix:** use `discovery` to start with zero checked types and add them one by one.
Depend on the annotations artifact and switch to `OPT_IN`, with no `types` list:

```kotlin title="build.gradle.kts"
dependencies {
    implementation("com.chrisjenx:serialkompat-annotations:{{ skversion }}")
}

serialkompat {
    discovery.set(com.chrisjenx.serialkompat.extractor.DiscoveryMode.OPT_IN)
}
```

Nothing is checked yet. As you review each wire type, annotate it:

```kotlin
@Serializable
@SerialkompatChecked
data class OrderEvent(val id: String)
```

Each newly annotated type joins the gate on its next run. You don't touch the
plugin config or maintain a `types` list by hand.

!!! note "The first annotated type"
    The PR that annotates your *first* type hits the empty-baseline guard from
    [First-time adoption](#first-time-adoption). The baseline on `main` still has
    zero types, so the change looks like "everything was just added". Use the same
    fix: set `failOnEmptyBaseline.set(false)` for that one PR, then remove it once
    `baselineRef` includes it.

When coverage is essentially complete, flip the default. Switch to
`DiscoveryMode.OPT_OUT` so every discovered type is checked, and mark the few
intentionally unstable types with `@SerialkompatIgnore`:

```kotlin title="build.gradle.kts"
serialkompat {
    discovery.set(com.chrisjenx.serialkompat.extractor.DiscoveryMode.OPT_OUT)
}
```

```kotlin
@Serializable
@SerialkompatIgnore // internal scratch type, no cross-version contract
data class DebugDump(val raw: String)
```

Both modes fail safe. `OPT_IN` never checks a type you haven't reviewed. `OPT_OUT`
never skips a type just because you forgot to annotate it. Adding or removing an
annotation takes effect on the next run.

See [Configuration](configuration.md#discovery-modes) for the full mode semantics
and precedence rules.

## Sanctioning a deliberate break

**Problem:** you are making a breaking change on purpose. Maybe it's a major
version bump, or you know every consumer has stopped using a field. The gate fails
the build anyway.

**Fix:** add the finding to `acceptedBreaks`. It moves from failing to
*acknowledged*: it still appears in the report, but no longer fails the build.

```kotlin title="build.gradle.kts"
serialkompat {
    acceptedBreaks.set(listOf(
        "com.example.wire.Payment PROPERTY_REMOVED BACKWARD",
    ))
}
```

The format is `"<serialName> <RULE> [DIRECTION]"`:

- the type's serial name,
- the exact rule ID (see [Rules](rules.md)),
- an optional direction. Omit it to accept the finding in every direction you
  check, or give `BACKWARD` or `FORWARD` to accept it in only one.

An entry only covers findings with that serial name and rule. Every other finding
on the type, or under that rule elsewhere, still fails. The console and JSON
reports keep listing acknowledged findings, counted separately from active ones, so
an accepted break stays visible.

## Monorepo scoping

**Problem:** in a multi-module build, a module gets graded on wire types it doesn't
own.

**Fix:** restrict each module's check to serial-name prefixes with `include` and
`exclude`. Where both match, `exclude` wins:

```kotlin title="modules/orders/build.gradle.kts"
serialkompat {
    types.set(listOf("com.example.wire.OrderEvent"))
    include.set(listOf("com.example.wire.orders"))
    exclude.set(listOf("com.example.wire.orders.internal"))
}
```

`include` defaults to `[""]`, which matches everything. Set it when a module
should ignore types outside its own package. Use `exclude` to carve out an
intentionally unstable subtree (internal-only types with no cross-version
contract) that `include` would otherwise match.

Each module has its own `types`, `baselineRef`, and scope. There's no repo-wide
config, so a change to one module's wire types can't widen or narrow another
module's check.

Multi-module builds work with `--parallel`. Modules take turns extracting their
baselines, so they don't compete for the same git repository.

## Persisted-data horizon: multi-version history

**Problem:** your data outlives a single release. Rows in a database or messages in
a queue may have been written by any past version. Checking against `baselineRef`
only catches a break against the *last* version, not against older ones.

You need a **transitive** check: the current schema must stay compatible with
**every** release whose data might still exist, not just the latest.

**Fix:** record each release's schema in an append-only, source-controlled history,
and check against all of it. On each release, record the schema and commit it:

```console
# On each release (from CI or by hand), record the released schema and commit it.
$ ./gradlew serialkompatRecord -Pserialkompat.recordVersion=1.4.0
serialkompat: recorded schema for version '1.4.0' into /path/to/project/serialkompat/history

$ git add serialkompat/history/1.4.0.snapshot && git commit -m "record wire schema 1.4.0"
```

Each entry (`serialkompat/history/<version>.snapshot`) is written once and never
changed. That is what makes the history trustworthy: you can't quietly rewrite it
to get past the gate. The directory is configurable:

```kotlin title="build.gradle.kts"
serialkompat {
    types.set(listOf("com.example.wire.OrderEvent"))
    history {
        dir.set(layout.projectDirectory.dir("serialkompat/history")) // the default
    }
}
```

`serialkompatCheckHistory` checks the current schema against every recorded
version at once. It fails on a break with **any** of them. A change that is fine
against the latest release, but would break data written by an older one, is
still caught:

```console
$ ./gradlew serialkompatCheckHistory
serialkompat: transitive check vs 3 published version(s).
serialkompat: 2 active finding(s) (2 breaking, 0 warning), 0 acknowledged

  BREAK  PROPERTY_REMOVED  com.example.wire.OrderEvent  (backward)
    field 'note' was removed from com.example.wire.OrderEvent
    fix: Removing a field drops its data for tolerant readers; keep it (or bridge a rename with @JsonNames) until nothing uses it; else bump major.
  BREAK  PROPERTY_REMOVED  com.example.wire.OrderEvent  (forward)
    field 'note' was removed from com.example.wire.OrderEvent
    fix: Removing a field drops its data for tolerant readers; keep it (or bridge a rename with @JsonNames) until nothing uses it; else bump major.
```

The history check writes its report to `build/serialkompat/report-history.json`
(and `report-history.sarif` if SARIF is on). It is kept separate from the pairwise
`report.json`, so you always know which check produced which report.

`serialkompatCheckHistory` is wired into `check`, but it does nothing until you
record at least one version. A repo that doesn't use history never sees it fail.

The two checks are independent. `serialkompatCheck` (against `baselineRef`) covers
compatibility between live services. The history check covers persisted data.

!!! note "Recording from the release flow"
    `serialkompatRecord` uses the project `version` by default. Pass
    `-Pserialkompat.recordVersion=X.Y.Z` to override it. Run it in your release job
    right after publishing, and commit the new `serialkompat/history/*.snapshot`
    file so the next transitive check can use it.

!!! warning "Recording refuses an empty schema"
    Because history is append-only, a bad entry can never be fixed. So
    `serialkompatRecord` fails instead of recording a schema with zero checked
    types. That includes a schema where every type is `OPAQUE`: a placeholder for a
    type serialkompat couldn't analyse. If it refuses, check your `types` or
    `discovery` configuration.

### Bounding the horizon (retention)

**Problem:** you don't promise compatibility with *every* version you ever shipped.
You promise a horizon: the last N releases, everything since some version, or
everything within a time window.

**Fix:** set retention bounds in the `history` block:

```kotlin title="build.gradle.kts"
serialkompat {
    history {
        depth.set(10)                          // only the newest 10 recorded versions
        // or:
        sinceVersion.set("2.0.0")              // only versions >= 2.0.0
        // or:
        maxAge.set(java.time.Duration.ofDays(548)) // only versions recorded in the last ~18 months
    }
}
```

Set one bound or combine several. Combining is **most-permissive**: a version is
checked if *any* bound keeps it. Adding a second bound can only widen coverage. It
never drops a version another bound still checks. With no bounds set, every
recorded version is checked.

When a bound drops versions, the check logs how many it checked out of how many
are recorded. That way "compatible" is never mistaken for "compatible with all
history".

## Next

- [Configuration](configuration.md) — full `serialkompat { }` reference.
- [Deep dive](deep-dive.md) — how extraction, classification, and the git-ref baseline actually work.
