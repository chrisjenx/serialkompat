# Rules

This page lists every rule the classifier applies. Each rule decides how serious
one kind of change is, in each direction, under your `Json { }` config. The rules
run on every change the [differ](architecture.md#the-diff) finds between two snapshots (the
extracted wire schema of your models at two points in time).

## Severity

| Glyph | Severity | Meaning |
|---|---|---|
| ✅ | `SAFE` | No impact. The change round-trips cleanly. |
| ⚠️ | `WARN` | Depends on config, or a silent semantic break: decode succeeds, but the data is wrong, defaulted, or dropped. No exception is thrown. |
| ❌ | `BREAK` | Decode fails. The reader throws. |

## Direction

Every verdict is given per direction: `BACKWARD` ([new code reads old
data](concepts.md#backward-vs-forward)), `FORWARD` ([old code reads new
data](concepts.md#backward-vs-forward)), or `FULL` (both, the default). See
[Which direction do I pick?](concepts.md#choosing-a-direction).

## The matrix

The verdicts below assume kotlinx-serialization defaults unless a cell says
otherwise: `ignoreUnknownKeys = false`, `encodeDefaults = false`,
`explicitNulls = true`, `coerceInputValues = false`.

"Config-aware" means the verdict changes under a non-default `Json { }`.
[Config awareness](#config-awareness) below explains exactly how.

In each cell, the **reader** is the side decoding the payload and the **writer** is
the side encoding it. Backward, the reader is your new code; forward, it's your old code.

Findings name each type by its serial name. A sealed or polymorphic subtype is
named `Base/sub` (for example `OrderEvent/created`), because a subtype's
`@SerialName` only has to be unique within its base.

| Rule | Detects | Backward | Forward | Config-aware |
|---|---|---|---|---|
| `CONTRACT_REMOVED` | Whole type deleted | ❌ BREAK | ❌ BREAK | — |
| [`PROPERTY_ADDED`](#property_added) | Field added | ✅ SAFE if optional (or nullable & reader `explicitNulls=false`), ❌ BREAK otherwise | ❌ BREAK unless reader has `ignoreUnknownKeys` | yes |
| [`PROPERTY_REMOVED`](#property_removed) | Field deleted | ⚠️ WARN if reader has `ignoreUnknownKeys` (silent drop), else ❌ BREAK | ✅ SAFE if it was optional, ⚠️ WARN if nullable & reader `explicitNulls=false` (absent → null), else ❌ BREAK | yes |
| `PROPERTY_OPTIONALITY` | Optional ↔ required | ❌ BREAK if became required, ✅ SAFE if became optional | ✅ SAFE if became required; if became optional: ❌ BREAK unless the new writer always emits it (`@EncodeDefault(ALWAYS)`, or `encodeDefaults` without `@EncodeDefault(NEVER)`), ⚠️ WARN if `encodeDefaults` but the field's `@EncodeDefault` couldn't be read | yes |
| `PROPERTY_NULLABILITY` | Nullable ↔ non-null | ✅ SAFE if became nullable, ❌ BREAK if became non-null | ❌ BREAK if became nullable & writer `explicitNulls = true`; ⚠️ WARN if `false` | yes |
| `PROPERTY_TYPE_CHANGED` | Field type changed, including a shape change around a generic type parameter (`List<T>` → `T`) | ✅ SAFE if numeric widening, else ❌ BREAK | ❌ BREAK | no |
| `PROPERTY_JSON_NAMES` | `@JsonNames` alias dropped | ⚠️ WARN | ✅ SAFE | no |
| [`ENUM_VALUE_ADDED`](#enum_value_added) | Enum value added | ✅ SAFE | ❌ BREAK, ⚠️ WARN if the reader coerces **and** every field reading the enum (in scope or not) has a default | yes |
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
| `CONFIG_FORMAT` | The wire format changed (JSON ↔ CBOR). No contract is compared³ | ⚠️ WARN | ⚠️ WARN | — |
| `CONFIG_CBOR_BYTE_STRING` | CBOR `alwaysUseByteString` toggled (every plain `ByteArray` changes encoding) | ❌ BREAK | ❌ BREAK | — |
| `CONFIG_CBOR_DEFINITE_LENGTH` | CBOR `useDefiniteLengthEncoding` toggled | ✅ SAFE | ⚠️ WARN if enabled (an all-default object in a nullable slot reads back as `null`), ✅ SAFE if disabled | — |
| `CONFIG_CBOR_TAGS` | A CBOR `encode*Tags` / `verify*Tags` flag toggled | ❌ BREAK if the new reader verifies tags the old writer didn't encode, else ✅ SAFE | ❌ BREAK if the old reader verifies tags the new writer doesn't encode, else ✅ SAFE | — |
| [`PROPERTY_CBOR_LABEL`](#property_cbor_label) | CBOR `@CborLabel` added, removed or changed | ❌ BREAK if the old writer wrote a label the new reader doesn't know (⚠️ WARN if the reader has `ignoreUnknownKeys` and the field is optional), else ✅ SAFE | the same, from the new writer to the old reader | yes⁴ |
| `PROPERTY_BYTE_STRING` | CBOR `@ByteString` added or removed on a field | ❌ BREAK unless `alwaysUseByteString` makes it a no-op | ❌ BREAK unless `alwaysUseByteString` makes it a no-op | yes |
| `CBOR_TAGS_CHANGED` | CBOR `@KeyTags` / `@ValueTags` / `@ObjectTags` changed | ❌ BREAK if the new reader verifies and declares tags that differ from what the old writer encoded, else ✅ SAFE | the same, from the new writer to the old reader | yes |
| [`CBOR_ARRAY_LAYOUT`](#cbor_array_layout) | CBOR `@CborArray` added, removed, or its elements added, removed or reordered | ✅ SAFE if the new layout only appends optional elements, else ❌ BREAK | ✅ SAFE if the new layout only drops trailing optional elements, else ❌ BREAK (even with `ignoreUnknownKeys`) | no |
| `CBOR_ARRAY_OMITTED_DEFAULT` | A `@CborArray` element, not the last, that is omitted when it holds its default (shifting every later position) | ❌ BREAK (⚠️ WARN if its `@EncodeDefault` mode wasn't recorded) | ❌ BREAK (⚠️ WARN if its `@EncodeDefault` mode wasn't recorded) | no¹ |
| `COVERAGE_GAP` | Opaque/unanalyzable type, or one that was opaque in the baseline and is analysed now | ⚠️ WARN | ⚠️ WARN | — |

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

³ Snapshots of different formats are judged by different rules, so the gate compares
nothing else and reports the format change once. This usually means the change
adopts CBOR (or JSON) in serialkompat while the baseline ref still checks the other
format. If the stored data itself changes format, old readers can't decode it;
migrate it explicitly.

⁴ A CBOR label reaches the wire only when the writer sets `preferCborLabelsOverNames`.
Otherwise the writer writes the field name, which every reader accepts, so a label
change is inert. `CBOR_ARRAY_OMITTED_DEFAULT` is, like `DISCRIMINATOR_COLLISION`
(¹), a defect of one model reported on every run.

## Rule reference

<a id="the-oracle-guarantee"></a><a id="keeping-this-page-in-sync"></a>
How each verdict is proven is on [The oracle](oracle.md); the build gates that keep this
page in sync are in [Contributing → Docs site](contributing.md#docs-site).

Each rule below shows the change as a diff, what happens on the wire in each
direction, and a link to the oracle test that proves the verdict against real
kotlinx-serialization (see [The oracle](oracle.md)). Only some rules have a worked example so far; the matrix
links the ones that do.

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

**Proof:** [`adding an optional field — strict reader`](https://github.com/chrisjenx/serialkompat/blob/main/serialkompat-extractor/src/test/kotlin/com/chrisjenx/serialkompat/extractor/RoundTripOracleTest.kt) · [`adding an optional field — lenient old reader tolerates it forward`](https://github.com/chrisjenx/serialkompat/blob/main/serialkompat-extractor/src/test/kotlin/com/chrisjenx/serialkompat/extractor/RoundTripOracleTest.kt) · [`CBOR - an added field breaks a strict old reader of a newer cache entry`](https://github.com/chrisjenx/serialkompat/blob/main/serialkompat-extractor/src/test/kotlin/com/chrisjenx/serialkompat/extractor/CborRoundTripOracleTest.kt)

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

**Proof:** [`removing an optional field`](https://github.com/chrisjenx/serialkompat/blob/main/serialkompat-extractor/src/test/kotlin/com/chrisjenx/serialkompat/extractor/RoundTripOracleTest.kt) · [`CBOR - a removed field breaks a strict new reader of old entries`](https://github.com/chrisjenx/serialkompat/blob/main/serialkompat-extractor/src/test/kotlin/com/chrisjenx/serialkompat/extractor/CborRoundTripOracleTest.kt)

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

The `WARN` needs *every* field that reads the enum to have a default. serialkompat
checks every reader in the baseline snapshot, not just the types in your `include` /
`exclude` scope. A required reader in an out-of-scope type, even one another module
owns, still keeps the verdict at `BREAK`.

**Proof:** [`adding an enum value`](https://github.com/chrisjenx/serialkompat/blob/main/serialkompat-extractor/src/test/kotlin/com/chrisjenx/serialkompat/extractor/RoundTripOracleTest.kt) · [`an added enum value on a DEFAULTED field is a coercing-reader WARN, a strict-reader BREAK (#129)`](https://github.com/chrisjenx/serialkompat/blob/main/serialkompat-extractor/src/test/kotlin/com/chrisjenx/serialkompat/extractor/RoundTripOracleTest.kt) · [`CBOR - an added enum value breaks old readers that meet it`](https://github.com/chrisjenx/serialkompat/blob/main/serialkompat-extractor/src/test/kotlin/com/chrisjenx/serialkompat/extractor/CborRoundTripOracleTest.kt)

### `PROPERTY_CBOR_LABEL` { #property_cbor_label }

A CBOR field's `@CborLabel` integer key changes. Config-aware: it matters only when
the writer sets `preferCborLabelsOverNames`. A reader accepts the field's name or its
own label, so a label it doesn't know is an unknown key.

```diff
 @Serializable
 data class CacheEntry(
-    @CborLabel(1) val id: String,
+    @CborLabel(2) val id: String,
 )
```

!!! failure "Both directions, with `preferCborLabelsOverNames`"
    The writer keys `id` by its label. The other side expects the other label, so a
    strict reader throws on the unknown key and then on the missing field. With
    `ignoreUnknownKeys` and an optional field, the value is silently replaced by the
    default instead (⚠️).

Adding a label breaks only old readers (new readers still accept the name old data
used). Removing one breaks only new readers.

**Proof:** [`CBOR - a changed label breaks both ways once labels are written`](https://github.com/chrisjenx/serialkompat/blob/main/serialkompat-extractor/src/test/kotlin/com/chrisjenx/serialkompat/extractor/CborRoundTripOracleTest.kt) · [`CBOR - a changed label is inert while names are written`](https://github.com/chrisjenx/serialkompat/blob/main/serialkompat-extractor/src/test/kotlin/com/chrisjenx/serialkompat/extractor/CborRoundTripOracleTest.kt) · [`CBOR - adding a label breaks only old readers`](https://github.com/chrisjenx/serialkompat/blob/main/serialkompat-extractor/src/test/kotlin/com/chrisjenx/serialkompat/extractor/CborRoundTripOracleTest.kt) · [`CBOR - a changed label on an optional field silently defaults for a tolerant reader`](https://github.com/chrisjenx/serialkompat/blob/main/serialkompat-extractor/src/test/kotlin/com/chrisjenx/serialkompat/extractor/CborRoundTripOracleTest.kt)

### `CBOR_ARRAY_LAYOUT` { #cbor_array_layout }

A `@CborArray` class is encoded as a positional array, not a map. Names don't reach
the wire; positions do.

```diff
 @Serializable
 @CborArray
 data class Point(
     val x: Int,
     val y: Int,
+    val z: Int = 0,
 )
```

!!! success "Backward — new reader ← old data"
    The old two-element array is shorter than the new layout. The missing `z` is
    optional, so it takes its default.

!!! failure "Forward — old reader ← new data"
    The old reader meets a third element it has no slot for and throws, **even with
    `ignoreUnknownKeys`**. That is unlike a keyed class, where an added optional field
    is safe for a tolerant reader.

Reordering elements is a ❌ BREAK both ways even when the types line up: the values
silently swap. Adding or removing `@CborArray` swaps a map for an array, so each side
rejects the other.

**Proof:** [`CBOR - appending an optional array element crashes old readers, even tolerant ones`](https://github.com/chrisjenx/serialkompat/blob/main/serialkompat-extractor/src/test/kotlin/com/chrisjenx/serialkompat/extractor/CborRoundTripOracleTest.kt) · [`CBOR - dropping a trailing optional array element breaks only new readers`](https://github.com/chrisjenx/serialkompat/blob/main/serialkompat-extractor/src/test/kotlin/com/chrisjenx/serialkompat/extractor/CborRoundTripOracleTest.kt) · [`CBOR - reordering array elements silently swaps values and is flagged`](https://github.com/chrisjenx/serialkompat/blob/main/serialkompat-extractor/src/test/kotlin/com/chrisjenx/serialkompat/extractor/CborRoundTripOracleTest.kt) · [`CBOR - toggling CborArray breaks both ways`](https://github.com/chrisjenx/serialkompat/blob/main/serialkompat-extractor/src/test/kotlin/com/chrisjenx/serialkompat/extractor/CborRoundTripOracleTest.kt) · [`CBOR - an array element omitted at its default misreads its own data and is flagged`](https://github.com/chrisjenx/serialkompat/blob/main/serialkompat-extractor/src/test/kotlin/com/chrisjenx/serialkompat/extractor/CborRoundTripOracleTest.kt)

## Config awareness

The classifier reads your actual `Json { }` config (set with `jsonInstance`, see
[Configuration](configuration.md)), or your `Cbor { }` config (`cborInstance`, see
[CBOR](cbor.md)). It doesn't assume defaults. The same change can be `SAFE` under one
config and `BREAK` under another. `ignoreUnknownKeys` and `encodeDefaults` mean the
same under CBOR; the other settings below are JSON-only:

| Setting | Default | Flips |
|---|---|---|
| `ignoreUnknownKeys` | `false` | `PROPERTY_ADDED` forward: `BREAK` → `SAFE` once the old reader tolerates unknown keys. `PROPERTY_REMOVED` backward: `BREAK` → `WARN`, because the new reader silently drops the data instead of throwing. `SUBTYPE_ADDED` forward: `BREAK` → `WARN`, but only when the base also registers a default deserializer (see note ² above). Tightening the setting from `true` to `false` is itself a `CONFIG_READER_STRICTNESS` `WARN`. |
| `encodeDefaults` | `false` | `PROPERTY_OPTIONALITY` forward (field became optional): `BREAK` → `SAFE`, because the old reader now receives the field instead of missing it. A per-field `@EncodeDefault` overrides the setting: `NEVER` keeps it a `BREAK`, and `ALWAYS` makes it `SAFE` even when `encodeDefaults` is `false`. The extractor reads `@EncodeDefault` from the compiled class. If it can't (for example, with a hand-written serializer), `encodeDefaults = true` only downgrades to `WARN`, never `SAFE`. Disabling the setting is itself a `CONFIG_ENCODE_DEFAULTS` `WARN` forward. |
| `explicitNulls` | `true` | `PROPERTY_NULLABILITY` forward (field became nullable): `BREAK` when the new writer has `true`, because the old reader gets an explicit `null` it can't accept. `WARN` when `false`, because the field is just omitted. With `false`, a reader also decodes an *absent* nullable field as `null`, with no default needed. So for a **nullable** field, `PROPERTY_ADDED` backward flips `BREAK` → `SAFE` (a brand-new field, so `null` is the correct value). `PROPERTY_REMOVED` forward flips `BREAK` → `WARN`: the old reader silently sees `null` where data once lived, which is a silent substitution, not `SAFE`. Toggling the setting is itself `CONFIG_EXPLICIT_NULLS`, a `WARN` in both directions. |
| `coerceInputValues` | `false` | `ENUM_VALUE_ADDED` forward: `BREAK` → `WARN`, but **only when the reading field has a default** to coerce to. The extractor records this per field; config alone isn't enough. An unknown constant then becomes that default: decode succeeds but yields the default, not the written value, so it's a `WARN` (silent substitution), never `SAFE`. A required field, a `List`/`Map` element, or a top-level enum has no default, so it still throws (`BREAK`). Disabling the setting is itself a `CONFIG_COERCE_INPUT` `WARN` backward. |
| `namingStrategy` | none | Any change is a blanket `CONFIG_NAMING_STRATEGY` `BREAK` in both directions, because every generated JSON key moves at once. |
| `classDiscriminator` | `"type"` | Any change is a blanket `CONFIG_DISCRIMINATOR` `BREAK` in both directions, because every polymorphic payload's discriminator key moves at once. Changing `classDiscriminatorMode` is the same `BREAK`. |
| `useArrayPolymorphism` | `false` | Toggling it is a `CONFIG_ARRAY_POLYMORPHISM` `BREAK` in both directions. Every polymorphic payload changes between `{"type":..}` objects and `["..",{..}]` arrays, and each reader rejects the other shape. |
| `allowStructuredMapKeys` | `false` | Toggling it is `CONFIG_STRUCTURED_MAP_KEYS`. A reader without it rejects maps with non-primitive keys, so it's a `BREAK` on the side that lacks it: backward if disabled, forward if enabled. |
| `allowSpecialFloatingPointValues` | `false` | Toggling it is `CONFIG_SPECIAL_FLOATS`. A reader without it rejects `NaN` and `Infinity`, so it's a `WARN` (only payloads carrying those values fail) on the side that lacks it: backward if disabled, forward if enabled. |
| `isLenient`, `decodeEnumsCaseInsensitive`, `allowTrailingComma`, `allowComments` | `false` | Reader-only. kotlinx writes the same bytes either way, but turning one off makes the new reader reject input the old one accepted (for example from a non-kotlinx producer). That is a `CONFIG_READER_STRICTNESS` `WARN` backward; turning one on is `SAFE`. |
