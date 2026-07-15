# Report formats

serialkompat runs the gate once and produces a single `Report`, then renders it in
several formats. Every reporter is a **pure function in `serialkompat-core`** — no
I/O, no kotlinx-serialization runtime — so each format is a read-only view of the
same findings, and adding a format never changes the gate's result.

| Format | Surface | Enable |
|---|---|---|
| Console | terminal / CI log | default (Gradle log; CLI default) |
| JSON | tooling, the PR comment | on by default (`report.json`); CLI `--format=json` |
| SARIF 2.1.0 | IDEs, SARIF dashboards | `reports { sarif { required.set(true) } }`; CLI `--format=sarif` |
| GitHub annotations | inline PR feedback | the `serialkompat` action (automatic); CLI `--format=github` |

Every example below renders the **same report** — three findings from one
`serialkompatCheck` run: an active breaking change (`PROPERTY_REMOVED` on
`com.example.OrderEvent`), an active warning (`ENUM_VALUE_ADDED` on
`com.example.Status`), and one break acknowledged via `acceptedBreaks`
(`PROPERTY_REMOVED` on `com.example.LegacyPing`). Reading the four renderings
side by side shows what each format keeps.

## Console

The default: printed to the Gradle log, and by the CLI when you pass no
`--format`. Active findings first (each with its fix hint), then a short list of
the acknowledged breaks.

```text
serialkompat: 2 active finding(s) (1 breaking, 1 warning), 1 acknowledged

  BREAK  PROPERTY_REMOVED  com.example.OrderEvent  (backward)
    field 'note' was removed from com.example.OrderEvent
    fix: Removing a field drops its data for tolerant readers; keep it (or bridge a rename with @JsonNames) until nothing uses it; else bump major.
  WARN  ENUM_VALUE_ADDED  com.example.Status  (forward)
    enum value 'ARCHIVED' was added to com.example.Status
    fix: Enable coerceInputValues *and* give the reading field a default, or bump major.

acknowledged:
  BREAK  PROPERTY_REMOVED  com.example.LegacyPing  (backward)  [acknowledged]
```

## JSON

The JSON report carries a top-level **`schemaVersion`** (currently `"1.0"`) as its
first key, so external tooling can depend on the shape:

```json
{
  "schemaVersion": "1.0",
  "summary": {
    "total": 3,
    "breaking": 1,
    "warning": 1,
    "acknowledged": 1,
    "failed": true
  },
  "findings": [
    {
      "rule": "PROPERTY_REMOVED",
      "severity": "BREAK",
      "direction": "BACKWARD",
      "contract": "com.example.OrderEvent",
      "detail": "field 'note'",
      "message": "field 'note' was removed from com.example.OrderEvent",
      "fixHint": "Removing a field drops its data for tolerant readers; keep it (or bridge a rename with @JsonNames) until nothing uses it; else bump major.",
      "acknowledged": false
    },
    {
      "rule": "ENUM_VALUE_ADDED",
      "severity": "WARN",
      "direction": "FORWARD",
      "contract": "com.example.Status",
      "detail": "value 'ARCHIVED'",
      "message": "enum value 'ARCHIVED' was added to com.example.Status",
      "fixHint": "Enable coerceInputValues *and* give the reading field a default, or bump major.",
      "acknowledged": false
    },
    {
      "rule": "PROPERTY_REMOVED",
      "severity": "BREAK",
      "direction": "BACKWARD",
      "contract": "com.example.LegacyPing",
      "detail": "field 'seq'",
      "message": "field 'seq' was removed from com.example.LegacyPing",
      "fixHint": "Removing a field drops its data for tolerant readers; keep it (or bridge a rename with @JsonNames) until nothing uses it; else bump major.",
      "acknowledged": true
    }
  ]
}
```

`summary.breaking` and `summary.warning` count only **active** findings —
the acknowledged break is in `total` and `acknowledged`, but its
`"acknowledged": true` keeps it out of the breaking count, so `failed` here is
still `true` only because of the active `OrderEvent` break.

**Version policy:** an additive change (a new optional key) bumps the **minor**
(`1.0` → `1.1`); a breaking shape change bumps the **major** (`2.0`). The shape is
pinned by a byte-exact golden test, so a silent change can't slip through.

It is written by default to `build/serialkompat/report.json`. Customize or disable it:

```kotlin title="build.gradle.kts"
serialkompat {
  reports {
    json {
      required.set(true)                                  // default
      outputLocation.set(layout.buildDirectory.file("serialkompat/report.json"))
    }
  }
}
```

From the CLI, `--format=json` writes it to stdout.

## SARIF

SARIF 2.1.0 output targets **IDEs and third-party SARIF dashboards**. Enable it:

```kotlin title="build.gradle.kts"
serialkompat {
  reports {
    sarif { required.set(true) }                          // off by default -> report.sarif
  }
}
```

