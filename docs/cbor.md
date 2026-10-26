# CBOR

serialkompat checks [kotlinx-serialization-cbor](https://github.com/Kotlin/kotlinx.serialization/blob/master/formats/README.md#cbor)
models to the same level as JSON: the same rule matrix, the same gate, plus rules for
the features only CBOR has. Every verdict is proved by a round-trip oracle against real
`Cbor`.

CBOR is usually **persisted**: client caches, job-queue payloads, Redis values. That is
where schema evolution bites hardest. An old app build reads a cache entry a newer build
wrote, and a strict `Cbor` (the default, `ignoreUnknownKeys = false`) throws on the field
it doesn't know.

## Setup

Set the format, and point `cborInstance` at the `Cbor` your code really uses, so its
settings and `SerializersModule` decide the verdicts:

```kotlin title="build.gradle.kts"
import com.chrisjenx.serialkompat.core.CompatibilityDirection
import com.chrisjenx.serialkompat.core.WireFormat

serialkompat {
    format.set(WireFormat.CBOR)
    cborInstance.set("com.example.storage.WireCbor.instance") // unset = Cbor.Default
    types.set(listOf("com.example.storage.CacheEntry"))
    direction.set(CompatibilityDirection.FULL)
}
```

`cborInstance` names an `object` property (`com.example.WireCbor.instance`) or a
top-level `val` (`com.example.WireCborKt.instance`). If it is set but can't be loaded, or
`kotlinx-serialization-cbor` isn't on the module's runtime classpath, extraction fails
instead of guessing. Setting `cborInstance` under `JSON`, or `jsonInstance` under `CBOR`,
also fails.

!!! tip "Use `FULL` for persisted data"
    A cache or a job queue is read by both older and newer builds, so you need
    [backward (new code reads old data)](concepts.md#backward-vs-forward) and
    [forward (old code reads new data)](concepts.md#backward-vs-forward). `FULL` checks
    both; see [Which direction do I pick?](concepts.md#choosing-a-direction).

## One format per module { #one-format-per-module }

A snapshot describes one format, so a module is checked as JSON *or* CBOR. When the same
models travel as JSON (an API) and are stored as CBOR (a cache), check them from two
modules. The module that owns the cache, with `kotlinx-serialization-cbor` on its runtime
classpath, can name the cached types in `types` even when another module defines them:

```kotlin title="storage/build.gradle.kts"
serialkompat {
    format.set(WireFormat.CBOR)
    cborInstance.set("com.example.storage.CacheCbor.instance")
    types.set(listOf("com.example.api.OrderResponse", "com.example.api.UserResponse"))
}
```

## Adopting CBOR { #adopting-cbor }

The baseline is extracted on `baselineRef` with *its* configuration (see
[Baseline & caching](baseline-caching.md#the-git-ref-baseline)). On the change that sets `format = CBOR`, the baseline is
still a JSON snapshot. Snapshots of different formats aren't compared, so that run
reports a single `CONFIG_FORMAT` warning, and the gate starts comparing CBOR on the next
change.

## What's the same as JSON

CBOR behaves like a `Json` with `explicitNulls = true`, `coerceInputValues = false` and
`useArrayPolymorphism = true`. The extractor records that, so every JSON rule applies
unchanged:

- An added field breaks a strict old reader (`PROPERTY_ADDED` forward). With
  `ignoreUnknownKeys = true` an added *optional* field is safe.
- A removed field breaks a strict new reader, and is a silent drop (WARN) for a tolerant
  one (`PROPERTY_REMOVED`).
- Optionality, nullability, type changes, enum values and subtypes follow the
  [rule matrix](rules.md#the-matrix).

The differences, all proved in the oracle:

| | JSON | CBOR |
|---|---|---|
| Absent nullable field with no default | decodes as `null` under `explicitNulls = false` | always fails: there is no `explicitNulls = false` escape |
| Unknown enum value | can coerce to a default with `coerceInputValues` | always fails |
| Rename bridge | `@JsonNames` aliases | none (`@JsonNames` is ignored); a renamed field is a remove + add |
| Polymorphism | `{"type": …}` discriminator key | `[serialName, value]` array, so no discriminator rules |
| Numbers | widen `Int` → `Long`, `Float` → `Double` | the same widenings; `Int` → `Double` fails (different CBOR major types) |

## CBOR-only rules

Rules that only apply to CBOR (`CBOR_ARRAY_LAYOUT`, `CBOR_ARRAY_OMITTED_DEFAULT`,
`PROPERTY_CBOR_LABEL`, `PROPERTY_BYTE_STRING`, `CBOR_TAGS_CHANGED`, the `CONFIG_CBOR_*`
settings and `CONFIG_FORMAT`) are listed with their verdicts in the
[rules matrix](rules.md#the-matrix), with worked examples for
[`CBOR_ARRAY_LAYOUT`](rules.md#cbor_array_layout) and
[`PROPERTY_CBOR_LABEL`](rules.md#property_cbor_label).

## Limitations

- A type with a hand-written `KSerializer` is opaque, just as for JSON: a coverage-gap
  warning, never a silent pass.
- Extraction runs on the JVM. A Kotlin Multiplatform module is checked through its JVM
  target.
