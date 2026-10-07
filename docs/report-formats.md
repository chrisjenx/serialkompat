# Report formats

serialkompat runs the gate once and produces a single `Report`. It then renders
that report in one or more formats. Every reporter is a **pure function in
`serialkompat-core`**, with no I/O and no kotlinx-serialization runtime. Each format
is a read-only view of the same findings, so the format you pick never changes
the gate's result.

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

The JSON report's first key is **`schemaVersion`** (currently `"1.0"`), so your
tooling can rely on its shape:

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

SARIF 2.1.0 output is for **IDEs and third-party SARIF dashboards**. It's off by
default. To enable it:

```kotlin title="build.gradle.kts"
serialkompat {
  reports {
    sarif { required.set(true) }                          // off by default -> report.sarif
  }
}
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
                { "id": "CONFIG_ARRAY_POLYMORPHISM", "name": "CONFIG_ARRAY_POLYMORPHISM", "helpUri": "https://chrisjenx.github.io/serialkompat/rules/" },
                { "id": "CONFIG_STRUCTURED_MAP_KEYS", "name": "CONFIG_STRUCTURED_MAP_KEYS", "helpUri": "https://chrisjenx.github.io/serialkompat/rules/" },
                { "id": "CONFIG_SPECIAL_FLOATS", "name": "CONFIG_SPECIAL_FLOATS", "helpUri": "https://chrisjenx.github.io/serialkompat/rules/" },
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
::error title=PROPERTY_REMOVED::com.example.OrderEvent — field 'note' was removed from com.example.OrderEvent
::warning title=ENUM_VALUE_ADDED::com.example.Status — enum value 'ARCHIVED' was added to com.example.Status
```

## CLI: `--format`

```console
$ serialkompat diff baseline.snapshot current.snapshot --format=sarif > report.sarif
```

The values are `console`, `json`, `sarif`, and `github`; the default is `console`.
`--format` only changes what is printed. It never changes the exit code: `0` means
OK, `1` means a breaking change, and `2` means a usage error.

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
