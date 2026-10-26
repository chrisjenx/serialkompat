# Rules

This page lists every rule the classifier applies. Each rule decides how serious
one kind of change is, in each direction, under your `Json { }` (or `Cbor { }`) config.
The rules run on every change the [differ](architecture.md#the-diff) finds between two
snapshots (the extracted wire schema of your models at two points in time).

## Severity

| Glyph | Severity | Meaning |
|---|---|---|
| ✅ | `SAFE` | No impact. The change round-trips cleanly. |
| ⚠️ | `WARN` | Depends on config, can't be verified (an unanalysable type), or a silent semantic break: decode succeeds, but the data is wrong, defaulted, or dropped. |
| ❌ | `BREAK` | Decode fails. The reader throws. |

## Direction

Every verdict is given per direction: `BACKWARD` ([new code reads old
data](concepts.md#backward-vs-forward)), `FORWARD` ([old code reads new
data](concepts.md#backward-vs-forward)), or `FULL` (both, the default). See
[Which direction do I pick?](concepts.md#choosing-a-direction).

In each direction, the **reader** is the side decoding the payload and the **writer** is
the side encoding it. Backward, the reader is your new code; forward, it's your old code.

## The matrix

Each row links to the rule's section, which has the exact conditions, an example and
the test that proves the verdict. Where a cell shows two or three glyphs, the verdict
depends on the model or the config; the "When" column names the deciding condition.
Defaults are assumed unless a row says otherwise: `ignoreUnknownKeys = false`,
`encodeDefaults = false`, `explicitNulls = true`, `coerceInputValues = false`.
[Config awareness](#config-awareness) lists which settings move which verdicts.

Findings name each type by its serial name. A sealed or polymorphic subtype is
named `Base/sub` (for example `OrderEvent/created`), because a subtype's
`@SerialName` only has to be unique within its base.

### Properties and types { #matrix-properties }

These apply to JSON and CBOR alike, except `PROPERTY_JSON_NAMES` (JSON only).

| Rule | Backward | Forward | When |
|---|---|---|---|
| [`CONTRACT_REMOVED`](#contract_removed) | ❌ | ❌ | A whole `@Serializable` type is deleted |
| [`PROPERTY_ADDED`](#property_added) | ✅ / ❌ | ✅ / ❌ | ✅ with a default (backward), `ignoreUnknownKeys` (forward) |
| [`PROPERTY_REMOVED`](#property_removed) | ⚠️ / ❌ | ✅ / ⚠️ / ❌ | ⚠️ under `ignoreUnknownKeys`; forward ✅ if it had a default |
| [`PROPERTY_OPTIONALITY`](#property_optionality) | ✅ / ❌ | ✅ / ⚠️ / ❌ | Became required ❌ backward; became optional depends on the writer |
| [`PROPERTY_NULLABILITY`](#property_nullability) | ✅ / ❌ | ✅ / ⚠️ / ❌ | Became non-null ❌ backward; became nullable ❌ or ⚠️ forward |
| [`PROPERTY_JSON_NAMES`](#property_json_names) | ⚠️ | ✅ | A `@JsonNames` alias is dropped |
| [`PROPERTY_TYPE_CHANGED`](#property_type_changed) | ✅ / ❌ | ❌ | Backward ✅ only for a numeric widening |
| [`COVERAGE_GAP`](#coverage_gap) | ⚠️ | ⚠️ | A type serialkompat can't analyse or pair (every run) |

### Enums { #matrix-enums }

| Rule | Backward | Forward | When |
|---|---|---|---|
| [`ENUM_VALUE_ADDED`](#enum_value_added) | ✅ | ⚠️ / ❌ | ⚠️ if the reader coerces into a defaulted field |
| [`ENUM_VALUE_REMOVED`](#enum_value_removed) | ❌ | ✅ | An enum constant is removed |

### Polymorphism { #matrix-polymorphism }

| Rule | Backward | Forward | When |
|---|---|---|---|
| [`SUBTYPE_ADDED`](#subtype_added) | ✅ | ⚠️ / ❌ | ⚠️ with a default deserializer and `ignoreUnknownKeys` |
| [`SUBTYPE_REMOVED`](#subtype_removed) | ❌ | ✅ | A sealed or polymorphic subtype is removed |
| [`DISCRIMINATOR_CHANGED`](#discriminator_changed) | ❌ | ❌ | A base's discriminator key changed |
| [`DISCRIMINATOR_VALUE_CHANGED`](#discriminator_value_changed) | ❌ | ❌ | A sealed or polymorphic base moved through `renames` |
| [`DISCRIMINATOR_COLLISION`](#discriminator_collision) | ❌ | ❌ | A subtype property shadows the discriminator (every run) |

### Config { #matrix-config }

| Rule | Backward | Forward | When |
|---|---|---|---|
| [`CONFIG_NAMING_STRATEGY`](#config_naming_strategy) | ❌ | ❌ | `namingStrategy` changed |
| [`CONFIG_DISCRIMINATOR`](#config_discriminator) | ❌ | ❌ | `classDiscriminator` or `classDiscriminatorMode` changed |
| [`CONFIG_READER_STRICTNESS`](#config_reader_strictness) | ✅ / ⚠️ | ✅ | ⚠️ when a reader-only acceptance flag is turned off |
| [`CONFIG_ENCODE_DEFAULTS`](#config_encode_defaults) | ✅ | ✅ / ⚠️ | ⚠️ when the writer stops encoding defaults |
| [`CONFIG_EXPLICIT_NULLS`](#config_explicit_nulls) | ⚠️ / ❌ | ⚠️ / ❌ | ❌ where omitted nulls meet a nullable no-default field |
| [`CONFIG_COERCE_INPUT`](#config_coerce_input) | ✅ / ⚠️ | ✅ | ⚠️ when the reader stops coercing |
| [`CONFIG_ARRAY_POLYMORPHISM`](#config_array_polymorphism) | ❌ | ❌ | `useArrayPolymorphism` toggled |
| [`CONFIG_STRUCTURED_MAP_KEYS`](#config_structured_map_keys) | ✅ / ❌ | ✅ / ❌ | ❌ on the side whose reader lacks it |
| [`CONFIG_SPECIAL_FLOATS`](#config_special_floats) | ✅ / ⚠️ | ✅ / ⚠️ | ⚠️ on the side whose reader lacks it |
| [`CONFIG_FORMAT`](#config_format) | ⚠️ | ⚠️ | Baseline and current use different formats |
| [`CONFIG_CHANGED`](#config_changed) | ⚠️ | ⚠️ | Catch-all; no current setting reaches it |

### CBOR-only { #matrix-cbor }

| Rule | Backward | Forward | When |
|---|---|---|---|
| [`CONFIG_CBOR_BYTE_STRING`](#config_cbor_byte_string) | ❌ | ❌ | `alwaysUseByteString` toggled |
| [`CONFIG_CBOR_DEFINITE_LENGTH`](#config_cbor_definite_length) | ✅ | ✅ / ⚠️ | ⚠️ when definite-length encoding is turned on |
| [`CONFIG_CBOR_TAGS`](#config_cbor_tags) | ✅ / ❌ | ✅ / ❌ | ❌ when a verifying reader meets untagged data |
| [`PROPERTY_CBOR_LABEL`](#property_cbor_label) | ✅ / ⚠️ / ❌ | ✅ / ⚠️ / ❌ | Matters only once the writer prefers labels |
| [`PROPERTY_BYTE_STRING`](#property_byte_string) | ❌ | ❌ | `@ByteString` toggled on a field |
| [`CBOR_TAGS_CHANGED`](#cbor_tags_changed) | ✅ / ❌ | ✅ / ❌ | ❌ where tags are encoded, verified and differ |
| [`CBOR_ARRAY_LAYOUT`](#cbor_array_layout) | ✅ / ❌ | ✅ / ❌ | ✅ only for an optional tail appended or dropped |
| [`CBOR_ARRAY_OMITTED_DEFAULT`](#cbor_array_omitted_default) | ⚠️ / ❌ | ⚠️ / ❌ | A middle `@CborArray` element is omitted at its default |

## Rule reference

<a id="the-oracle-guarantee"></a><a id="keeping-this-page-in-sync"></a>
Each rule below says what it detects, shows the change as a diff, shows what happens on
the wire in each direction, and lists the config that moves its verdict. Its **Proof**
line links the tests that check the verdict against real kotlinx-serialization: see
[The oracle](oracle.md) for how, and [Contributing → Docs site](contributing.md#docs-site)
for the build gates that keep this page in sync with the classifier.

### `CONTRACT_REMOVED` { #contract_removed }

A whole `@Serializable` type disappeared from the snapshot.

```diff
-@Serializable
-data class Refund(val id: String)
```

!!! success "Instead"
    Keep the type while stored data or peers still use it. Adding a type is always safe.

!!! failure "Both directions"
    New code can't decode old `Refund` payloads, and old code still expects them. ❌ BREAK
    both ways.

**Proof:** [`a removed @Serializable contract is a real BREAK both ways`](https://github.com/chrisjenx/serialkompat/blob/main/serialkompat-extractor/src/test/kotlin/com/chrisjenx/serialkompat/extractor/RoundTripOracleTest.kt)

### `PROPERTY_ADDED` { #property_added }

A field is added to a type.

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
    The added `note` defaults to `""`, so the payload decodes cleanly. Without a default,
    the new reader throws `MissingFieldException` (❌).

!!! failure "Forward — old reader ← new data"
    ```json
    {"id":"A1","note":"ship it"}
    ```
    Old code doesn't know `note`, so it throws `SerializationException: Encountered an
    unknown key 'note'`. Set `ignoreUnknownKeys` on the old reader to make it skip the key
    (✅).

Config effects:

- `ignoreUnknownKeys` on the old reader makes forward ✅.
- `explicitNulls = false` on the new reader makes backward ✅ for a nullable field with no
  default: an absent nullable field decodes as `null`. JSON only; in CBOR only a default
  helps.

**Proof:** [`nullable no-default field absence is a real break under explicitNulls=true, tolerated under false (#118)`](https://github.com/chrisjenx/serialkompat/blob/main/serialkompat-extractor/src/test/kotlin/com/chrisjenx/serialkompat/extractor/RoundTripOracleTest.kt) · [`adding an optional field — strict reader`](https://github.com/chrisjenx/serialkompat/blob/main/serialkompat-extractor/src/test/kotlin/com/chrisjenx/serialkompat/extractor/RoundTripOracleTest.kt) · [`adding an optional field — lenient old reader tolerates it forward`](https://github.com/chrisjenx/serialkompat/blob/main/serialkompat-extractor/src/test/kotlin/com/chrisjenx/serialkompat/extractor/RoundTripOracleTest.kt) · [`CBOR - an added field breaks a strict old reader of a newer cache entry`](https://github.com/chrisjenx/serialkompat/blob/main/serialkompat-extractor/src/test/kotlin/com/chrisjenx/serialkompat/extractor/CborRoundTripOracleTest.kt) · [`CBOR - an added optional field is safe for an ignoreUnknownKeys reader`](https://github.com/chrisjenx/serialkompat/blob/main/serialkompat-extractor/src/test/kotlin/com/chrisjenx/serialkompat/extractor/CborRoundTripOracleTest.kt) · [`CBOR - an added required field breaks new readers of old entries`](https://github.com/chrisjenx/serialkompat/blob/main/serialkompat-extractor/src/test/kotlin/com/chrisjenx/serialkompat/extractor/CborRoundTripOracleTest.kt) · [`CBOR - an added nullable field with no default breaks new readers of old entries`](https://github.com/chrisjenx/serialkompat/blob/main/serialkompat-extractor/src/test/kotlin/com/chrisjenx/serialkompat/extractor/CborRoundTripOracleTest.kt)

### `PROPERTY_REMOVED` { #property_removed }

A field is deleted from a type. A field rename shows up as this plus `PROPERTY_ADDED`.

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
    In old code `note` had a default, so it takes it and the payload decodes cleanly. Had
    `note` been required, the old reader would throw (❌).

!!! failure "Backward — new reader ← old data"
    ```json
    {"id":"A1","note":"hi"}
    ```
    New code no longer has `note`, so it throws `SerializationException: Encountered
    an unknown key 'note'`. With `ignoreUnknownKeys` the value is silently dropped
    instead (⚠️).

Config effects:

- `ignoreUnknownKeys` on the new reader turns backward ❌ into ⚠️ (silent data loss).
- `explicitNulls = false` on the old reader turns forward ❌ into ⚠️ for a nullable field
  with no default: the old reader silently sees `null` where data used to be.

**Proof:** [`removing an optional field`](https://github.com/chrisjenx/serialkompat/blob/main/serialkompat-extractor/src/test/kotlin/com/chrisjenx/serialkompat/extractor/RoundTripOracleTest.kt) · [`a field rename silently loses data under a lenient reader — flagged at least WARN`](https://github.com/chrisjenx/serialkompat/blob/main/serialkompat-extractor/src/test/kotlin/com/chrisjenx/serialkompat/extractor/RoundTripOracleTest.kt) · [`nullable no-default field absence is a real break under explicitNulls=true, tolerated under false (#118)`](https://github.com/chrisjenx/serialkompat/blob/main/serialkompat-extractor/src/test/kotlin/com/chrisjenx/serialkompat/extractor/RoundTripOracleTest.kt) · [`CBOR - a removed field breaks a strict new reader of old entries`](https://github.com/chrisjenx/serialkompat/blob/main/serialkompat-extractor/src/test/kotlin/com/chrisjenx/serialkompat/extractor/CborRoundTripOracleTest.kt) · [`CBOR - a removed field is a silent drop for an ignoreUnknownKeys reader`](https://github.com/chrisjenx/serialkompat/blob/main/serialkompat-extractor/src/test/kotlin/com/chrisjenx/serialkompat/extractor/CborRoundTripOracleTest.kt) · [`CBOR - a removed required field breaks old readers of new entries`](https://github.com/chrisjenx/serialkompat/blob/main/serialkompat-extractor/src/test/kotlin/com/chrisjenx/serialkompat/extractor/CborRoundTripOracleTest.kt)

### `PROPERTY_OPTIONALITY` { #property_optionality }

A field gained or lost its default value.

```diff
 @Serializable
 data class OrderEvent(
     val id: String,
-    val note: String = "",
+    val note: String,
 )
```

!!! success "Forward — old reader ← new data (became required)"
    The new writer always sends `note`, which the old reader accepts. ✅ SAFE.

!!! failure "Backward — new reader ← old data (became required)"
    ```json
    {"id":"A1"}
    ```
    Old writers omitted `note` when it held its default, and the new reader now requires
    it: `MissingFieldException`. ❌ BREAK.

The reverse change, a required field that became optional, is ✅ backward. Forward, the old
reader still requires the field, so the verdict depends on whether the new writer always
sends it:

- `@EncodeDefault(ALWAYS)` on the field: ✅.
- `@EncodeDefault(NEVER)`: ❌, even with `encodeDefaults = true`.
- No `@EncodeDefault`: ✅ with `encodeDefaults = true` on the writer, else ❌.
- The mode couldn't be read (for example, a hand-written serializer): ⚠️ with
  `encodeDefaults = true`, else ❌.

**Proof:** [`optional becomes required`](https://github.com/chrisjenx/serialkompat/blob/main/serialkompat-extractor/src/test/kotlin/com/chrisjenx/serialkompat/extractor/RoundTripOracleTest.kt) · [`CBOR - a field becoming required breaks new readers of entries that omitted it`](https://github.com/chrisjenx/serialkompat/blob/main/serialkompat-extractor/src/test/kotlin/com/chrisjenx/serialkompat/extractor/CborRoundTripOracleTest.kt) · [`required becomes optional with @EncodeDefault(NEVER) breaks forward despite encodeDefaults`](https://github.com/chrisjenx/serialkompat/blob/main/serialkompat-extractor/src/test/kotlin/com/chrisjenx/serialkompat/extractor/RoundTripOracleTest.kt) · [`required becomes optional with @EncodeDefault(ALWAYS) is forward safe without encodeDefaults`](https://github.com/chrisjenx/serialkompat/blob/main/serialkompat-extractor/src/test/kotlin/com/chrisjenx/serialkompat/extractor/RoundTripOracleTest.kt) · [`required becomes optional without @EncodeDefault follows the writer's encodeDefaults`](https://github.com/chrisjenx/serialkompat/blob/main/serialkompat-extractor/src/test/kotlin/com/chrisjenx/serialkompat/extractor/RoundTripOracleTest.kt) · [`required to optional — an unrecorded @EncodeDefault is never assumed safe`](https://github.com/chrisjenx/serialkompat/blob/main/serialkompat-core/src/test/kotlin/com/chrisjenx/serialkompat/core/ClassifierTest.kt)

### `PROPERTY_NULLABILITY` { #property_nullability }

A field's type became nullable, or stopped being nullable.

```diff
 @Serializable
 data class OrderEvent(
-    val note: String?,
+    val note: String,
 )
```

!!! success "Forward — old reader ← new data (became non-null)"
    The new writer never sends `null`, and the old reader accepts any string. ✅ SAFE.

!!! failure "Backward — new reader ← old data (became non-null)"
    ```json
    {"note":null}
    ```
    Old data may hold `null`, which the new non-null field rejects. ❌ BREAK.

The reverse change, a field that became nullable, is ✅ backward. Forward depends on how the
new writer sends the null:

- `explicitNulls = true` (the default): it writes `"note":null`, which the old non-null
  reader rejects. ❌.
- `explicitNulls = false`: it omits the key. If the old field has a default, the old
  reader silently takes that default (⚠️). If it is required, the old reader throws
  `MissingFieldException` (❌).

CBOR has no `explicitNulls`, so it always behaves like `true`.

**Proof:** [`PROPERTY_NULLABILITY - became non-null breaks new readers of old nulls, old readers are fine`](https://github.com/chrisjenx/serialkompat/blob/main/serialkompat-extractor/src/test/kotlin/com/chrisjenx/serialkompat/extractor/RoundTripOracleTest.kt) · [`PROPERTY_NULLABILITY - became nullable breaks old readers of an explicit null`](https://github.com/chrisjenx/serialkompat/blob/main/serialkompat-extractor/src/test/kotlin/com/chrisjenx/serialkompat/extractor/RoundTripOracleTest.kt) · [`PROPERTY_NULLABILITY - became nullable without a default breaks old readers when nulls are omitted`](https://github.com/chrisjenx/serialkompat/blob/main/serialkompat-extractor/src/test/kotlin/com/chrisjenx/serialkompat/extractor/RoundTripOracleTest.kt) · [`PROPERTY_NULLABILITY - became nullable with a default and omitted nulls is a silent default`](https://github.com/chrisjenx/serialkompat/blob/main/serialkompat-extractor/src/test/kotlin/com/chrisjenx/serialkompat/extractor/RoundTripOracleTest.kt) · [`PROPERTY_NULLABILITY - became nullable with explicitNulls=false omits the null, a silent default`](https://github.com/chrisjenx/serialkompat/blob/main/serialkompat-extractor/src/test/kotlin/com/chrisjenx/serialkompat/extractor/RoundTripOracleTest.kt) · [`CBOR - a field becoming nullable breaks old readers of a written null`](https://github.com/chrisjenx/serialkompat/blob/main/serialkompat-extractor/src/test/kotlin/com/chrisjenx/serialkompat/extractor/CborRoundTripOracleTest.kt) · [`CBOR - a field becoming non-null breaks new readers of old nulls`](https://github.com/chrisjenx/serialkompat/blob/main/serialkompat-extractor/src/test/kotlin/com/chrisjenx/serialkompat/extractor/CborRoundTripOracleTest.kt)

### `PROPERTY_JSON_NAMES` { #property_json_names }

A field dropped a `@JsonNames` alias. Adding one is safe and reports nothing. JSON only.

```diff
 @Serializable
 data class Customer(
-    @JsonNames("legacy") val name: String,
+    val name: String,
 )
```

!!! success "Forward — old reader ← new data"
    The new writer sends `name`, which the old reader accepts. ✅ SAFE.

!!! failure "Backward — new reader ← old data"
    ```json
    {"legacy":"x"}
    ```
    A producer still sending the alias now hits an unknown key `legacy` (or, with
    `ignoreUnknownKeys`, a missing `name`). Only payloads that use the alias fail, so it
    is ⚠️ WARN.

**Proof:** [`PROPERTY_JSON_NAMES - dropping an alias warns new readers, old readers are fine`](https://github.com/chrisjenx/serialkompat/blob/main/serialkompat-extractor/src/test/kotlin/com/chrisjenx/serialkompat/extractor/RoundTripOracleTest.kt) · [`dropping a @JsonNames alias breaks a producer still sending that key`](https://github.com/chrisjenx/serialkompat/blob/main/serialkompat-extractor/src/test/kotlin/com/chrisjenx/serialkompat/extractor/RoundTripOracleTest.kt)

### `PROPERTY_TYPE_CHANGED` { #property_type_changed }

A field's type changed. This includes a shape change around a generic type parameter
(`List<T>` → `T`), and a `@Contextual` field whose module serializer was swapped for one
with a different wire shape.

```diff
 @Serializable
 data class LineItem(
-    val quantity: Int,
+    val quantity: Long,
 )
```

!!! success "Backward — new reader ← old data (numeric widening)"
    Every old `Int` fits in a `Long`. ✅ SAFE. The widenings are `Byte` → `Short`, `Int`
    or `Long`; `Short` → `Int` or `Long`; `Int` → `Long`; and `Float` → `Double`. Any
    other type change is ❌ backward.

!!! failure "Forward — old reader ← new data"
    ```json
    {"quantity":9223372036854775807}
    ```
    The old `Int` reader can't hold a large `Long`. ❌ BREAK, for every type change.

**Proof:** [`PROPERTY_TYPE_CHANGED - numeric widening is safe for new readers, a BREAK for old ones`](https://github.com/chrisjenx/serialkompat/blob/main/serialkompat-extractor/src/test/kotlin/com/chrisjenx/serialkompat/extractor/RoundTripOracleTest.kt) · [`incompatible type change (String to Int)`](https://github.com/chrisjenx/serialkompat/blob/main/serialkompat-extractor/src/test/kotlin/com/chrisjenx/serialkompat/extractor/RoundTripOracleTest.kt) · [`oracle - an envelope change around a hole is a BREAK both ways, matching the real library`](https://github.com/chrisjenx/serialkompat/blob/main/serialkompat-extractor/src/test/kotlin/com/chrisjenx/serialkompat/extractor/RoundTripOracleTest.kt) · [`a contextual serializer swapped in the module is a type change both ways`](https://github.com/chrisjenx/serialkompat/blob/main/serialkompat-extractor/src/test/kotlin/com/chrisjenx/serialkompat/extractor/RoundTripOracleTest.kt) · [`CBOR - Int to Long widens for new readers and breaks old readers of large values`](https://github.com/chrisjenx/serialkompat/blob/main/serialkompat-extractor/src/test/kotlin/com/chrisjenx/serialkompat/extractor/CborRoundTripOracleTest.kt) · [`CBOR - Int to String breaks both ways`](https://github.com/chrisjenx/serialkompat/blob/main/serialkompat-extractor/src/test/kotlin/com/chrisjenx/serialkompat/extractor/CborRoundTripOracleTest.kt)

### `COVERAGE_GAP` { #coverage_gap }

serialkompat couldn't verify a type, so it never treats it as safe. It fires when:

- a type in the current snapshot is opaque (for example, a hand-written serializer), on
  every run;
- a type that was opaque in the baseline is analysed now, so its old shape was never seen;
- a field was an unresolved `@Contextual` type in the baseline and resolves now;
- a type can't be paired with a baseline type, because its serial name is shared by
  several subtypes and the baseline doesn't record their bases.

```diff
-@Serializable(with = MoneyAsStringSerializer::class)
-class Money(val text: String)
+@Serializable
+data class Money(val amount: Long, val currency: String)
```

!!! success "Resolve it"
    Give the type an analysable `@Serializable` form, or accept the gap explicitly. A
    gap from an older baseline clears once the next baseline is recorded.

!!! failure "Both directions"
    The gate can't see what changed, so it reports ⚠️ WARN both ways. In the example the
    real change is a break, which is why a gap is never read as safe.

**Proof:** [`a baseline-opaque type that becomes analysable is a coverage gap, never a removal or safe`](https://github.com/chrisjenx/serialkompat/blob/main/serialkompat-extractor/src/test/kotlin/com/chrisjenx/serialkompat/extractor/RoundTripOracleTest.kt) · [`an open base extracted without its module registrations is not read as safe`](https://github.com/chrisjenx/serialkompat/blob/main/serialkompat-extractor/src/test/kotlin/com/chrisjenx/serialkompat/extractor/RoundTripOracleTest.kt) · [`unresolved @Contextual surfaces as an OPAQUE coverage-gap node (#131)`](https://github.com/chrisjenx/serialkompat/blob/main/serialkompat-extractor/src/test/kotlin/com/chrisjenx/serialkompat/extractor/ToleranceGapsTest.kt)

### `ENUM_VALUE_ADDED` { #enum_value_added }

A constant is added to an enum.

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
    contain element with name 'ARCHIVED'`.

Config effects: with `coerceInputValues = true` on the old reader, **and** a default on
every field that reads the enum, the value silently becomes that default (⚠️). A required
field, a `List` or `Map` element, or a top-level enum has no default, so it stays ❌.
serialkompat checks every reader in the baseline snapshot, not just the types in your
`include` / `exclude` scope: a required reader in an out-of-scope type keeps the verdict at
❌. CBOR never coerces, so it is always ❌ there.

**Proof:** [`an added enum value on a DEFAULTED field is a coercing-reader WARN, a strict-reader BREAK (#129)`](https://github.com/chrisjenx/serialkompat/blob/main/serialkompat-extractor/src/test/kotlin/com/chrisjenx/serialkompat/extractor/RoundTripOracleTest.kt) · [`an added enum value on a REQUIRED field breaks even a coercing reader (#129)`](https://github.com/chrisjenx/serialkompat/blob/main/serialkompat-extractor/src/test/kotlin/com/chrisjenx/serialkompat/extractor/RoundTripOracleTest.kt) · [`coerceInputValues rescues an added enum value, downgrading the forward break to a warning`](https://github.com/chrisjenx/serialkompat/blob/main/serialkompat-extractor/src/test/kotlin/com/chrisjenx/serialkompat/extractor/RoundTripOracleTest.kt) · [`adding an enum value`](https://github.com/chrisjenx/serialkompat/blob/main/serialkompat-extractor/src/test/kotlin/com/chrisjenx/serialkompat/extractor/RoundTripOracleTest.kt) · [`CBOR - an added enum value breaks old readers that meet it`](https://github.com/chrisjenx/serialkompat/blob/main/serialkompat-extractor/src/test/kotlin/com/chrisjenx/serialkompat/extractor/CborRoundTripOracleTest.kt)

### `ENUM_VALUE_REMOVED` { #enum_value_removed }

A constant is removed from an enum.

```diff
 @Serializable
 enum class Status {
     ACTIVE,
-    ARCHIVED,
 }
```

!!! success "Forward — old reader ← new data"
    The new writer only sends values the old enum still has. ✅ SAFE.

!!! failure "Backward — new reader ← old data"
    ```json
    "ARCHIVED"
    ```
    Old data may hold the removed value, which the new enum can't decode. ❌ BREAK.

**Proof:** [`ENUM_VALUE_REMOVED - new readers break on the removed value, old readers are fine`](https://github.com/chrisjenx/serialkompat/blob/main/serialkompat-extractor/src/test/kotlin/com/chrisjenx/serialkompat/extractor/RoundTripOracleTest.kt) · [`CBOR - a removed enum value breaks new readers of old data`](https://github.com/chrisjenx/serialkompat/blob/main/serialkompat-extractor/src/test/kotlin/com/chrisjenx/serialkompat/extractor/CborRoundTripOracleTest.kt)

### `SUBTYPE_ADDED` { #subtype_added }

A subtype is added to a sealed or polymorphic base.

```diff
 @Serializable
 sealed interface Shape {
     @Serializable @SerialName("circle")
     data class Circle(val r: Double) : Shape
+
+    @Serializable @SerialName("square")
+    data class Square(val side: Double) : Shape
 }
```

!!! success "Backward — new reader ← old data"
    Old data only holds subtypes the new base still has. ✅ SAFE.

!!! failure "Forward — old reader ← new data"
    ```json
    {"type":"square","side":2.0}
    ```
    The old reader doesn't know `square` and throws. ❌ BREAK.

Config effects: a base can register a polymorphic default deserializer in the
`SerializersModule` (the `Unknown` sentinel idiom). An old reader with one decodes the new
subtype as the sentinel: decode succeeds, but not as the real subtype (⚠️). That needs the
old reader to also set `ignoreUnknownKeys`, so the sentinel can skip the new subtype's
fields; a strict reader still throws (❌). This works for open and sealed bases.

**Proof:** [`a registered default deserializer downgrades an added-subtype forward break to WARN`](https://github.com/chrisjenx/serialkompat/blob/main/serialkompat-extractor/src/test/kotlin/com/chrisjenx/serialkompat/extractor/RoundTripOracleTest.kt) · [`a module default deserializer on a SEALED base downgrades an added-subtype forward break to WARN`](https://github.com/chrisjenx/serialkompat/blob/main/serialkompat-extractor/src/test/kotlin/com/chrisjenx/serialkompat/extractor/RoundTripOracleTest.kt) · [`adding a sealed subtype`](https://github.com/chrisjenx/serialkompat/blob/main/serialkompat-extractor/src/test/kotlin/com/chrisjenx/serialkompat/extractor/RoundTripOracleTest.kt) · [`CBOR - an added sealed subtype breaks old readers that meet it`](https://github.com/chrisjenx/serialkompat/blob/main/serialkompat-extractor/src/test/kotlin/com/chrisjenx/serialkompat/extractor/CborRoundTripOracleTest.kt) · [`CBOR - an added open subtype breaks a strict old reader even with a default deserializer`](https://github.com/chrisjenx/serialkompat/blob/main/serialkompat-extractor/src/test/kotlin/com/chrisjenx/serialkompat/extractor/CborRoundTripOracleTest.kt) · [`CBOR - an added open subtype is a silent substitution for a tolerant old reader with a default`](https://github.com/chrisjenx/serialkompat/blob/main/serialkompat-extractor/src/test/kotlin/com/chrisjenx/serialkompat/extractor/CborRoundTripOracleTest.kt)

### `SUBTYPE_REMOVED` { #subtype_removed }

A subtype is removed from a sealed or polymorphic base.

```diff
 @Serializable
 sealed interface Shape {
     @Serializable @SerialName("circle")
     data class Circle(val r: Double) : Shape
-
-    @Serializable @SerialName("square")
-    data class Square(val side: Double) : Shape
 }
```

!!! success "Forward — old reader ← new data"
    The new writer only sends subtypes the old base has. ✅ SAFE.

!!! failure "Backward — new reader ← old data"
    ```json
    {"type":"square","side":2.0}
    ```
    Old data may hold the removed subtype, which the new reader can't decode. ❌ BREAK.

**Proof:** [`SUBTYPE_REMOVED - new readers break on the removed subtype, old readers are fine`](https://github.com/chrisjenx/serialkompat/blob/main/serialkompat-extractor/src/test/kotlin/com/chrisjenx/serialkompat/extractor/RoundTripOracleTest.kt) · [`CBOR - a removed sealed subtype breaks new readers of old data`](https://github.com/chrisjenx/serialkompat/blob/main/serialkompat-extractor/src/test/kotlin/com/chrisjenx/serialkompat/extractor/CborRoundTripOracleTest.kt)

### `DISCRIMINATOR_CHANGED` { #discriminator_changed }

A polymorphic base's discriminator key changed: its `@JsonClassDiscriminator`, or the
`classDiscriminator` it inherits from the config (which also reports
[`CONFIG_DISCRIMINATOR`](#config_discriminator)). JSON only.

```diff
 @Serializable
-@JsonClassDiscriminator("type")
+@JsonClassDiscriminator("kind")
 sealed interface Shape
```

!!! success "Instead"
    Keep the discriminator key stable. To change it, bump major.

!!! failure "Both directions"
    ```json
    {"kind":"circle","r":1.0}
    ```
    Each side looks for its own key and doesn't find it. ❌ BREAK both ways.

**Proof:** [`DISCRIMINATOR_CHANGED - a changed @JsonClassDiscriminator key breaks both ways`](https://github.com/chrisjenx/serialkompat/blob/main/serialkompat-extractor/src/test/kotlin/com/chrisjenx/serialkompat/extractor/RoundTripOracleTest.kt) · [`changing a type's @JsonClassDiscriminator key breaks decoding both ways`](https://github.com/chrisjenx/serialkompat/blob/main/serialkompat-extractor/src/test/kotlin/com/chrisjenx/serialkompat/extractor/RoundTripOracleTest.kt)

### `DISCRIMINATOR_VALUE_CHANGED` { #discriminator_value_changed }

A sealed or polymorphic base was moved to a new serial name through
[`renames`](configuration.md). Moving a plain class or an enum reports nothing.

```diff
-@Serializable @SerialName("Payment")
+@Serializable @SerialName("Billing")
 sealed interface Payment
```

!!! success "Instead"
    Pin the old value with `@SerialName`, or accept the move as a deliberate break.

!!! failure "Both directions"
    Reported as ❌ BREAK both ways, conservatively: the rule doesn't inspect the subtypes.
    When every subtype keeps its own `@SerialName`, as in the cited test, both directions
    still decode. This sound over-prediction is tracked in [#240](https://github.com/chrisjenx/serialkompat/issues/240).

**Proof:** [`a sealed-base rename is DISCRIMINATOR_VALUE_CHANGED yet both round-trips decode`](https://github.com/chrisjenx/serialkompat/blob/main/serialkompat-extractor/src/test/kotlin/com/chrisjenx/serialkompat/extractor/RoundTripOracleTest.kt)

### `DISCRIMINATOR_COLLISION` { #discriminator_collision }

A subtype declares a property whose JSON key equals its base's class discriminator. Such a
model is already unserializable: kotlinx-serialization refuses to encode it. Like
`COVERAGE_GAP`, it is a defect of one model, reported on every run until you fix it.

```kotlin
@Serializable
@JsonClassDiscriminator("name")
sealed interface Msg {
    @Serializable @SerialName("hello")
    data class Hello(val name: String, val greeting: String) : Msg
}
```

!!! success "Fix it"
    Rename the colliding property, or set a different `@JsonClassDiscriminator`.

!!! failure "Both directions"
    `Json.encodeToString` throws for `Hello`, so nothing can be written or read. ❌ BREAK
    both ways.

It doesn't fire when no discriminator is written: under `classDiscriminatorMode = NONE`, or
`useArrayPolymorphism`.

**Proof:** [`discriminator colliding with a subtype property is flagged as unserializable (#132)`](https://github.com/chrisjenx/serialkompat/blob/main/serialkompat-extractor/src/test/kotlin/com/chrisjenx/serialkompat/extractor/ToleranceGapsTest.kt) · [`a discriminator collision is classified BREAK in both directions`](https://github.com/chrisjenx/serialkompat/blob/main/serialkompat-core/src/test/kotlin/com/chrisjenx/serialkompat/core/DiscriminatorCollisionTest.kt) · [`classDiscriminatorMode NONE suppresses the collision — no discriminator is emitted`](https://github.com/chrisjenx/serialkompat/blob/main/serialkompat-core/src/test/kotlin/com/chrisjenx/serialkompat/core/DiscriminatorCollisionTest.kt)

### `CONFIG_NAMING_STRATEGY` { #config_naming_strategy }

The `Json { namingStrategy }` changed, so every generated JSON key moves at once.

```diff
-val json = Json { }
+val json = Json { namingStrategy = JsonNamingStrategy.SnakeCase }
```

!!! success "Instead"
    Keep the naming strategy stable. To change it, bump major.

!!! failure "Both directions"
    ```json
    {"order_id":"x","line_total":1}
    ```
    Each side expects the other key spelling (`orderId` vs `order_id`). ❌ BREAK both ways.

**Proof:** [`CONFIG_NAMING_STRATEGY - a changed naming strategy breaks both ways`](https://github.com/chrisjenx/serialkompat/blob/main/serialkompat-extractor/src/test/kotlin/com/chrisjenx/serialkompat/extractor/RoundTripOracleTest.kt) · [`changing the naming strategy renames every key — a real break both ways`](https://github.com/chrisjenx/serialkompat/blob/main/serialkompat-extractor/src/test/kotlin/com/chrisjenx/serialkompat/extractor/RoundTripOracleTest.kt)

### `CONFIG_DISCRIMINATOR` { #config_discriminator }

The `Json { classDiscriminator }` or `classDiscriminatorMode` changed. Each polymorphic base
that inherits the key also reports [`DISCRIMINATOR_CHANGED`](#discriminator_changed).

```diff
-val json = Json { }
+val json = Json { classDiscriminator = "kind" }
```

!!! success "Instead"
    Keep the discriminator settings stable. To change them, bump major.

!!! failure "Both directions"
    ```json
    {"kind":"a","x":1}
    ```
    Each side looks for its own key. ❌ BREAK both ways.

For `classDiscriminatorMode`, forward really throws: an old reader can't decode data written
without the discriminator. Backward is reported as ❌ too, a conservative over-prediction:
the cited test observes only the forward throw. Tracked in [#240](https://github.com/chrisjenx/serialkompat/issues/240).

**Proof:** [`CONFIG_DISCRIMINATOR - a changed classDiscriminator breaks both ways`](https://github.com/chrisjenx/serialkompat/blob/main/serialkompat-extractor/src/test/kotlin/com/chrisjenx/serialkompat/extractor/RoundTripOracleTest.kt) · [`CONFIG_DISCRIMINATOR - a changed classDiscriminatorMode is a BREAK both ways`](https://github.com/chrisjenx/serialkompat/blob/main/serialkompat-extractor/src/test/kotlin/com/chrisjenx/serialkompat/extractor/RoundTripOracleTest.kt) · [`changing the class discriminator breaks polymorphic decoding both ways`](https://github.com/chrisjenx/serialkompat/blob/main/serialkompat-extractor/src/test/kotlin/com/chrisjenx/serialkompat/extractor/RoundTripOracleTest.kt)

### `CONFIG_READER_STRICTNESS` { #config_reader_strictness }

A reader-side acceptance flag changed: `ignoreUnknownKeys`, `useAlternativeNames`,
`isLenient`, `decodeEnumsCaseInsensitive`, `allowTrailingComma` or `allowComments`. Only
`ignoreUnknownKeys` applies to CBOR.

```diff
-val json = Json { ignoreUnknownKeys = true }
+val json = Json { }
```

!!! success "Turned on, or forward"
    A looser reader accepts everything the old one did, and kotlinx writes the same bytes
    either way. ✅ SAFE.

!!! failure "Backward — turned off"
    ```json
    {"id":"x","legacy":"y"}
    ```
    The new, stricter reader rejects input the old one accepted: here an unknown key, or
    for the other flags an alias key, an unquoted string, a differently cased constant, a
    trailing comma or a comment. Only such payloads fail, so it is ⚠️ WARN.

**Proof:** [`tightening ignoreUnknownKeys makes a tolerated unknown key throw — backward WARN`](https://github.com/chrisjenx/serialkompat/blob/main/serialkompat-extractor/src/test/kotlin/com/chrisjenx/serialkompat/extractor/RoundTripOracleTest.kt) · [`disabling useAlternativeNames makes a @JsonNames alias key throw — backward WARN`](https://github.com/chrisjenx/serialkompat/blob/main/serialkompat-extractor/src/test/kotlin/com/chrisjenx/serialkompat/extractor/RoundTripOracleTest.kt) · [`disabling isLenient makes a tolerated unquoted string throw — backward WARN`](https://github.com/chrisjenx/serialkompat/blob/main/serialkompat-extractor/src/test/kotlin/com/chrisjenx/serialkompat/extractor/RoundTripOracleTest.kt) · [`disabling decodeEnumsCaseInsensitive makes a differently-cased constant throw — backward WARN`](https://github.com/chrisjenx/serialkompat/blob/main/serialkompat-extractor/src/test/kotlin/com/chrisjenx/serialkompat/extractor/RoundTripOracleTest.kt) · [`disabling allowTrailingComma makes a trailing comma throw — backward WARN`](https://github.com/chrisjenx/serialkompat/blob/main/serialkompat-extractor/src/test/kotlin/com/chrisjenx/serialkompat/extractor/RoundTripOracleTest.kt) · [`disabling allowComments makes a commented payload throw — backward WARN`](https://github.com/chrisjenx/serialkompat/blob/main/serialkompat-extractor/src/test/kotlin/com/chrisjenx/serialkompat/extractor/RoundTripOracleTest.kt) · [`reader-only acceptance flags never change what the writer emits`](https://github.com/chrisjenx/serialkompat/blob/main/serialkompat-extractor/src/test/kotlin/com/chrisjenx/serialkompat/extractor/RoundTripOracleTest.kt) · [`CBOR - tightening ignoreUnknownKeys is a reader-strictness WARN backed by a real rejection`](https://github.com/chrisjenx/serialkompat/blob/main/serialkompat-extractor/src/test/kotlin/com/chrisjenx/serialkompat/extractor/CborRoundTripOracleTest.kt)

### `CONFIG_ENCODE_DEFAULTS` { #config_encode_defaults }

`encodeDefaults` changed. It applies to JSON and CBOR.

```diff
-val json = Json { encodeDefaults = true }
+val json = Json { }
```

!!! success "Turned on, or backward"
    The writer sends more, never less. ✅ SAFE.

!!! failure "Forward — turned off"
    The new writer stops sending fields that hold their default. The old reader fills in
    its own default, which may not be the value peers relied on. ⚠️ WARN.

A field that changed optionality at the same time is judged by
[`PROPERTY_OPTIONALITY`](#property_optionality) under the new writer's setting.

**Proof:** [`no longer encoding defaults (true to false) warns forward only`](https://github.com/chrisjenx/serialkompat/blob/main/serialkompat-core/src/test/kotlin/com/chrisjenx/serialkompat/core/ClassifierConfigTest.kt) · [`CBOR - disabling encodeDefaults is flagged where an old reader loses a required field`](https://github.com/chrisjenx/serialkompat/blob/main/serialkompat-extractor/src/test/kotlin/com/chrisjenx/serialkompat/extractor/CborRoundTripOracleTest.kt) · [`disabling encodeDefaults round-trips without a decode failure`](https://github.com/chrisjenx/serialkompat/blob/main/serialkompat-extractor/src/test/kotlin/com/chrisjenx/serialkompat/extractor/RoundTripOracleTest.kt)

### `CONFIG_EXPLICIT_NULLS` { #config_explicit_nulls }

`explicitNulls` changed, so a writer starts or stops writing `null` values. JSON only.

```diff
-val json = Json { }
+val json = Json { explicitNulls = false }
```

```kotlin
@Serializable
data class Account(val id: String?) // nullable, no default
```

!!! success "Fix it: give every nullable field a default"
    Declare `val id: String? = null`. A writer with `explicitNulls = false` then omits the
    null and the reader fills in the default, so nothing throws. The data may still differ
    (a default replaces the null), so the change stays ⚠️ WARN both ways.

!!! failure "Forward — turned off, a nullable field with no default"
    ```json
    {}
    ```
    The new writer omits `Account(null)`'s `"id":null`. The old reader, with
    `explicitNulls = true`, requires
    the key for a nullable field with no default: `MissingFieldException`. ❌ BREAK.

The ❌ applies in the direction whose writer has `explicitNulls = false` and whose reader has
`true`: forward when the setting is turned off, backward when it is turned on. It needs a
nullable field with no default among that direction's readers. Forward checks every type in
the baseline, in scope or not. Backward checks the current snapshot's in-scope types only
([#238](https://github.com/chrisjenx/serialkompat/issues/238)).

**Proof:** [`CONFIG_EXPLICIT_NULLS - omitting nulls breaks old readers of a nullable no-default field`](https://github.com/chrisjenx/serialkompat/blob/main/serialkompat-extractor/src/test/kotlin/com/chrisjenx/serialkompat/extractor/RoundTripOracleTest.kt) · [`CONFIG_EXPLICIT_NULLS - a reader that stops accepting omitted nulls breaks on old data`](https://github.com/chrisjenx/serialkompat/blob/main/serialkompat-extractor/src/test/kotlin/com/chrisjenx/serialkompat/extractor/RoundTripOracleTest.kt) · [`CONFIG_EXPLICIT_NULLS - stays a WARN both ways when every nullable field has a default`](https://github.com/chrisjenx/serialkompat/blob/main/serialkompat-extractor/src/test/kotlin/com/chrisjenx/serialkompat/extractor/RoundTripOracleTest.kt) · [`CONFIG_EXPLICIT_NULLS - toggling explicitNulls warns both ways`](https://github.com/chrisjenx/serialkompat/blob/main/serialkompat-extractor/src/test/kotlin/com/chrisjenx/serialkompat/extractor/RoundTripOracleTest.kt)

### `CONFIG_COERCE_INPUT` { #config_coerce_input }

`coerceInputValues` changed. JSON only.

```diff
-val json = Json { coerceInputValues = true }
+val json = Json { }
```

!!! success "Turned on, or forward"
    A coercing reader accepts everything a strict one does. ✅ SAFE.

!!! failure "Backward — turned off"
    ```json
    {"e":"NOPE"}
    ```
    The old reader turned an unknown enum value into the field's default; the new reader
    throws. Only such payloads fail, so it is ⚠️ WARN.

**Proof:** [`disabling coerceInputValues makes an out-of-domain enum value throw — backward WARN`](https://github.com/chrisjenx/serialkompat/blob/main/serialkompat-extractor/src/test/kotlin/com/chrisjenx/serialkompat/extractor/RoundTripOracleTest.kt)

### `CONFIG_ARRAY_POLYMORPHISM` { #config_array_polymorphism }

`useArrayPolymorphism` changed. Every polymorphic payload switches between a
`{"type":..}` object and a `["..",{..}]` array.

```diff
-val json = Json { }
+val json = Json { useArrayPolymorphism = true }
```

!!! success "Instead"
    Keep it stable. To change it, bump major.

!!! failure "Both directions"
    ```json
    ["a",{"x":1}]
    ```
    Each reader rejects the other shape. ❌ BREAK both ways.

**Proof:** [`toggling useArrayPolymorphism reshapes every polymorphic payload — a real break both ways`](https://github.com/chrisjenx/serialkompat/blob/main/serialkompat-extractor/src/test/kotlin/com/chrisjenx/serialkompat/extractor/RoundTripOracleTest.kt)

### `CONFIG_STRUCTURED_MAP_KEYS` { #config_structured_map_keys }

`allowStructuredMapKeys` changed. A reader without it rejects maps whose keys aren't
primitives.

```diff
-val json = Json { allowStructuredMapKeys = true }
+val json = Json { }
```

!!! success "The side whose reader has it"
    Turned on, the new reader accepts everything (✅ backward). Turned off, the new
    writer can't produce such maps for the old reader (✅ forward).

!!! failure "The side whose reader lacks it"
    ```json
    {"m":[{"a":1},2]}
    ```
    Turned off, the new reader rejects stored structured-key maps (❌ backward). Turned on,
    the old reader rejects the new ones (❌ forward). This is reported even if your model
    has no such map.

**Proof:** [`disabling allowStructuredMapKeys makes stored structured-key maps undecodable — backward BREAK`](https://github.com/chrisjenx/serialkompat/blob/main/serialkompat-extractor/src/test/kotlin/com/chrisjenx/serialkompat/extractor/RoundTripOracleTest.kt) · [`enabling allowStructuredMapKeys emits maps an old reader rejects — forward BREAK`](https://github.com/chrisjenx/serialkompat/blob/main/serialkompat-extractor/src/test/kotlin/com/chrisjenx/serialkompat/extractor/RoundTripOracleTest.kt)

### `CONFIG_SPECIAL_FLOATS` { #config_special_floats }

`allowSpecialFloatingPointValues` changed. A reader without it rejects `NaN` and
`Infinity`.

```diff
-val json = Json { allowSpecialFloatingPointValues = true }
+val json = Json { }
```

!!! success "The side whose reader has it"
    Turned on, the new reader accepts everything (✅ backward). Turned off, the new
    writer no longer emits such values (✅ forward).

!!! failure "The side whose reader lacks it"
    ```json
    {"f":NaN}
    ```
    Turned off, the new reader rejects stored `NaN` (⚠️ backward). Turned on, the old
    reader rejects new `Infinity` (⚠️ forward). Only payloads with those values fail, so
    it is a WARN.

**Proof:** [`disabling allowSpecialFloatingPointValues rejects stored NaN — backward WARN`](https://github.com/chrisjenx/serialkompat/blob/main/serialkompat-extractor/src/test/kotlin/com/chrisjenx/serialkompat/extractor/RoundTripOracleTest.kt) · [`enabling allowSpecialFloatingPointValues emits NaN an old reader rejects — forward WARN`](https://github.com/chrisjenx/serialkompat/blob/main/serialkompat-extractor/src/test/kotlin/com/chrisjenx/serialkompat/extractor/RoundTripOracleTest.kt)

### `CONFIG_FORMAT` { #config_format }

The baseline and the current snapshot use different wire formats (JSON ↔ CBOR). Different
rules judge each format, so no type is compared, and the change is reported once.

```diff
 serialkompat {
-    jsonInstance.set("com.example.wire.WireJson.instance")
+    format.set(WireFormat.CBOR)
+    cborInstance.set("com.example.wire.WireCbor.instance")
 }
```

!!! success "Expected"
    This usually means the change adopts CBOR (or JSON) in serialkompat while the baseline
    ref still checks the other format. It clears once the baseline moves past the change.

!!! failure "Both directions"
    Nothing was compared, so it reports ⚠️ WARN both ways. If the stored data itself changes
    format, old readers can't decode it; migrate it explicitly.

**Proof:** [`CBOR - a JSON baseline against a CBOR snapshot is one format WARN`](https://github.com/chrisjenx/serialkompat/blob/main/serialkompat-extractor/src/test/kotlin/com/chrisjenx/serialkompat/extractor/CborRoundTripOracleTest.kt)

### `CONFIG_CHANGED` { #config_changed }

A catch-all for a wire-relevant setting with no specific rule. Every current `Json` and
`Cbor` setting has its own rule, so it isn't reported today. CBOR's
`preferCborLabelsOverNames` is judged here as safe both ways, because a reader accepts a
field's name or its label either way.

```kotlin
// No current setting reaches CONFIG_CHANGED. A future wire-relevant Json or Cbor
// setting would, until serialkompat gives it its own rule.
```

!!! success "Known settings"
    Each one is judged by its own rule above.

!!! failure "Both directions"
    An unknown wire-relevant setting gets a conservative ⚠️ WARN both ways.

**Proof:** [`an unrecognized wire-relevant setting is a conservative WARN both ways`](https://github.com/chrisjenx/serialkompat/blob/main/serialkompat-core/src/test/kotlin/com/chrisjenx/serialkompat/core/ClassifierConfigTest.kt) · [`every wire-relevant Json setting maps to a specific rule — CONFIG_CHANGED is unreachable`](https://github.com/chrisjenx/serialkompat/blob/main/serialkompat-extractor/src/test/kotlin/com/chrisjenx/serialkompat/extractor/RoundTripOracleTest.kt) · [`CBOR - every Cbor setting maps to a specific rule, and every CBOR field is exercised`](https://github.com/chrisjenx/serialkompat/blob/main/serialkompat-extractor/src/test/kotlin/com/chrisjenx/serialkompat/extractor/CborRoundTripOracleTest.kt) · [`CBOR - toggling preferCborLabelsOverNames decodes both ways and is not flagged`](https://github.com/chrisjenx/serialkompat/blob/main/serialkompat-extractor/src/test/kotlin/com/chrisjenx/serialkompat/extractor/CborRoundTripOracleTest.kt)

### `CONFIG_CBOR_BYTE_STRING` { #config_cbor_byte_string }

CBOR `alwaysUseByteString` changed, so every plain `ByteArray` changes encoding: a byte
string on one side, an array of integers on the other.

```diff
-val cbor = Cbor { }
+val cbor = Cbor { alwaysUseByteString = true }
```

!!! success "Instead"
    Keep it stable. Mark single fields with `@ByteString` instead, which
    [`PROPERTY_BYTE_STRING`](#property_byte_string) judges.

!!! failure "Both directions"
    Neither side reads the other's encoding. ❌ BREAK both ways.

**Proof:** [`CBOR - toggling alwaysUseByteString breaks both ways`](https://github.com/chrisjenx/serialkompat/blob/main/serialkompat-extractor/src/test/kotlin/com/chrisjenx/serialkompat/extractor/CborRoundTripOracleTest.kt)

### `CONFIG_CBOR_DEFINITE_LENGTH` { #config_cbor_definite_length }

CBOR `useDefiniteLengthEncoding` changed. Readers accept either length form.

```diff
-val cbor = Cbor { }
+val cbor = Cbor { useDefiniteLengthEncoding = true }
```

!!! success "Backward, or turned off"
    Every reader decodes both length forms. Turning it off adds no new loss. ✅ SAFE.

!!! failure "Forward — turned on"
    A definite-length writer encodes an object whose fields all hold their defaults as an
    empty map, which a reader takes as `null` in a nullable slot: `Outer(Inner())` reads
    back as `Outer(null)`. ⚠️ WARN.

The `null` quirk belongs to definite-length bytes, so it exists whenever the setting is on,
not only when it changes.

**Proof:** [`CBOR - enabling definite-length encoding is flagged forward, where an all-default object reads as null`](https://github.com/chrisjenx/serialkompat/blob/main/serialkompat-extractor/src/test/kotlin/com/chrisjenx/serialkompat/extractor/CborRoundTripOracleTest.kt) · [`CBOR - disabling definite-length encoding adds no new loss and is not flagged`](https://github.com/chrisjenx/serialkompat/blob/main/serialkompat-extractor/src/test/kotlin/com/chrisjenx/serialkompat/extractor/CborRoundTripOracleTest.kt)

### `CONFIG_CBOR_TAGS` { #config_cbor_tags }

A CBOR `encode*Tags` or `verify*Tags` flag changed (key, value or object tags). A verifying
reader rejects data written without the tags; a reader that doesn't verify skips them.

```diff
-val cbor = Cbor { }
+val cbor = Cbor { verifyValueTags = true }
```

!!! success "Encoding before verifying"
    A writer that starts encoding tags, or a verifying reader of data that was always
    tagged, is ✅ SAFE.

!!! failure "A verifying reader meets untagged data"
    The new reader verifies tags the old writer didn't encode (❌ backward), or the old
    reader verifies tags the new writer stopped encoding (❌ forward). This is judged
    from the config alone, whether or not your model declares tags.

**Proof:** [`CBOR - a reader that starts verifying tags breaks on untagged data`](https://github.com/chrisjenx/serialkompat/blob/main/serialkompat-extractor/src/test/kotlin/com/chrisjenx/serialkompat/extractor/CborRoundTripOracleTest.kt) · [`CBOR - a verifying reader of data that was always tagged is fine`](https://github.com/chrisjenx/serialkompat/blob/main/serialkompat-extractor/src/test/kotlin/com/chrisjenx/serialkompat/extractor/CborRoundTripOracleTest.kt) · [`CBOR - encoding tags for readers that skip them is not flagged`](https://github.com/chrisjenx/serialkompat/blob/main/serialkompat-extractor/src/test/kotlin/com/chrisjenx/serialkompat/extractor/CborRoundTripOracleTest.kt) · [`CBOR - a writer that stops encoding tags breaks old verifying readers`](https://github.com/chrisjenx/serialkompat/blob/main/serialkompat-extractor/src/test/kotlin/com/chrisjenx/serialkompat/extractor/CborRoundTripOracleTest.kt)

### `PROPERTY_CBOR_LABEL` { #property_cbor_label }

A CBOR field's `@CborLabel` integer key was added, removed or changed. A label reaches the
wire only when the writer sets `preferCborLabelsOverNames`; otherwise the writer writes the
field name, which every reader accepts, and the change reports nothing. A reader accepts
the field's name or its own label, so a label it doesn't know is an unknown key.

```diff
 @Serializable
 data class CacheEntry(
-    @CborLabel(1) val id: String,
+    @CborLabel(2) val id: String,
 )
```

!!! success "Adding a label, backward"
    New readers still accept the field name that old data used. ✅ SAFE. Likewise,
    removing a label is ✅ forward.

!!! failure "Both directions, with `preferCborLabelsOverNames`"
    The writer keys `id` by its label. The other side expects another label, so a strict
    reader throws on the unknown key and then on the missing field (❌). With
    `ignoreUnknownKeys` and an optional field, the value is silently replaced by the
    default instead (⚠️).

**Proof:** [`CBOR - a changed label breaks both ways once labels are written`](https://github.com/chrisjenx/serialkompat/blob/main/serialkompat-extractor/src/test/kotlin/com/chrisjenx/serialkompat/extractor/CborRoundTripOracleTest.kt) · [`CBOR - a changed label is inert while names are written`](https://github.com/chrisjenx/serialkompat/blob/main/serialkompat-extractor/src/test/kotlin/com/chrisjenx/serialkompat/extractor/CborRoundTripOracleTest.kt) · [`CBOR - adding a label breaks only old readers`](https://github.com/chrisjenx/serialkompat/blob/main/serialkompat-extractor/src/test/kotlin/com/chrisjenx/serialkompat/extractor/CborRoundTripOracleTest.kt) · [`CBOR - removing a label breaks only new readers`](https://github.com/chrisjenx/serialkompat/blob/main/serialkompat-extractor/src/test/kotlin/com/chrisjenx/serialkompat/extractor/CborRoundTripOracleTest.kt) · [`CBOR - a changed label on an optional field silently defaults for a tolerant reader`](https://github.com/chrisjenx/serialkompat/blob/main/serialkompat-extractor/src/test/kotlin/com/chrisjenx/serialkompat/extractor/CborRoundTripOracleTest.kt)

### `PROPERTY_BYTE_STRING` { #property_byte_string }

A CBOR `ByteArray` field gained or lost `@ByteString`, so it switches between a byte
string and an array of integers.

```diff
 @Serializable
 data class Blob(
-    val data: ByteArray,
+    @ByteString val data: ByteArray,
 )
```

!!! success "Under `alwaysUseByteString`"
    Every `ByteArray` is already a byte string, so the annotation changes nothing and the
    rule reports nothing.

!!! failure "Both directions"
    Neither side reads the other's encoding. ❌ BREAK both ways.

**Proof:** [`CBOR - toggling ByteString on a field breaks both ways`](https://github.com/chrisjenx/serialkompat/blob/main/serialkompat-extractor/src/test/kotlin/com/chrisjenx/serialkompat/extractor/CborRoundTripOracleTest.kt) · [`CBOR - toggling ByteString under alwaysUseByteString changes nothing`](https://github.com/chrisjenx/serialkompat/blob/main/serialkompat-extractor/src/test/kotlin/com/chrisjenx/serialkompat/extractor/CborRoundTripOracleTest.kt)

### `CBOR_TAGS_CHANGED` { #cbor_tags_changed }

A CBOR `@KeyTags`, `@ValueTags` or `@ObjectTags` annotation changed. Tags reach the wire
only when the writer encodes that kind, and matter only when the reader verifies them and
declares tags of its own there.

```diff
 @Serializable
 data class Tagged(
-    @ValueTags(7u) val v: Int,
+    @ValueTags(8u) val v: Int,
 )
```

!!! success "By default"
    Without `encode*Tags` on the writer and `verify*Tags` on the reader, tags are inert.
    ✅ SAFE. A reader that declares no tags for the field skips any it meets.

!!! failure "Where tags are encoded and verified"
    The reader checks its declared tags against the ones written and rejects a mismatch.
    ❌ BREAK in each direction where that happens.

**Proof:** [`CBOR - changed value tags break where they are encoded and verified`](https://github.com/chrisjenx/serialkompat/blob/main/serialkompat-extractor/src/test/kotlin/com/chrisjenx/serialkompat/extractor/CborRoundTripOracleTest.kt) · [`CBOR - changed key tags break where they are encoded and verified`](https://github.com/chrisjenx/serialkompat/blob/main/serialkompat-extractor/src/test/kotlin/com/chrisjenx/serialkompat/extractor/CborRoundTripOracleTest.kt) · [`CBOR - changed object tags break where they are encoded and verified`](https://github.com/chrisjenx/serialkompat/blob/main/serialkompat-extractor/src/test/kotlin/com/chrisjenx/serialkompat/extractor/CborRoundTripOracleTest.kt) · [`CBOR - changed value tags are inert by default`](https://github.com/chrisjenx/serialkompat/blob/main/serialkompat-extractor/src/test/kotlin/com/chrisjenx/serialkompat/extractor/CborRoundTripOracleTest.kt) · [`CBOR - dropping value tags breaks only the reader that still declares them`](https://github.com/chrisjenx/serialkompat/blob/main/serialkompat-extractor/src/test/kotlin/com/chrisjenx/serialkompat/extractor/CborRoundTripOracleTest.kt)

### `CBOR_ARRAY_LAYOUT` { #cbor_array_layout }

A `@CborArray` class is encoded as a positional array, not a map. Names don't reach
the wire; positions do. This rule fires when `@CborArray` is added or removed, or its
elements are added, removed, renamed or reordered.

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
    optional, so it takes its default. ✅ SAFE. Likewise, dropping a trailing optional
    element is ✅ forward.

!!! failure "Forward — old reader ← new data"
    The old reader meets a third element it has no slot for and throws, **even with
    `ignoreUnknownKeys`**. That is unlike a keyed class, where an added optional field
    is safe for a tolerant reader. ❌ BREAK.

Any other layout change is ❌ both ways. Reordering elements breaks even when the types line
up: the values silently swap. Adding or removing `@CborArray` swaps a map for an array, so
each side rejects the other.

**Proof:** [`CBOR - appending an optional array element crashes old readers, even tolerant ones`](https://github.com/chrisjenx/serialkompat/blob/main/serialkompat-extractor/src/test/kotlin/com/chrisjenx/serialkompat/extractor/CborRoundTripOracleTest.kt) · [`CBOR - appending a required array element breaks both ways`](https://github.com/chrisjenx/serialkompat/blob/main/serialkompat-extractor/src/test/kotlin/com/chrisjenx/serialkompat/extractor/CborRoundTripOracleTest.kt) · [`CBOR - dropping a trailing optional array element breaks only new readers`](https://github.com/chrisjenx/serialkompat/blob/main/serialkompat-extractor/src/test/kotlin/com/chrisjenx/serialkompat/extractor/CborRoundTripOracleTest.kt) · [`CBOR - reordering array elements silently swaps values and is flagged`](https://github.com/chrisjenx/serialkompat/blob/main/serialkompat-extractor/src/test/kotlin/com/chrisjenx/serialkompat/extractor/CborRoundTripOracleTest.kt) · [`CBOR - toggling CborArray breaks both ways`](https://github.com/chrisjenx/serialkompat/blob/main/serialkompat-extractor/src/test/kotlin/com/chrisjenx/serialkompat/extractor/CborRoundTripOracleTest.kt)

### `CBOR_ARRAY_OMITTED_DEFAULT` { #cbor_array_omitted_default }

A `@CborArray` element, not the last, is left out when it holds its default. Every later
position shifts, so the class misreads its own data. Like `DISCRIMINATOR_COLLISION`, it is
a defect of one model, reported on every run.

```kotlin
@Serializable
@CborArray
data class Gap(
    val x: Int,
    val m: Int = 0, // omitted when 0, shifting y
    val y: Int,
)
```

!!! success "Fix it"
    Annotate the element `@EncodeDefault(ALWAYS)`, set `encodeDefaults = true`, or move
    it to the end.

!!! failure "Both directions"
    `Gap(1, 0, 2)` doesn't round-trip, even through the same class. ❌ BREAK both ways,
    or ⚠️ WARN if the element's `@EncodeDefault` mode couldn't be read.

**Proof:** [`CBOR - an array element omitted at its default misreads its own data and is flagged`](https://github.com/chrisjenx/serialkompat/blob/main/serialkompat-extractor/src/test/kotlin/com/chrisjenx/serialkompat/extractor/CborRoundTripOracleTest.kt) · [`CBOR - encoding defaults closes the array gap`](https://github.com/chrisjenx/serialkompat/blob/main/serialkompat-extractor/src/test/kotlin/com/chrisjenx/serialkompat/extractor/CborRoundTripOracleTest.kt) · [`a CborArray element whose @EncodeDefault mode was not recorded is a WARN both ways`](https://github.com/chrisjenx/serialkompat/blob/main/serialkompat-core/src/test/kotlin/com/chrisjenx/serialkompat/core/CborRulesTest.kt)

## Config awareness

The classifier reads your actual `Json { }` config (set with `jsonInstance`, see
[Configuration](configuration.md)), or your `Cbor { }` config (`cborInstance`, see
[CBOR](cbor.md)). It doesn't assume defaults, so the same change can be `SAFE` under one
config and `BREAK` under another. `ignoreUnknownKeys` and `encodeDefaults` mean the same
under CBOR; the other settings are JSON-only.

| Setting | Default | Moves the verdict of | Toggling it is |
|---|---|---|---|
| `ignoreUnknownKeys` | `false` | [`PROPERTY_ADDED`](#property_added) forward ❌ → ✅; [`PROPERTY_REMOVED`](#property_removed) backward ❌ → ⚠️; [`SUBTYPE_ADDED`](#subtype_added) forward ❌ → ⚠️ (with a default deserializer); [`PROPERTY_CBOR_LABEL`](#property_cbor_label) ❌ → ⚠️ (optional field) | [`CONFIG_READER_STRICTNESS`](#config_reader_strictness) |
| `encodeDefaults` | `false` | [`PROPERTY_OPTIONALITY`](#property_optionality) forward ❌ → ✅ (became optional); [`CBOR_ARRAY_OMITTED_DEFAULT`](#cbor_array_omitted_default) cleared, unless `@EncodeDefault(NEVER)` (stays ❌) or the mode is unrecorded (stays ⚠️) | [`CONFIG_ENCODE_DEFAULTS`](#config_encode_defaults) |
| `explicitNulls` | `true` | When `false`: [`PROPERTY_ADDED`](#property_added) backward ❌ → ✅ and [`PROPERTY_REMOVED`](#property_removed) forward ❌ → ⚠️ (nullable, no default); [`PROPERTY_NULLABILITY`](#property_nullability) forward ❌ → ⚠️ (old field has a default) | [`CONFIG_EXPLICIT_NULLS`](#config_explicit_nulls) |
| `coerceInputValues` | `false` | [`ENUM_VALUE_ADDED`](#enum_value_added) forward ❌ → ⚠️ (every reading field has a default) | [`CONFIG_COERCE_INPUT`](#config_coerce_input) |
| `namingStrategy` | none | — | [`CONFIG_NAMING_STRATEGY`](#config_naming_strategy) |
| `classDiscriminator`, `classDiscriminatorMode` | `"type"`, `POLYMORPHIC` | [`DISCRIMINATOR_COLLISION`](#discriminator_collision) off under `NONE` | [`CONFIG_DISCRIMINATOR`](#config_discriminator) |
| `useArrayPolymorphism` | `false` | [`DISCRIMINATOR_COLLISION`](#discriminator_collision) off when `true` | [`CONFIG_ARRAY_POLYMORPHISM`](#config_array_polymorphism) |
| `allowStructuredMapKeys` | `false` | — | [`CONFIG_STRUCTURED_MAP_KEYS`](#config_structured_map_keys) |
| `allowSpecialFloatingPointValues` | `false` | — | [`CONFIG_SPECIAL_FLOATS`](#config_special_floats) |
| `useAlternativeNames` | `true` | — | [`CONFIG_READER_STRICTNESS`](#config_reader_strictness) |
| `isLenient`, `decodeEnumsCaseInsensitive`, `allowTrailingComma`, `allowComments` | `false` | — | [`CONFIG_READER_STRICTNESS`](#config_reader_strictness) |
| CBOR `alwaysUseByteString` | `false` | [`PROPERTY_BYTE_STRING`](#property_byte_string) ❌ → nothing | [`CONFIG_CBOR_BYTE_STRING`](#config_cbor_byte_string) |
| CBOR `preferCborLabelsOverNames` | `false` | [`PROPERTY_CBOR_LABEL`](#property_cbor_label) nothing → ✅ / ⚠️ / ❌ | nothing (safe both ways) |
| CBOR `useDefiniteLengthEncoding` | `false` | — | [`CONFIG_CBOR_DEFINITE_LENGTH`](#config_cbor_definite_length) |
| CBOR `encode*Tags`, `verify*Tags` | `false` | [`CBOR_TAGS_CHANGED`](#cbor_tags_changed) nothing → ❌ | [`CONFIG_CBOR_TAGS`](#config_cbor_tags) |
