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

## JSON

The JSON report carries a top-level **`schemaVersion`** (currently `"1.0"`) as its
first key, so external tooling can depend on the shape:

```json
{
  "schemaVersion": "1.0",
  "summary": { "total": 2, "breaking": 1, "warning": 1, "acknowledged": 0, "failed": true },
  "findings": [
    { "rule": "PROPERTY_REMOVED", "severity": "BREAK", "direction": "BACKWARD",
      "contract": "com.example.OrderEvent", "detail": "field 'note'",
      "message": "…", "fixHint": "…", "acknowledged": false }
  ]
}
```

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
