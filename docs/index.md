# serialkompat

**A backward/forward compatibility gate for [kotlinx-serialization](https://github.com/Kotlin/kotlinx.serialization) `@Serializable` models — like [`buf breaking`](https://buf.build/docs/breaking/), but for JSON.**

You delete a field. The JSON already on the wire — queues, caches, older app
versions — still carries it. Code review says LGTM; serialkompat fails the build:

<div class="sk-hero" markdown>

<div class="sk-hero-caption" markdown>The one-line change on your PR</div>

```diff
 @Serializable
 data class Order(
     val id: String,
     val amountCents: Long,
-    val note: String? = null,
 )
```

</div>

<div class="sk-hero-flow" aria-hidden="true"><span></span></div>

<div class="sk-terminal">
<div class="sk-terminal-bar"><i></i><i></i><i></i><b>CI</b></div>
<pre><code><span class="sk-t-prompt">$</span> ./gradlew serialkompatCheck

serialkompat: 1 active finding(s) (1 breaking, 0 warning), 0 acknowledged

  <span class="sk-t-break">BREAK</span>  PROPERTY_REMOVED  com.example.Order  (backward)
    field 'note' was removed from com.example.Order
    <span class="sk-t-dim">fix: Removing a field drops its data for tolerant readers; keep it (or bridge a rename with @JsonNames) until nothing uses it; else bump major.</span>

<span class="sk-t-fail">BUILD FAILED</span></code></pre>
</div>

No baseline file to maintain: the baseline schema is extracted **live from a git
ref** (your target branch), diffed against your compiled models, and every change
is classified against real kotlinx-serialization behavior — including your actual
`Json { }` config, in both directions (new code reading old data, and old code
reading new data).

[Quick start](quickstart.md){ .md-button .md-button--primary }
[Rules](rules.md){ .md-button }
[API](https://chrisjenx.github.io/serialkompat/api/){ .md-button }

## How it works

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
- **Report** — findings plus an exit code, printed to console and CI (also [JSON, SARIF, and GitHub annotations](report-formats.md)).

## Is / is not

| serialkompat is | serialkompat is not |
|---|---|
| A CI gate for JSON wire/persisted-schema evolution of `@Serializable` models | A runtime validator |
| Direction-aware (`BACKWARD` / `FORWARD` / `FULL`) and config-aware (`ignoreUnknownKeys`, `namingStrategy`, `encodeDefaults`, …) | A migration tool |
| Grounded in real kotlinx-serialization behavior — every rule is backed by a round-trip oracle test | A replacement for API/schema versioning |
| | A general-purpose JSON-schema linter |

!!! warning "Early development"
    serialkompat is `{{ skversion }}` — `-SNAPSHOT`s publish to Maven Central on every push
    to `main`, but there is no stable release yet and the plugin is not on the Gradle Plugin
    Portal (see [Setup](setup.md#gradle-plugin)). The design is settled and the project is
    being built in the open, one reviewed PR at a time — see the
    [issues](https://github.com/chrisjenx/serialkompat/issues) and
    [milestones](https://github.com/chrisjenx/serialkompat/milestones).