or `--format=sarif` from the CLI.

**Logical locations only.** A finding carries a serial name (its `contract`, e.g.
`com.example.OrderEvent`) but **no source file or line** — the extractor works from
compiled `SerialDescriptor`s and bytecode, which don't reliably give a property's
declaration site. So each result uses
`locations[].logicalLocations[].fullyQualifiedName` and never a `physicalLocation`.

**GitHub code scanning is out of scope.** Code scanning requires a
`physicalLocation` (a file URI) to ingest a SARIF result, so a logical-only log
would surface nothing in the Security tab. Rather than pin every finding to a fake
`build.gradle.kts:1` — misleading, and against serialkompat's never-mislead ethos —
the SARIF stays honest and serialkompat does **not** upload it to code scanning
(the action has no `upload-sarif` step). Physical `file:line` locations would need
source tracking in the extractor and remain possible future work.

Acknowledged breaks (via `acceptedBreaks`) appear on their result as
`suppressions: [{ kind: "external", status: "accepted", justification: … }]`, so a
consumer can see *why* a break was sanctioned.

`tool.driver.version` carries the plugin version (read from its jar manifest);
it is omitted under dev or Gradle TestKit runs, where no manifest is present.
The `rules` catalog always lists every rule id, so a consumer can resolve any
`ruleIndex`. The shared report renders as:

??? example "report.sarif (full log)"

    ```json
    {
      "$schema": "https://json.schemastore.org/sarif-2.1.0.json",
      "version": "2.1.0",
      "runs": [
        {
          "tool": {
            "driver": {
              "name": "serialkompat",
              "informationUri": "https://chrisjenx.github.io/serialkompat/",
              "version": "0.1.0",
              "rules": [
                { "id": "CONTRACT_REMOVED", "name": "CONTRACT_REMOVED", "helpUri": "https://chrisjenx.github.io/serialkompat/rules/" },
                { "id": "PROPERTY_ADDED", "name": "PROPERTY_ADDED", "helpUri": "https://chrisjenx.github.io/serialkompat/rules/" },
                { "id": "PROPERTY_REMOVED", "name": "PROPERTY_REMOVED", "helpUri": "https://chrisjenx.github.io/serialkompat/rules/" },
                { "id": "PROPERTY_OPTIONALITY", "name": "PROPERTY_OPTIONALITY", "helpUri": "https://chrisjenx.github.io/serialkompat/rules/" },
                { "id": "PROPERTY_NULLABILITY", "name": "PROPERTY_NULLABILITY", "helpUri": "https://chrisjenx.github.io/serialkompat/rules/" },
                { "id": "PROPERTY_JSON_NAMES", "name": "PROPERTY_JSON_NAMES", "helpUri": "https://chrisjenx.github.io/serialkompat/rules/" },
                { "id": "PROPERTY_TYPE_CHANGED", "name": "PROPERTY_TYPE_CHANGED", "helpUri": "https://chrisjenx.github.io/serialkompat/rules/" },
                { "id": "ENUM_VALUE_ADDED", "name": "ENUM_VALUE_ADDED", "helpUri": "https://chrisjenx.github.io/serialkompat/rules/" },
                { "id": "ENUM_VALUE_REMOVED", "name": "ENUM_VALUE_REMOVED", "helpUri": "https://chrisjenx.github.io/serialkompat/rules/" },
                { "id": "SUBTYPE_ADDED", "name": "SUBTYPE_ADDED", "helpUri": "https://chrisjenx.github.io/serialkompat/rules/" },
                { "id": "SUBTYPE_REMOVED", "name": "SUBTYPE_REMOVED", "helpUri": "https://chrisjenx.github.io/serialkompat/rules/" },
                { "id": "DISCRIMINATOR_CHANGED", "name": "DISCRIMINATOR_CHANGED", "helpUri": "https://chrisjenx.github.io/serialkompat/rules/" },
                { "id": "DISCRIMINATOR_VALUE_CHANGED", "name": "DISCRIMINATOR_VALUE_CHANGED", "helpUri": "https://chrisjenx.github.io/serialkompat/rules/" },
                { "id": "DISCRIMINATOR_COLLISION", "name": "DISCRIMINATOR_COLLISION", "helpUri": "https://chrisjenx.github.io/serialkompat/rules/" },
                { "id": "CONFIG_CHANGED", "name": "CONFIG_CHANGED", "helpUri": "https://chrisjenx.github.io/serialkompat/rules/" },
                { "id": "CONFIG_NAMING_STRATEGY", "name": "CONFIG_NAMING_STRATEGY", "helpUri": "https://chrisjenx.github.io/serialkompat/rules/" },
                { "id": "CONFIG_DISCRIMINATOR", "name": "CONFIG_DISCRIMINATOR", "helpUri": "https://chrisjenx.github.io/serialkompat/rules/" },
                { "id": "CONFIG_READER_STRICTNESS", "name": "CONFIG_READER_STRICTNESS", "helpUri": "https://chrisjenx.github.io/serialkompat/rules/" },
                { "id": "CONFIG_ENCODE_DEFAULTS", "name": "CONFIG_ENCODE_DEFAULTS", "helpUri": "https://chrisjenx.github.io/serialkompat/rules/" },
                { "id": "CONFIG_EXPLICIT_NULLS", "name": "CONFIG_EXPLICIT_NULLS", "helpUri": "https://chrisjenx.github.io/serialkompat/rules/" },
                { "id": "CONFIG_COERCE_INPUT", "name": "CONFIG_COERCE_INPUT", "helpUri": "https://chrisjenx.github.io/serialkompat/rules/" },
                { "id": "COVERAGE_GAP", "name": "COVERAGE_GAP", "helpUri": "https://chrisjenx.github.io/serialkompat/rules/" }
              ]
            }
          },
          "results": [
            {
              "ruleId": "PROPERTY_REMOVED",
              "ruleIndex": 2,
              "level": "error",
              "message": { "text": "field 'note' was removed from com.example.OrderEvent" },
              "locations": [ { "logicalLocations": [ { "fullyQualifiedName": "com.example.OrderEvent" } ] } ],
              "properties": { "direction": "BACKWARD", "detail": "field 'note'", "fixHint": "Removing a field drops its data for tolerant readers; keep it (or bridge a rename with @JsonNames) until nothing uses it; else bump major." }
            },
            {
              "ruleId": "ENUM_VALUE_ADDED",
              "ruleIndex": 7,
              "level": "warning",
              "message": { "text": "enum value 'ARCHIVED' was added to com.example.Status" },
              "locations": [ { "logicalLocations": [ { "fullyQualifiedName": "com.example.Status" } ] } ],
              "properties": { "direction": "FORWARD", "detail": "value 'ARCHIVED'", "fixHint": "Enable coerceInputValues *and* give the reading field a default, or bump major." }
            },
            {
              "ruleId": "PROPERTY_REMOVED",
              "ruleIndex": 2,
              "level": "error",
              "message": { "text": "field 'seq' was removed from com.example.LegacyPing" },
              "locations": [ { "logicalLocations": [ { "fullyQualifiedName": "com.example.LegacyPing" } ] } ],
              "suppressions": [ { "kind": "external", "status": "accepted", "justification": "retired in v3; no live producers — accepted by alice" } ],
              "properties": { "direction": "BACKWARD", "detail": "field 'seq'", "fixHint": "Removing a field drops its data for tolerant readers; keep it (or bridge a rename with @JsonNames) until nothing uses it; else bump major." }
            }
          ]
        }
      ]
    }
    ```

