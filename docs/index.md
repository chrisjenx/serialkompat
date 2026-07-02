# serialkompat

**A backward/forward compatibility gate for [kotlinx-serialization](https://github.com/Kotlin/kotlinx.serialization) `@Serializable` models — like [`buf breaking`](https://buf.build/docs/breaking/), but for JSON.**

It reads the JSON wire schema out of your compiled models, diffs it against a baseline from a git ref, and fails CI on incompatible changes.

[Quick start](quickstart.md){ .md-button .md-button--primary } [Rules](rules.md){ .md-button } [API](https://chrisjenx.github.io/serialkompat/api/){ .md-button }

!!! note
    Full content — animated pipeline, is/is-not table — lands with [#105](https://github.com/chrisjenx/serialkompat/issues/105).
