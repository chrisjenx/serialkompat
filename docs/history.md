# Persisted data & history

Check your current schema against every released version, not just the last one. It is for
teams whose data outlives a release: database rows, queue messages, caches.

## Persisted-data horizon: multi-version history { #persisted-data-horizon-multi-version-history }

Checking against `baselineRef` only catches a break against the *last* version, not against
older ones. Rows in a database or messages in a queue may have been written by any past
version. You need a **transitive** check: the current schema must stay compatible with
**every** release whose data might still exist.

Record each release's schema in an append-only, source-controlled history, and check against
all of it. On each release, record the schema and commit it:

```console
# On each release (from CI or by hand), record the released schema and commit it.
$ ./gradlew serialkompatRecord -Pserialkompat.recordVersion=1.4.0
serialkompat: recorded schema for version '1.4.0' into /path/to/project/serialkompat/history

$ git add serialkompat/history/1.4.0.snapshot && git commit -m "record wire schema 1.4.0"
```

Each entry (`serialkompat/history/<version>.snapshot`) is written once and never changed.
That is what makes the history trustworthy: you can't quietly rewrite it to get past the
gate. The directory is configurable:

```kotlin title="build.gradle.kts"
--8<-- "docs-samples/snippets/history-dir.gradle.kts"
```

`serialkompatCheckHistory` checks the current schema against every recorded version at once.
It fails on a break with **any** of them. A change that is fine against the latest release,
but would break data written by an older one, is still caught:

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

`(backward)` and `(forward)` name the direction that breaks; see
[Backward vs forward](concepts.md#backward-vs-forward).

The history check writes its report to `build/serialkompat/report-history.json` (and
`report-history.sarif` if SARIF is on). It is kept separate from the pairwise `report.json`,
so you always know which check produced which report.

`serialkompatCheckHistory` is wired into `check`, but it does nothing until you record at
least one version. A repo that doesn't use history never sees it fail.

The two checks are independent. `serialkompatCheck` (against `baselineRef`) covers
compatibility between live services. The history check covers persisted data. See the
[glossary](concepts.md#glossary) for pairwise versus history.

!!! note "Recording from the release flow"
    `serialkompatRecord` uses the project `version` by default. It reads `version` when the
    task runs, so you can set it anywhere in your build script. Pass
    `-Pserialkompat.recordVersion=X.Y.Z` to override it. An unversioned project still
    configures fine; only `serialkompatRecord` itself fails without a version. Run it in your
    release job right after publishing, and commit the new
    `serialkompat/history/*.snapshot` file so the next transitive check can use it.

!!! warning "Recording refuses an empty schema"
    Because history is append-only, a bad entry can never be fixed. So `serialkompatRecord`
    fails instead of recording a schema with zero checked types. That includes a schema where
    every type is `OPAQUE`: a placeholder for a type serialkompat couldn't analyse. If it
    refuses, check your `types` or `discovery` configuration.

## Bounding the horizon (retention) { #bounding-the-horizon-retention }

You may not promise compatibility with *every* version you ever shipped. You may promise a
horizon: the last N releases, everything since some version, or everything within a time
window. Set retention bounds in the `history` block:

```kotlin title="build.gradle.kts"
--8<-- "docs-samples/snippets/history-retention.gradle.kts:retention"
```

Set one bound or combine several. Combining is **most-permissive**: a version is checked if
*any* bound keeps it. Adding a second bound can only widen coverage. It never drops a version
another bound still checks. With no bounds set, every recorded version is checked.

When a bound drops versions, the check logs how many it checked out of how many are
recorded. That way "compatible" is never mistaken for "compatible with all history".

The `history.*` options are listed in [Configuration](configuration.md).
