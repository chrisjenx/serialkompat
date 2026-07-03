# Rules

What counts as breaking, by direction and reader config — the classifier's full
rule set, applied to every change the [Differ](deep-dive.md) finds between two
snapshots.

## Severity

| Glyph | Severity | Meaning |
|---|---|---|
| ✅ | `SAFE` | No impact — the change round-trips cleanly. |
| ⚠️ | `WARN` | Config-dependent, or a silent semantic break — decode succeeds but data is wrong, defaulted, or dropped. No exception is thrown. |
| ❌ | `BREAK` | Decode fails — the reader throws. |

## Direction

- **`BACKWARD`** — new code reads old data (new reader ← old writer). The
  question: can code compiled against the *new* schema decode a payload
  written by the *old* one?
- **`FORWARD`** — old code reads new data (old reader ← new writer). The
  question: can code still running the *old* schema decode a payload written
  by the *new* one?
- **`FULL`** checks both; it's the default and the safest choice unless you
  control both ends of a rollout. See [Configuration → Choosing a
  direction](configuration.md#choosing-a-direction).

## The matrix

Verdicts below assume kotlinx-serialization defaults (`ignoreUnknownKeys =
false`, `encodeDefaults = false`, `explicitNulls = true`, `coerceInputValues =
false`) unless a cell says otherwise. "Config-aware" means the verdict shifts
under a non-default `Json { }` — see [Config awareness](#config-awareness)
below for exactly how.

| Rule | Detects | Backward | Forward | Config-aware |
|---|---|---|---|---|
| `CONTRACT_REMOVED` | Whole type deleted | ❌ BREAK | ❌ BREAK | — |
| `PROPERTY_ADDED` | Field added | ✅ SAFE if optional, ❌ BREAK if required | ❌ BREAK unless reader has `ignoreUnknownKeys` | yes |
| `PROPERTY_REMOVED` | Field deleted | ⚠️ WARN if `ignoreUnknownKeys` (silent drop), else ❌ BREAK | ✅ SAFE if it was optional, ❌ BREAK if required | yes |
| `PROPERTY_OPTIONALITY` | Optional ↔ required | ❌ BREAK if became required, ✅ SAFE if became optional | ✅ SAFE if became required; ❌ BREAK unless `encodeDefaults` if became optional | yes |
| `PROPERTY_NULLABILITY` | Nullable ↔ non-null | ✅ SAFE if became nullable, ❌ BREAK if became non-null | ❌ BREAK if nullable & `explicitNulls = true`; ⚠️ WARN if `false` | yes |
| `PROPERTY_TYPE_CHANGED` | Field type changed | ✅ SAFE if numeric widening, else ❌ BREAK | ❌ BREAK | yes |
| `PROPERTY_JSON_NAMES` | `@JsonNames` alias dropped | ⚠️ WARN | ✅ SAFE | no |
| `ENUM_VALUE_ADDED` | Enum value added | ✅ SAFE | ⚠️ WARN unless `coerceInputValues` | yes |
| `ENUM_VALUE_REMOVED` | Enum value removed | ❌ BREAK | ✅ SAFE | no |
| `SUBTYPE_ADDED` | Polymorphic variant added | ✅ SAFE | ❌ BREAK | no |
| `SUBTYPE_REMOVED` | Polymorphic variant removed | ❌ BREAK | ✅ SAFE | no |
| `DISCRIMINATOR_CHANGED` | Discriminator key changed | ❌ BREAK | ❌ BREAK | no |
| `DISCRIMINATOR_VALUE_CHANGED` | Polymorphic type moved (new FQN) | ❌ BREAK | ❌ BREAK | no |
| `DISCRIMINATOR_COLLISION` | Subtype property shadows the class discriminator (unserializable model) | ❌ BREAK | ❌ BREAK | no¹ |
| `CONFIG_NAMING_STRATEGY` | `namingStrategy` changed | ❌ BREAK | ❌ BREAK | — |
| `CONFIG_DISCRIMINATOR` | `classDiscriminator` changed | ❌ BREAK | ❌ BREAK | — |
| `CONFIG_READER_STRICTNESS` | `ignoreUnknownKeys` / `useAlternativeNames` changed | ⚠️ WARN if tightened, ✅ SAFE if loosened | ✅ SAFE | — |
| `CONFIG_ENCODE_DEFAULTS` | `encodeDefaults` toggled | ✅ SAFE | ⚠️ WARN if disabled, ✅ SAFE if enabled | — |
| `CONFIG_EXPLICIT_NULLS` | `explicitNulls` toggled | ⚠️ WARN | ⚠️ WARN | — |
| `CONFIG_COERCE_INPUT` | `coerceInputValues` toggled | ⚠️ WARN if disabled, ✅ SAFE if enabled | ✅ SAFE | — |
| `CONFIG_CHANGED` | Any other wire-relevant `Json` setting changed (catch-all) | ⚠️ WARN | ⚠️ WARN | — |
| `COVERAGE_GAP` | Opaque/unanalyzable type | ⚠️ WARN | ⚠️ WARN | — |

¹ `DISCRIMINATOR_COLLISION` is not a delta between two versions — it flags a single
model that is *already* unserializable: a sealed/polymorphic subtype declares a
property whose JSON key equals the base's class discriminator, which
kotlinx-serialization refuses to encode (`JsonEncodingException`). It is surfaced on
every run until fixed (like `COVERAGE_GAP`), and only when a discriminator is
actually emitted — `classDiscriminatorMode = NONE` suppresses it (nothing to
collide with).

## Config awareness

The classifier reads your actual `Json { }` config (via `jsonInstance`, see
[Configuration](configuration.md)) rather than assuming defaults. The same
change can be `SAFE` under one config and `BREAK` under another:

| Setting | Default | Flips |
|---|---|---|
| `ignoreUnknownKeys` | `false` | `PROPERTY_ADDED` forward: `BREAK` → `SAFE` once the reader tolerates unknown keys. `PROPERTY_REMOVED` backward: `BREAK` → `WARN` (data silently dropped instead of an exception). Tightening it from `true` → `false` is itself a `CONFIG_READER_STRICTNESS` `WARN`. |
| `encodeDefaults` | `false` | `PROPERTY_OPTIONALITY` forward (became optional): `BREAK` → `SAFE`, because the old reader now receives the field instead of missing it. `CONFIG_ENCODE_DEFAULTS` itself is `WARN` forward when disabled. |
| `explicitNulls` | `true` | `PROPERTY_NULLABILITY` forward (became nullable): `BREAK` when `true` (old reader gets an explicit `null` it must handle), `WARN` when `false` (field just omitted). Toggling the setting itself is `CONFIG_EXPLICIT_NULLS`, `WARN` in both directions. |
| `coerceInputValues` | `false` | `ENUM_VALUE_ADDED` forward: `WARN` → `SAFE`, since an unrecognized enum constant coerces to the declared default instead of failing. `CONFIG_COERCE_INPUT` itself is `WARN` backward when disabled. |
| `namingStrategy` | none | Any change to the strategy is a blanket `CONFIG_NAMING_STRATEGY` `BREAK` in both directions — every generated JSON key on the wire moves at once. |
| `classDiscriminator` | `"type"` | Any change is a blanket `CONFIG_DISCRIMINATOR` `BREAK` in both directions — every polymorphic payload's discriminator key moves at once. |

## The oracle guarantee

Every rule above is backed by a round-trip oracle test: serialize a payload
with the old model, decode it with the new one (and vice versa) using real
kotlinx-serialization under the declared `Json` config, and assert the
classifier's predicted severity matches what actually happened. Rules aren't
derived from reading the kotlinx-serialization source or spec — they're
verified against its real runtime behavior, config by config.

`COVERAGE_GAP` is the sharp edge of that guarantee: when the extractor can't
fully analyse a type, it never guesses `SAFE`. It emits a `WARN` and asks you
to look. Unanalysable is never treated as compatible.

## Coming later

This matrix is hand-written today. A follow-up PR regenerates it directly from
the shipped rule set, so the table on this page and the classifier's actual
behavior can never drift apart.
