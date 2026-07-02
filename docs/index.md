# serialkompat

**A backward/forward compatibility gate for [kotlinx-serialization](https://github.com/Kotlin/kotlinx.serialization) `@Serializable` models — like [`buf breaking`](https://buf.build/docs/breaking/), but for JSON.**

<div class="sk-pipeline" markdown>
<div class="sk-stage" markdown>`@Serializable`</div>
<div class="sk-connector"></div>
<div class="sk-stage" markdown>Extractor</div>
<div class="sk-connector"></div>
<div class="sk-stage" markdown>Snapshot</div>
<div class="sk-connector"></div>
<div class="sk-stage" markdown>Differ</div>
<div class="sk-connector"></div>
<div class="sk-stage" markdown>Classifier</div>
<div class="sk-connector"></div>
<div class="sk-stage" markdown>Report</div>
</div>

- **`@Serializable`** — your compiled Kotlin models.
- **Extractor** — walks the runtime `SerialDescriptor` graph (JVM); sees exactly what goes on the wire.
- **Snapshot** — a canonical, comparable model of that wire schema.
- **Differ** — computes the list of changes between two snapshots.
- **Classifier** — applies rules plus your real `Json { }` config to turn changes into findings and a severity.
- **Report** — findings plus an exit code, printed to console and CI.

The baseline snapshot is extracted **live from a git ref** (e.g. your target branch) — there's no hand-maintained baseline file to forget to update.

## Is / is not

| serialkompat is | serialkompat is not |
|---|---|
| A CI gate for JSON wire/persisted-schema evolution of `@Serializable` models | A runtime validator |
| Direction-aware (`BACKWARD` / `FORWARD` / `FULL`) and config-aware (`ignoreUnknownKeys`, `namingStrategy`, `encodeDefaults`, …) | A migration tool |
| Grounded in real kotlinx-serialization behavior — every rule is backed by a round-trip oracle test | A replacement for API/schema versioning |
| | A general-purpose JSON-schema linter |

[Quick start](quickstart.md){ .md-button .md-button--primary }
[Rules](rules.md){ .md-button }
[API](https://chrisjenx.github.io/serialkompat/api/){ .md-button }

!!! warning "Early development"
    serialkompat is `{{ skversion }}` — `-SNAPSHOT`s publish to Maven Central on every push
    to `main`, but there is no stable release yet and the plugin is not on the Gradle Plugin
    Portal (see [Setup](setup.md#gradle-plugin)). The design is settled and the project is
    being built in the open, one reviewed PR at a time — see the
    [issues](https://github.com/chrisjenx/serialkompat/issues) and
    [milestones](https://github.com/chrisjenx/serialkompat/milestones).
