# serialkompat

**A backward/forward compatibility gate for [kotlinx-serialization](https://github.com/Kotlin/kotlinx.serialization) `@Serializable` models — like [`buf breaking`](https://buf.build/docs/breaking/), but for JSON.**

You delete a field. Payloads in queues, caches, and old app versions still carry it:

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

serialkompat compares your models against a **baseline**: the schema at a git ref,
usually your target branch. It extracts that baseline live on every run, so there is
no baseline file to maintain.

Every change is checked in both directions. *Backward* compatible means new code can
read old data. *Forward* compatible means old code can read new data. Each verdict
reflects how real kotlinx-serialization behaves under your actual `Json { }` config.

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

- **`@Serializable`**: your compiled Kotlin models.
- **Extractor**: walks the runtime `SerialDescriptor` graph on the JVM, so it sees exactly what goes on the wire.
- **Snapshot**: a canonical, comparable model of that wire schema.
- **Differ**: lists the changes between two snapshots.
- **Classifier**: applies the rules and your real `Json { }` config to turn each change into a finding with a severity.
- **Report**: the findings plus an exit code, printed to the console. It is also available as [JSON, SARIF, and GitHub annotations](report-formats.md).

## Is / is not

| serialkompat is | serialkompat is not |
|---|---|
| A CI gate for JSON wire/persisted-schema evolution of `@Serializable` models | A runtime validator |
| Direction-aware (`BACKWARD` / `FORWARD` / `FULL`) and config-aware (`ignoreUnknownKeys`, `namingStrategy`, `encodeDefaults`, …) | A migration tool |
| Grounded in real kotlinx-serialization behavior — every rule is backed by a round-trip oracle test | A replacement for API/schema versioning |
| | A general-purpose JSON-schema linter |

!!! note "Install from Maven Central"
    The plugin is published to Maven Central, not the Gradle Plugin Portal. Add
    `mavenCentral()` to `pluginManagement.repositories` in `settings.gradle.kts`, as shown in
    [Setup](setup.md#gradle-plugin). Development happens in the open; see the
    [issues](https://github.com/chrisjenx/serialkompat/issues) and
    [milestones](https://github.com/chrisjenx/serialkompat/milestones).
