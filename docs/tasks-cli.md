# Tasks & CLI

Every Gradle task, the standalone CLI and its flags, and the exit codes, in one place.
This is a reference for people who already have serialkompat
[installed](installation.md).

## Gradle tasks { #tasks }

| Task | What it does | Wired into `check`? | Cacheable? |
|---|---|---|---|
| `serialkompatExtract` | Extracts the current schema to `build/serialkompat/current.snapshot` | Indirectly: every other task depends on it | Yes (build-cacheable, relocatable) |
| `serialkompatCheck` | Extracts, then diffs against `baselineRef`. Fails on an active `BREAK` | Yes | No, never cached on purpose |
| `serialkompatCheckAgainst` | Same as `serialkompatCheck`, but the ref can be overridden with `-Pserialkompat.ref=<ref>` | No, run it ad hoc | No |
| `serialkompatRecord` | Records the current schema into the published history (`-Pserialkompat.recordVersion=X.Y.Z`). See [Persisted data & history](history.md) | No, an explicit release-time step | No |
| `serialkompatCheckHistory` | Checks the current schema against every recorded version | Yes, but skipped until a version is recorded | No |

If neither `types` nor a non-`EXPLICIT` `discovery` mode is set, every serialkompat task
is skipped, so applying the plugin never breaks `check` on its own.

The plugin supports the configuration cache, `--parallel`, and Isolated Projects. The
check tasks have no cacheable outputs, so they always run. See
[Baseline & caching → Build cache & configuration cache](baseline-caching.md#build-cache-configuration-cache).

## Gradle properties { #properties }

| Property | Used by | Effect |
|---|---|---|
| `-Pserialkompat.ref=<ref>` | `serialkompatCheckAgainst` | Baseline ref for this run. `serialkompatCheck` ignores it |
| `-Pserialkompat.recordVersion=X.Y.Z` | `serialkompatRecord` | Version to record; defaults to the project `version` |

## CLI { #cli }

The CLI diffs two snapshot files without a Gradle build in the loop. To build and run
it, see [Installation → CLI](installation.md#cli).

```text
serialkompat diff <baseline.snapshot> <current.snapshot> [--direction=FULL|BACKWARD|FORWARD] [--format=console|json|sarif|github] [--no-fail] [--allow-empty-baseline]
```

The first file is the old schema and the second is the new one. That matches the Gradle
check, where `baselineRef` is the old side and your current code is the new side.

| Flag | Effect |
|---|---|
| `--direction=FULL\|BACKWARD\|FORWARD` | Compatibility [direction](concepts.md#backward-vs-forward) to enforce (default `FULL`) |
| `--format=console\|json\|sarif\|github` | Output format for the report (default `console`). See [Report formats](report-formats.md) |
| `--no-fail` | Exit `0` even if the diff finds breaking changes (the report still prints) |
| `--allow-empty-baseline` | Accept a baseline with no contracts. Use it only for first-time adoption |
| `--help`, `-h` | Print usage and exit `0` |

For example, `serialkompat diff a.snapshot b.snapshot --format=sarif > report.sarif`
writes a SARIF 2.1.0 log. `--format` only changes what is printed, never the exit code.

The CLI never crashes on bad input. A missing file, an unknown flag, or an invalid
`--direction` prints `error: <message>` and the usage line, then exits `2`.

The CLI also fails closed on an empty baseline. If the baseline file has no contracts
but the current one has some, the diff would read as "everything was added" and hide
every removal. So the CLI prints an `error:` line, renders no report, and exits `2`.
`--no-fail` doesn't skip this check. Pass `--allow-empty-baseline` when an empty
baseline is expected, such as on [first-time adoption](quickstart.md#first-time-adoption).
Two empty snapshots still pass.

## Exit codes { #exit-codes }

The two tools signal results differently.

| Outcome | Gradle (`serialkompatCheck`, `serialkompatCheckAgainst`, `serialkompatCheckHistory`) | CLI (`serialkompat diff`) |
|---|---|---|
| No active `BREAK` (there may be `WARN`s) | Task passes | Exit `0` |
| At least one active `BREAK` | Task fails, so the build exits non-zero | Exit `1` (exit `0` with `--no-fail`) |
| Usage error (bad arguments, unreadable snapshot) | Not applicable | Exit `2` |
| Empty baseline, current has contracts | Pairwise checks fail unless `failOnEmptyBaseline` is off (see [Configuration](configuration.md)) | Exit `2` unless `--allow-empty-baseline` |
