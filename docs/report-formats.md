# Report formats

Pick how a verdict reaches you: in the build log, as JSON for tooling, as SARIF for IDEs and
dashboards, or as inline annotations on a pull request. It is for anyone wiring serialkompat
into CI or reading its output. All formats show the same findings, so the one you pick never
changes whether the gate passes. To turn a format on, see the `reports.*` options in
[Configuration](configuration.md#serialkompat-reference).

| Format | Surface | Enable |
|---|---|---|
| Console | terminal / CI log | default (Gradle log; CLI default) |
| JSON | tooling, the PR comment | on by default (`report.json`); CLI `--format=json` |
| SARIF 2.1.0 | IDEs, SARIF dashboards | `reports { sarif { required.set(true) } }`; CLI `--format=sarif` |
| GitHub annotations | inline PR feedback | the `serialkompat` action (automatic); CLI `--format=github` |

Every example below renders the **same report**: three findings from one
`serialkompatCheck` run.

- An active breaking change: `PROPERTY_REMOVED` on `com.example.OrderEvent`.
- An active warning: `ENUM_VALUE_ADDED` on `com.example.Status`.
- A break you acknowledged with `acceptedBreaks`: `PROPERTY_REMOVED` on
  `com.example.LegacyPing`.

Compare the renderings to see what each format keeps.

## Console

This is the default. It's printed to the Gradle log, and by the CLI when you pass
no `--format`. Active findings come first, each with its fix hint, followed by a
short list of acknowledged breaks.

```text
--8<-- "docs/snippets/reports/console.txt"
```

## JSON

The JSON report's first key is **`schemaVersion`** (currently `"1.0"`), so your
tooling can rely on its shape:

```json
--8<-- "docs/snippets/reports/report.json"
```

`summary.breaking` and `summary.warning` count only **active** findings. The
acknowledged break is counted in `total` and `acknowledged`, but not in
`breaking`. So `failed` is `true` here only because of the active `OrderEvent`
break.

**Version policy:** an additive change (such as a new optional key) bumps the
**minor** version (`1.0` → `1.1`). A breaking shape change bumps the **major**
(`2.0`). A byte-exact golden test pins the shape, so it can't change silently.

By default the report is written to `build/serialkompat/report.json`. To move or
disable it:

```kotlin title="build.gradle.kts"
--8<-- "docs-samples/snippets/report-formats-json.gradle.kts"
```

From the CLI, `--format=json` writes it to stdout.

## SARIF

SARIF 2.1.0 output is for **IDEs and third-party SARIF dashboards**. It's off by
default. To enable it:

```kotlin title="build.gradle.kts"
--8<-- "docs-samples/snippets/report-formats-sarif.gradle.kts"
```

or `--format=sarif` from the CLI.

**Logical locations only.** A finding has a serial name (its `contract`, such as
`com.example.OrderEvent`) but **no source file or line**. The extractor works from
compiled `SerialDescriptor`s and bytecode, which don't reliably say where a
property was declared. So each result uses
`locations[].logicalLocations[].fullyQualifiedName`, never a `physicalLocation`.

**GitHub code scanning is not supported.** Code scanning only ingests SARIF results
that have a `physicalLocation` (a file URI), so a log with only logical locations
would show nothing in the Security tab. serialkompat could pin every finding to a
fake `build.gradle.kts:1`, but that would mislead you. Instead the SARIF stays
accurate, and serialkompat does **not** upload it to code scanning. The action has
no `upload-sarif` step. Real `file:line` locations would need source tracking in
the extractor, which is possible future work.

An acknowledged break (from `acceptedBreaks`) carries
`suppressions: [{ kind: "external", status: "accepted", justification: … }]` on its
result, so a consumer can see *why* the break was allowed.

`tool.driver.version` holds the plugin version, read from its jar manifest. It's
left out of dev and Gradle TestKit runs, which have no manifest. The `rules`
catalog always lists every rule id, so a consumer can resolve any `ruleIndex`. The
shared report renders as:

??? example "report.sarif (full log)"

    ```json
    --8<-- "docs/snippets/reports/report.sarif"
    ```

## GitHub annotations

On CI, the `serialkompat` action posts inline annotations for the **active**
findings, alongside its sticky PR comment. A `BREAK` becomes an error and a `WARN`
becomes a warning. Acknowledged breaks are not annotated.

GitHub caps annotations at **10 errors + 10 warnings** per step. When there are
more, the action adds one **notice** with the number left out, so nothing is
silently lost. The sticky comment always lists every finding.

Findings have no source file or line, so annotations attach to the run and the job
summary, not to a line of code.

Outside the action, `--format=github` prints the same GitHub workflow-command lines
to stdout (`::error` / `::warning`, plus the `::notice` summary). Use it when you
run the CLI directly in a workflow step.

For the shared report, the two **active** findings become annotations; the
acknowledged `LegacyPing` break produces none:

```text
--8<-- "docs/snippets/reports/github.txt"
```

## CLI: `--format`

```console
$ serialkompat diff baseline.snapshot current.snapshot --format=sarif > report.sarif
```

The values are `console`, `json`, `sarif`, and `github`; the default is `console`.
`--format` only changes what is printed. It never changes the exit code; see
[exit codes](tasks-cli.md#exit-codes).

## Gradle: report scope

The `reports { }` block fully applies to the pairwise checks, `serialkompatCheck`
and `serialkompatCheckAgainst`.

The history check (`serialkompatCheckHistory`) follows the same `required`
settings, but ignores `outputLocation`. It always writes to
`build/serialkompat/report-history.json` and `report-history.sarif`. That way it
never overwrites the pairwise `report.json`.

!!! warning
    The GitHub Action builds its PR comment and annotations from `report.json`.
    Keep the JSON report enabled when you use the action. If you disable it
    (`reports { json { required.set(false) } }`), the action has no report to read.
    It then posts "No report was produced", even though the gate ran.

## Next

- [Configuration](configuration.md): the full `serialkompat { }` DSL, including `reports { }`.
- [CI setup](ci.md): the GitHub Action, the sticky comment, and the inline annotations.