## GitHub annotations

On CI, the `serialkompat` action posts inline annotations for the **active**
findings (`BREAK` → error, `WARN` → warning) in addition to the sticky PR comment.
GitHub caps annotations at **10 errors + 10 warnings** per step; when more exist,
the action emits a single **notice** summarizing the dropped count, so nothing is
silently lost — the sticky comment remains the complete surface. Findings have no
source file/line, so annotations attach to the run and the job summary, not a
specific line of code. Acknowledged breaks are not annotated.

Outside the action — for non-GitHub CI that captures stdout — `--format=github`
emits the same GitHub workflow-command lines (`::error` / `::warning`, plus the
`::notice` summary).

For the shared report, the two **active** findings become annotations; the
acknowledged `LegacyPing` break produces none:

```text
::error title=PROPERTY_REMOVED::com.example.OrderEvent — field 'note' was removed from com.example.OrderEvent
::warning title=ENUM_VALUE_ADDED::com.example.Status — enum value 'ARCHIVED' was added to com.example.Status
```

## CLI: `--format`

```console
$ serialkompat diff baseline.snapshot current.snapshot --format=sarif > report.sarif
```

`--format=console|json|sarif|github` (default `console`). `--format` only changes
what is rendered — it never changes the exit code (`0` ok, `1` breaking, `2` usage).

## Gradle: report scope

The `reports { }` block applies to the pairwise `serialkompatCheck` /
`serialkompatCheckAgainst`. The transitive history check
(`serialkompatCheckHistory`) writes its own `report-history.json` /
`report-history.sarif`, so the pairwise `report.json` stays deterministic.

!!! warning
    The GitHub Action builds its PR comment and annotations from `report.json`, so
    keep the JSON report enabled when using the action. Disabling it
    (`reports { json { required.set(false) } }`) leaves the action with no report to
    read, and it posts "No report was produced" even though the gate ran.

## Next

- [Configuration](configuration.md) — the full `serialkompat { }` DSL, including `reports { }`.
- [CI setup](ci.md) — the GitHub Action, the sticky comment, and the inline annotations.
