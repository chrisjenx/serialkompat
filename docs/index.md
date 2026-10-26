# Catch breaking JSON & CBOR changes before they ship

A CI gate for kotlinx-serialization `@Serializable` models, like
[`buf breaking`](https://buf.build/docs/breaking/) but for JSON and [CBOR](cbor.md).

<div class="sk-steps" markdown>
<div class="sk-step" markdown>
<span class="sk-step-n">1</span>
**You change a model.** Rename a field, drop a default, add a required property.
</div>
<div class="sk-step" markdown>
<span class="sk-step-n">2</span>
**Old data is still out there.** Older app versions, queues, caches and databases hold the previous shape.
</div>
<div class="sk-step" markdown>
<span class="sk-step-n">3</span>
**The PR fails, with a fix.** serialkompat diffs against your target branch and says what breaks and how to fix it.
</div>
</div>

<div class="sk-hero-pair" markdown>
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

<div class="sk-terminal">
<div class="sk-terminal-bar"><i></i><i></i><i></i><b>CI</b></div>
<pre><code><span class="sk-t-prompt">$</span> ./gradlew serialkompatCheck

serialkompat: 1 active finding(s) (1 breaking, 0 warning), 0 acknowledged

  <span class="sk-t-break">BREAK</span>  PROPERTY_REMOVED  com.example.Order  (backward)
    field 'note' was removed from com.example.Order
    <span class="sk-t-dim">fix: Removing a field drops its data for tolerant readers; keep it (or bridge a rename with @JsonNames) until nothing uses it; else bump major.</span>

<span class="sk-t-fail">BUILD FAILED</span></code></pre>
</div>
</div>

## Why it's trustworthy

<div class="grid cards" markdown>

- **No baseline file**

    The old schema is extracted live from your target branch on every run.

- **Both directions**

    New code reading old data, and old code reading new data.

- **Proven, not guessed**

    Every rule is round-trip tested against real kotlinx-serialization, under your `Json { }` / `Cbor { }` config.

</div>

## Get started

```kotlin
plugins {
    id("com.chrisjenx.serialkompat") version "{{ skversion }}"
}
```

[Quick start](quickstart.md){ .md-button .md-button--primary }
[Rules](rules.md){ .md-button }
[GitHub Action](ci.md){ .md-button }

Not a runtime validator, migration tool or JSON-schema linter.
