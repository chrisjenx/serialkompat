# Rules

This page lists every rule the classifier applies. Each rule decides how serious
one kind of change is, in each direction, under your `Json { }` config. The rules
run on every change the [Differ](deep-dive.md) finds between two snapshots (the
extracted wire schema of your models at two points in time).

## Severity

| Glyph | Severity | Meaning |
|---|---|---|
| ✅ | `SAFE` | No impact. The change round-trips cleanly. |
| ⚠️ | `WARN` | Depends on config, or a silent semantic break: decode succeeds, but the data is wrong, defaulted, or dropped. No exception is thrown. |
| ❌ | `BREAK` | Decode fails. The reader throws. |

## Direction

- **`BACKWARD`**: new code reads old data (new reader ← old writer). Can code
  built against the *new* schema decode a payload written by the *old* one?
- **`FORWARD`**: old code reads new data (old reader ← new writer). Can code
  still running the *old* schema decode a payload written by the *new* one?
- **`FULL`** checks both. It's the default, and the safest choice unless you
  control both ends of a rollout. See [Configuration → Choosing a
  direction](configuration.md#choosing-a-direction).

## The matrix

The verdicts below assume kotlinx-serialization defaults unless a cell says
otherwise: `ignoreUnknownKeys = false`, `encodeDefaults = false`,
`explicitNulls = true`, `coerceInputValues = false`.

"Config-aware" means the verdict changes under a non-default `Json { }`.
[Config awareness](#config-awareness) below explains exactly how.

In each cell, the **reader** is the side decoding the payload and the **writer** is
the side encoding it. Backward, the reader is your new code; forward, it's your old code.

| Rule | Detects | Backward | Forward | Config-aware |
|---|---|---|---|---|
| `CONTRACT_REMOVED` | Whole type deleted | ❌ BREAK | ❌ BREAK | — |
| [`PROPERTY_ADDED`](#property_added) | Field added | ✅ SAFE if optional (or nullable & reader `explicitNulls=false`), ❌ BREAK otherwise | ❌ BREAK unless reader has `ignoreUnknownKeys` | yes |
| [`PROPERTY_REMOVED`](#property_removed) | Field deleted | ⚠️ WARN if reader has `ignoreUnknownKeys` (silent drop), else ❌ BREAK | ✅ SAFE if it was optional, ⚠️ WARN if nullable & reader `explicitNulls=false` (absent → null), else ❌ BREAK | yes |
| `PROPERTY_OPTIONALITY` | Optional ↔ required | ❌ BREAK if became required, ✅ SAFE if became optional | ✅ SAFE if became required; if became optional: ❌ BREAK unless the new writer always emits it (`@EncodeDefault(ALWAYS)`, or `encodeDefaults` without `@EncodeDefault(NEVER)`), ⚠️ WARN if `encodeDefaults` but the field's `@EncodeDefault` couldn't be read | yes |
| `PROPERTY_NULLABILITY` | Nullable ↔ non-null | ✅ SAFE if became nullable, ❌ BREAK if became non-null | ❌ BREAK if became nullable & writer `explicitNulls = true`; ⚠️ WARN if `false` | yes |
| `PROPERTY_TYPE_CHANGED` | Field type changed | ✅ SAFE if numeric widening, else ❌ BREAK | ❌ BREAK | no |
| `PROPERTY_JSON_NAMES` | `@JsonNames` alias dropped | ⚠️ WARN | ✅ SAFE | no |
| [`ENUM_VALUE_ADDED`](#enum_value_added) | Enum value added | ✅ SAFE | ❌ BREAK, ⚠️ WARN if the reader coerces **and** every field reading the enum has a default | yes |
| `ENUM_VALUE_REMOVED` | Enum value removed | ❌ BREAK | ✅ SAFE | no |
| `SUBTYPE_ADDED` | Polymorphic variant added | ✅ SAFE | ❌ BREAK, ⚠️ WARN if the base registers a default deserializer **and** the reader has `ignoreUnknownKeys`² | yes |
| `SUBTYPE_REMOVED` | Polymorphic variant removed | ❌ BREAK | ✅ SAFE | no |
| `DISCRIMINATOR_CHANGED` | Discriminator key changed | ❌ BREAK | ❌ BREAK | no |
| `DISCRIMINATOR_VALUE_CHANGED` | Polymorphic type moved (new FQN) | ❌ BREAK | ❌ BREAK | no |
| `DISCRIMINATOR_COLLISION` | Subtype property shadows the class discriminator (unserializable model) | ❌ BREAK | ❌ BREAK | no¹ |
| `CONFIG_NAMING_STRATEGY` | `namingStrategy` changed | ❌ BREAK | ❌ BREAK | — |
| `CONFIG_DISCRIMINATOR` | `classDiscriminator` or `classDiscriminatorMode` changed | ❌ BREAK | ❌ BREAK | — |
| `CONFIG_READER_STRICTNESS` | `ignoreUnknownKeys` / `useAlternativeNames` / `isLenient` / `decodeEnumsCaseInsensitive` / `allowTrailingComma` / `allowComments` changed | ⚠️ WARN if tightened, ✅ SAFE if loosened | ✅ SAFE | — |
| `CONFIG_ARRAY_POLYMORPHISM` | `useArrayPolymorphism` toggled (`{"type":..}` object ↔ `["..",{..}]` array) | ❌ BREAK | ❌ BREAK | — |
| `CONFIG_STRUCTURED_MAP_KEYS` | `allowStructuredMapKeys` toggled | ❌ BREAK if disabled, ✅ SAFE if enabled | ✅ SAFE if disabled, ❌ BREAK if enabled | — |
| `CONFIG_SPECIAL_FLOATS` | `allowSpecialFloatingPointValues` toggled (`NaN`/`Infinity`) | ⚠️ WARN if disabled, ✅ SAFE if enabled | ✅ SAFE if disabled, ⚠️ WARN if enabled | — |
| `CONFIG_ENCODE_DEFAULTS` | `encodeDefaults` toggled | ✅ SAFE | ⚠️ WARN if disabled, ✅ SAFE if enabled | — |
| `CONFIG_EXPLICIT_NULLS` | `explicitNulls` toggled | ⚠️ WARN | ⚠️ WARN | — |
| `CONFIG_COERCE_INPUT` | `coerceInputValues` toggled | ⚠️ WARN if disabled, ✅ SAFE if enabled | ✅ SAFE | — |
| `CONFIG_CHANGED` | Any other wire-relevant `Json` setting changed (catch-all) | ⚠️ WARN | ⚠️ WARN | — |
| `COVERAGE_GAP` | Opaque/unanalyzable type | ⚠️ WARN | ⚠️ WARN | — |

¹ `DISCRIMINATOR_COLLISION` is not a difference between two versions. It flags a
single model that is *already* unserializable. A sealed or polymorphic subtype
declares a property whose JSON key equals the base's class discriminator, and
kotlinx-serialization refuses to encode it (`JsonEncodingException`). Like
`COVERAGE_GAP`, it is reported on every run until you fix it. It only fires when a
discriminator is actually emitted: under `classDiscriminatorMode = NONE` there is
nothing to collide with.

² A base type (open **or sealed**) can register a polymorphic **default
deserializer** in the `Json`'s `SerializersModule`. This is the common `Unknown`
sentinel idiom. The extractor records it as a fact about the model. With one
registered, an old reader that meets an unknown subtype (such as a newly added one)
decodes it as the sentinel instead of throwing. Decode succeeds but yields the
sentinel, not the real subtype, so the verdict is `WARN` (silent substitution), not
`SAFE`.

The sentinel only absorbs the new subtype's fields when the old reader has
`ignoreUnknownKeys`. A strict reader still throws on those fields, so the verdict
stays `BREAK`, the same as with no default deserializer. This mirrors how
`coerceInputValues` downgrades an added enum value.

## Rule reference

Each rule below shows the change as a diff, what happens on the wire in each
direction, and a link to the oracle test that proves the verdict against real
kotlinx-serialization. Only some rules have a worked example so far; the matrix
links the ones that do. Progress is tracked in
[#119](https://github.com/chrisjenx/serialkompat/issues/119).

### `PROPERTY_ADDED` { #property_added }

A field is added to a type. **Backward:** ✅ SAFE when the field is optional (has a
default). **Forward:** ❌ BREAK unless the reader sets `ignoreUnknownKeys`.
Config-aware.

```diff
 @Serializable
 data class OrderEvent(
     val id: String,
+    val note: String = "",
 )
```

!!! success "Backward — new reader ← old data"
    ```json
    {"id":"A1"}
    ```
    The added `note` defaults to `""`, so the payload decodes cleanly.

!!! failure "Forward — old reader ← new data"
    ```json
    {"id":"A1","note":"ship it"}
    ```
    Old code doesn't know `note`, so it throws `SerializationException: Encountered an
    unknown key 'note'`. Set `ignoreUnknownKeys` on the old reader to turn this into
    a silent drop.

**Proof:** [`adding an optional field — strict reader`](https://github.com/chrisjenx/serialkompat/blob/main/serialkompat-extractor/src/test/kotlin/com/chrisjenx/serialkompat/extractor/RoundTripOracleTest.kt) · [`adding an optional field — lenient old reader tolerates it forward`](https://github.com/chrisjenx/serialkompat/blob/main/serialkompat-extractor/src/test/kotlin/com/chrisjenx/serialkompat/extractor/RoundTripOracleTest.kt)

### `PROPERTY_REMOVED` { #property_removed }

A field is deleted from a type. **Backward:** ❌ BREAK, or ⚠️ WARN (silent drop) if
the reader sets `ignoreUnknownKeys`. **Forward:** ✅ SAFE if the field was optional.
Config-aware.

```diff
 @Serializable
 data class OrderEvent(
     val id: String,
-    val note: String = "",
 )
```

!!! success "Forward — old reader ← new data"
    ```json
    {"id":"A1"}
    ```
    In old code `note` was optional, so it takes its default and the payload decodes
    cleanly.

!!! failure "Backward — new reader ← old data"
    ```json
    {"id":"A1","note":"hi"}
    ```
    New code no longer has `note`, so it throws `SerializationException: Encountered
    an unknown key 'note'`. With `ignoreUnknownKeys` the value is silently dropped
    instead (⚠️).

**Proof:** [`removing an optional field`](https://github.com/chrisjenx/serialkompat/blob/main/serialkompat-extractor/src/test/kotlin/com/chrisjenx/serialkompat/extractor/RoundTripOracleTest.kt)

### `ENUM_VALUE_ADDED` { #enum_value_added }

A constant is added to an enum. **Backward:** ✅ SAFE. **Forward:** ❌ BREAK, or
⚠️ WARN if the reader coerces *and* the reading field has a default. Config-aware.

```diff
 @Serializable
 enum class Status {
     ACTIVE,
     CLOSED,
+    ARCHIVED,
 }
```

!!! success "Backward — new reader ← old data"
    ```json
    "CLOSED"
    ```
    Every old value still exists in the new enum, so the payload decodes cleanly.

!!! failure "Forward — old reader ← new data"
    ```json
    "ARCHIVED"
    ```
    Old code has no `ARCHIVED`, so it throws `SerializationException: Status does not
    contain element with name 'ARCHIVED'`. With `coerceInputValues` **and** a
    defaulted reading field, the value silently becomes that default instead (⚠️).

**Proof:** [`adding an enum value`](https://github.com/chrisjenx/serialkompat/blob/main/serialkompat-extractor/src/test/kotlin/com/chrisjenx/serialkompat/extractor/RoundTripOracleTest.kt) · [`an added enum value on a DEFAULTED field is a coercing-reader WARN, a strict-reader BREAK (#129)`](https://github.com/chrisjenx/serialkompat/blob/main/serialkompat-extractor/src/test/kotlin/com/chrisjenx/serialkompat/extractor/RoundTripOracleTest.kt)

## Config awareness

The classifier reads your actual `Json { }` config (set with `jsonInstance`, see
[Configuration](configuration.md)). It doesn't assume defaults. The same change can
be `SAFE` under one config and `BREAK` under another:

| Setting | Default | Flips |
|---|---|---|
| `ignoreUnknownKeys` | `false` | `PROPERTY_ADDED` forward: `BREAK` → `SAFE` once the old reader tolerates unknown keys. `PROPERTY_REMOVED` backward: `BREAK` → `WARN`, because the new reader silently drops the data instead of throwing. `SUBTYPE_ADDED` forward: `BREAK` → `WARN`, but only when the base also registers a default deserializer (see note ² above). Tightening the setting from `true` to `false` is itself a `CONFIG_READER_STRICTNESS` `WARN`. |
| `encodeDefaults` | `false` | `PROPERTY_OPTIONALITY` forward (field became optional): `BREAK` → `SAFE`, because the old reader now receives the field instead of missing it. A per-field `@EncodeDefault` overrides the setting: `NEVER` keeps it a `BREAK`, and `ALWAYS` makes it `SAFE` even when `encodeDefaults` is `false`. The extractor reads `@EncodeDefault` from the compiled class. If it can't (for example, with a hand-written serializer), `encodeDefaults = true` only downgrades to `WARN`, never `SAFE`. Disabling the setting is itself a `CONFIG_ENCODE_DEFAULTS` `WARN` forward. |
| `explicitNulls` | `true` | `PROPERTY_NULLABILITY` forward (field became nullable): `BREAK` when the new writer has `true`, because the old reader gets an explicit `null` it can't accept. `WARN` when `false`, because the field is just omitted. With `false`, a reader also decodes an *absent* nullable field as `null`, with no default needed. So for a **nullable** field, `PROPERTY_ADDED` backward flips `BREAK` → `SAFE` (a brand-new field, so `null` is the correct value). `PROPERTY_REMOVED` forward flips `BREAK` → `WARN`: the old reader silently sees `null` where data once lived, which is a silent substitution, not `SAFE`. Toggling the setting is itself `CONFIG_EXPLICIT_NULLS`, a `WARN` in both directions. |
| `coerceInputValues` | `false` | `ENUM_VALUE_ADDED` forward: `BREAK` → `WARN`, but **only when the reading field has a default** to coerce to. The extractor records this per field; config alone isn't enough. An unknown constant then becomes that default: decode succeeds but yields the default, not the written value, so it's a `WARN` (silent substitution), never `SAFE`. A required field, a `List`/`Map` element, or a top-level enum has no default, so it still throws (`BREAK`). Disabling the setting is itself a `CONFIG_COERCE_INPUT` `WARN` backward. |
| `namingStrategy` | none | Any change is a blanket `CONFIG_NAMING_STRATEGY` `BREAK` in both directions, because every generated JSON key moves at once. |
| `classDiscriminator` | `"type"` | Any change is a blanket `CONFIG_DISCRIMINATOR` `BREAK` in both directions, because every polymorphic payload's discriminator key moves at once. Changing `classDiscriminatorMode` is the same `BREAK`. |

## The oracle guarantee

Every rule above is backed by a round-trip oracle test. The test serializes a
payload with the old model and decodes it with the new one (and the reverse), using
real kotlinx-serialization under the declared `Json` config. It then checks that
the classifier predicted what actually happened. The rules come from the library's
real runtime behavior, config by config, not from reading its source or spec.

`COVERAGE_GAP` is where that guarantee shows. When the extractor can't fully
analyse a type, it records it as `OPAQUE` and never guesses `SAFE`. It reports a
`WARN` and asks you to look. Unanalysable is never treated as compatible.

## Keeping this page in sync

The matrix is hand-written, and two build gates keep it honest:

- `checkRulesDoc` fails the build if any rule the classifier ships is missing
  from this page.
- `checkRulesProof` fails the build if a **Proof** link cites an oracle test that
  doesn't exist.

Not every rule has a [Rule reference](#rule-reference) section yet. Once they all
do, `checkRulesProof` will also fail if a rule ships without one.
