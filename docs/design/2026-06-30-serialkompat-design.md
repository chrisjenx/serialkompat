# serialkompat — design

**Date:** 2026-06-30
**Status:** Design approved; ready for implementation planning
**Repo:** `github.com/chrisjenx/serialkompat` (public, personal)
**Coordinates:** plugin id `com.chrisjenx.serialkompat`, Maven group `com.chrisjenx`

---

## 1. Problem

`kotlinx-serialization-json` is pleasant to use but ships **no backward/forward
compatibility safety**. Whether a change to a `@Serializable` model breaks old
clients or old persisted data is invisible until it fails in production or is
caught by hand-written round-trip tests. There is no `buf breaking` equivalent
for kotlinx-serialization.

`serialkompat` is that gate: it extracts the JSON wire schema from `@Serializable`
models, diffs the current schema against a baseline, classifies each change
against kotlinx-serialization's real wire-compatibility semantics, and fails CI
on unacknowledged breaking changes — locally and on CI.

### Prior art (verified 2026-06-30 — the space is open)

No existing tool detects breaking JSON wire-schema changes for kotlinx-serialization
`@Serializable` models. Neighbors we borrow from:

- **JetBrains `binary-compatibility-validator` (BCV)** — the architectural model
  (dump a deterministic golden file, diff it on CI). But it validates JVM **ABI**,
  not JSON wire shape.
- **`Kotlin/kotlinx-schema`** (v0.5.0, *experimental*) — extracts a normalized IR
  from `@Serializable`, resolving `SerializersModule` polymorphism. A reusable
  **building block** for the extractor (behind an anti-corruption layer).
- **`ProtoBufSchemaGenerator`**, **`Stream29/JsonSchemaGenerator`** — reference
  `SerialDescriptor` walks (BFS + visited-set). Borrow the traversal, not the tool.
- **buf breaking** — nested severity categories, `--against` baselines, rule naming,
  `_UNLESS_RESERVED` escape hatch.
- **Confluent Schema Registry** — `BACKWARD`/`FORWARD`/`FULL` (+ `_TRANSITIVE`)
  vocabulary; reader/writer "who upgrades first" framing.
- **oasdiff / graphql-inspector** — 3-tier severity (breaking / dangerous / safe).
- **kotlinx-serialization's own runtime semantics** — the ground-truth ruleset.

---

## 2. Goals / non-goals

### Goals
- Detect JSON wire backward/forward-incompat changes to `@Serializable` models.
- Run as a **gate** (fail CI, run locally) with **no step you can forget** and
  **no baseline artifact that can silently go stale**.
- Correct classification grounded in kotlinx-serialization's *actual* behavior,
  verified against the real library — not asserted from belief.
- Kotlin Multiplatform: models live in shared code; extraction runs on the JVM
  target (descriptors are identical across targets).
- Explicit, reviewable acceptance of intentional breaks.
- Track type moves/renames so they are not mis-reported as delete+add.

### Non-goals (v0)
- ProtoBuf / CBOR binary-format rules (field ordering, `@ProtoNumber`). Deferred
  until/unless binary formats are adopted.
- Detecting compatibility for non-kotlinx serializers.
- Runtime enforcement / schema-registry service. This is a static CI gate.

### Threat model (drives the ruleset)
- **Format:** JSON only.
- **Direction:** `FULL` (both) — live services/clients *and* long-horizon persisted
  data.
- **Consumers:** heterogeneous (may be non-Kotlin), so the tool reasons about the
  JSON itself and cannot assume a single shared reader config.

---

## 3. Architecture

Everything hangs off one swappable artifact — the `Snapshot`.

```
                    ┌─────────────── serialkompat-core (pure, no I/O) ───────────────┐
@Serializable  ──▶  Extractor ──▶  Snapshot  ──▶  Differ ──▶  Change[]  ──▶  Classifier  ──▶  Report
  types            (runtime         (canonical    (structural   (raw         (rules +          (findings
                    descriptor       model)        deltas)       diffs)       severity)          + exit code)
                    walk, JVM)
                         ▲                              ▲
                    swappable                      reads two Snapshots;
              (compiler plugin later)          never knows their origin
```

The diff/classify engine is fully decoupled from extraction and from where
baselines come from. This is what lets git-ref mode, local mode, and (v1)
published-history mode share one engine, and lets a compiler-plugin extractor drop
in later without touching the rules.

### Modules

| Module | Responsibility | Depends on |
|---|---|---|
| `serialkompat-core` | `Snapshot` model + canonical serialize/parse, `Differ`, `Classifier`, rule set, `Report`. **Pure Kotlin, no kotlinx-serialization runtime, no I/O.** | — |
| `serialkompat-extractor` | Walk `SerialDescriptor` → build `Snapshot`, behind an `Extractor` interface (anti-corruption layer), incl. `SerializersModule` polymorphism. Vendors its own walk (see §12); does **not** depend on `kotlinx-schema`. Runs on JVM. | kotlinx-serialization |
| `serialkompat-gradle` | `serialkompatCheck` / `serialkompatCheckAgainst` / `serialkompatExtract` tasks; config extension. | core, extractor |
| `serialkompat-cli` | Standalone `serialkompat diff <baseline> <current>` for non-Gradle / cross-repo use. | core |
| `serialkompat-cli` | Thin CLI for cross-repo / non-Gradle use. v1. | core, extractor |

### The `Snapshot` format

The canonical model of the wire contract. Serialized to a **deterministic,
sorted, human-readable text form** (BCV's lesson) so it is diffable and
reviewable; a machine-readable JSON form may be emitted alongside for tooling.

**Elements are sorted by serial name, not declaration order** — JSON does not care
about field order, so reordering produces zero diff (and a rename correctly
surfaces as remove+add). Sorted emission with token-escaped free-text fields makes the
text byte-stable across runs.

Canonical form (as implemented in `SnapshotFormat`, issue #5). Separators are
single spaces and list literals carry no inner spaces, so an element line
tokenizes unambiguously on whitespace (a type ref never contains a space).
Blocks are sorted by serial name; within a contract, elements sort by key, enum
values sort, and subtypes sort by discriminator value — so reordering fields
produces a zero diff. An element whose type is another contract simply records
that contract's serial name as its type ref (no distinguishing `->` marker):

```
@contract com.mercury.orders.OrderEvent kind=CLASS
  amountCents: Long
  id: String
  note: String optional
  status: com.mercury.orders.OrderStatus
  tags: List<String> optional jsonNames=[labels]

@contract com.mercury.orders.OrderStatus kind=ENUM
  values=[CANCELLED,CREATED,PAID]

@contract com.mercury.orders.Payment kind=SEALED discriminator=type
  subtypes:
    ach -> com.mercury.orders.AchPayment
    card -> com.mercury.orders.CardPayment

@config
  classDiscriminator=type
  coerceInputValues=false
  encodeDefaults=false
  explicitNulls=true
  ignoreUnknownKeys=false
  namingStrategy=none
```

Per element it records the compat-bearing facts: **JSON key** (post-`@SerialName`
and post-`namingStrategy`), **type ref**, **`nullable`**, **`optional`** (straight
from `isElementOptional` — no re-derivation of the compiler's rules),
**`@JsonNames` aliases**, **`@EncodeDefault` mode**; for enums/sealed the **value
set** and **discriminator + subtype map**. The relevant **`Json` config** is part
of the snapshot (see §5) so config changes are themselves diffed.

---

## 4. Extraction (Approach A — runtime descriptor reflection)

A Gradle task runs a small program on the JVM target's runtime classpath. It
discovers in-scope `@Serializable` types, calls `Type.serializer().descriptor`,
and walks the descriptor tree (BFS + visited-set for cyclic graphs).

The `SerialDescriptor` already contains exactly what wire compatibility depends
on: `elementNames` (real JSON keys), `isElementOptional(i)` (authoritative
optionality — already accounts for `@Required`/`@Transient`/defaults),
`isNullable`, `SerialKind`, enum entries, sealed subtypes. `@JsonNames` /
`@EncodeDefault` are read via element annotations.

**Why runtime, not compile-time:**
- Highest fidelity; the *only* approach that sees `SerializersModule`-resolved
  polymorphism and honors custom serializers' actual descriptors.
- No compiler-plugin fragility (compiler plugins break on nearly every Kotlin
  release).
- The baseline architecture (§5) never needs two classpaths in one JVM, which is
  the usual reason to reach for a compiler plugin.

**Rejected alternatives:**
- **KSP-only (static):** blind to `SerializersModule` polymorphism and custom
  serializers, and must re-implement the compiler's optionality logic from source
  — the subtlest rule in the tool. Risk of divergence from the real descriptor.
- **Compiler plugin:** most powerful, most fragile, unnecessary given the snapshot
  architecture.

**Discovery (Approach C) — class-dir scanning (#55).** Extraction (Approach A)
needs a list of types to reflect on. The primary source is explicit configuration;
when no types are configured the extractor discovers them by unioning two
producer-agnostic sources: the classpath manifest
(`META-INF/serialkompat/serializable-types.txt`, one `@Serializable` FQN per line)
and a scan of compiled class directories (`--scan-classes`). The scan is a
dependency-free class-file parse — no classloading: `@Serializable` is
RUNTIME-retained, so annotated classes carry it in `RuntimeVisibleAnnotations`.
Generic classes are skipped as scan roots, loudly — they have no standalone wire
shape and their concrete shapes are covered at use sites (a survey of two
production codebases found 9/9 generics nested in one; the other's 2/2 root-only
envelopes are the logged, tracked gap). Unreadable class files degrade to OPAQUE
coverage gaps. **KSP remains rejected** (maintainer decision, issue #22); the
compiler-plugin producer originally tracked in #55 is retired — #22 only mandated
a compiler plugin *over KSP*, and scanning proved not-awkward.

### Discovery modes (#115)

Discovery (§4 above) decides *which* types are candidates; `discovery` decides
*which of those candidates are checked* when `types` is empty:

| Mode | Checked | Rationale |
|---|---|---|
| `EXPLICIT` (default) | Only `types` | Unchanged pre-#115 behavior — zero-risk default |
| `OPT_OUT` | Discovered minus `@SerialkompatIgnore` | Coverage-by-default; escape hatch for the intentionally-unstable few |
| `OPT_IN` | Only `@SerialkompatChecked` | Gradual adoption — nothing joins the gate until reviewed |

**Precedence**, applied in this order, in every mode:

1. A non-empty `types` list always wins — `discovery` is only consulted when
   `types` is empty.
2. Annotations refine the **scanned** set only. Classpath-manifest entries
   (`META-INF/serialkompat/serializable-types.txt`) are a deliberate,
   producer-asserted act — like an explicit `types` entry — and bypass
   annotation filtering entirely: they're unioned into the checked set in
   `OPT_OUT`/`OPT_IN` regardless of `@SerialkompatIgnore`/`@SerialkompatChecked`.
3. `include`/`exclude` serial-name prefixes apply after discovery and
   filtering, in all modes — unchanged from before this feature.

**Why annotations are scanner-detected, not classloaded.** `@SerialkompatIgnore`
and `@SerialkompatChecked` (new `serialkompat-annotations` KMP artifact) are
read the same way `@Serializable` itself is detected in the class-dir scan
(§4): a class-file parse of `RuntimeVisibleAnnotations`, no classloading. This
keeps the discovery-time guarantee from §4 intact — a broken or unrelated
classpath entry still can't crash extraction — and means the filter only ever
sees types the scanner could already parse; it can't fabricate a false
inclusion/exclusion the scan didn't itself derive from bytecode.

**KMP `jvm()`-target floor.** Extraction runs against compiled JVM descriptors
(§4), so a Kotlin Multiplatform module only participates in discovery/checking
when it declares a `jvm()` target — that's a floor imposed by where the
`SerialDescriptor` bytecode lives, not an arbitrary restriction. Models are
annotated in `commonMain`; `serialkompat-annotations` is itself multiplatform
so the annotation type resolves there, but the *scan* still only sees the
compiled JVM output.

---

## 5. Baseline model (git-ref-live)

**Decision: no mutable committed baseline in the gate's critical path.** A single
hand-synced baseline file is only an "acknowledge you changed the schema" gate
(all BCV is) — running `dump` on a breaking change makes `current == committed`
and CI goes green. It can be overwritten to bless a break, and it can go stale.

**The gate recomputes both sides from source on every run.** For a PR it extracts
the PR-head schema and the **target branch** schema, diffs, classifies, fails on
unacknowledged breaks. Nothing committed, nothing to run locally, can't be
defeated by a `dump`, and — critically — the staleness gap the user flagged is
**structurally impossible** because there is no stored baseline of record.

### git-ref mechanics

Runtime reflection needs bytecode, so "the target branch's schema" means building
the target ref:
1. Look up a **content-addressed cache** keyed by `(baseline SHA + tool version +
   config hash)`.
2. On miss: `git worktree add` a throwaway checkout of that SHA, run
   `serialkompatExtract` there, cache the result. `main` compiles at most once per
   commit, reused across all PRs.
3. **Fail-closed:** if a cached baseline cannot be reproduced/validated, the gate
   refuses to run rather than trust a suspect baseline. Never fail-open.

The SHA-keyed cache is safe because it is content-addressed (a commit SHA
deterministically produces one schema), not hand-synced.

### Persisted-data horizon (v1: append-only published history)

git-ref-vs-`main` fully covers **live-service** compat (main = deployed) but not
**persisted data** written by a release from years ago — and rebuilding ancient
code may be impossible. So long-horizon baselines come from an **append-only,
write-once published schema history**: each release publishes an immutable,
version-tagged snapshot (artifact repo or `schemas/` location) via **release CI**,
from the exact release commit. The gate then diffs against the latest
(live-service) and optionally *all* prior releases (`_TRANSITIVE`, for persisted).
Append-only ⇒ no "dump defeats the gate" hazard; a periodic drift audit
re-extracts a tag and fails closed if it disagrees with what was published.

**Wired (#88):** `serialkompatRecord` writes `<version>.snapshot` into a
source-controlled `history { dir }` (default `serialkompat/history/`), keyed by
version, atomically and append-only (refuses to overwrite). Each entry carries an
`@history version=… recordedAt=…` header — a block key `SnapshotFormat` never
emits, so it can't collide with schema content — and load validates every entry,
failing closed on a torn/corrupt one rather than under-reporting. Entries load in
**semver** order (not lexicographic, so `1.9.0` < `1.10.0`).
`serialkompatCheckHistory` runs `TransitiveCompatibility` over the history and is
wired into `check`, but no-ops until a version is recorded. Recording is decoupled
from Maven publishing (#24): a consumer can record + commit manually or from any
release step.

**Retention (#121):** `history { sinceVersion / depth / maxAge }` bounds how far
back the check reaches (the persisted-data horizon isn't "forever"). Each bound
keeps a window; combining is **most-permissive** (union — a second bound never
silently narrows coverage). `maxAge` uses the stored `recordedAt`. When a horizon
drops versions, the check logs it, so "compatible" is never read as "compatible
with all history". The shared `VersionOrder` (semver, total order) is used by both
load ordering and the `sinceVersion` bound.

---

## 6. Config model (bind to the real `Json`, don't re-declare)

Compatibility is a function of `(change, direction, reader/writer config)`. The
same change is safe or breaking depending on the `Json` config, so config is a
first-class input, not an afterthought.

`Json.configuration` is public API. The tool reads the settings straight off the
user's actual `Json` instance:

```kotlin
serialkompat {
  types.set(listOf("com.mercury.wire.OrderEvent"))        // roots to check
  jsonInstance.set("com.mercury.wire.WireJson.instance")  // real config — read, not re-declared
  direction.set(CompatibilityDirection.FULL)              // policy; cannot be inferred; stays declared
}
```

**Resolution order:** read from the `Json` instance → else explicit config in the
extension → else conservative/strict *with a loud "assuming" warning*.

**This is correctness, not convenience.** These `Json` settings change the wire
shape or decode behavior; hand-re-declaring them would silently drift:
- `namingStrategy` (e.g. `SnakeCase`) — renames every key.
- `classDiscriminator` / `classDiscriminatorMode` — polymorphic discriminator key
  and whether it's emitted.
- `useAlternativeNames` (does `@JsonNames` apply?), `coerceInputValues`,
  `ignoreUnknownKeys`, `encodeDefaults`, `explicitNulls`.

**Config is part of the contract, so config *changes* are classified too:**
- flip `namingStrategy` → every key renamed → **BREAK** (both directions)
- change `classDiscriminator`/mode → polymorphic **BREAK**
- tighten `ignoreUnknownKeys` true→false → your own readers got stricter →
  **WARN** ("previously-safe additions now break for your services")

**Honest limit:** reading *your* `Json` config describes the sides you own (Kotlin
producers/consumers). A non-Kotlin client's tolerance is not in there, so for
externally-facing scopes you can pin a stricter assumption
(`readerTolerance = STRICT`) to override "what my own Json does." Multiple wire
boundaries → map scope → `Json` instance.

---

## 7. Rule engine

### Knobs (the "compatibility profile"), declared per scope

| Knob | Values | Default | Notes |
|---|---|---|---|
| **Direction** | `BACKWARD` / `FORWARD` / `FULL` | `FULL` | Confluent model; overridable per type |
| **Reader tolerance** | strict / `ignoreUnknownKeys` | read from `Json`, else **strict** | overridable per scope for third-party readers |
| **Fail floor** | `BREAKING` / `DANGEROUS` | `BREAKING` | fail at/above; below is reported only |

Severity tiers: **BREAK** (a decode will throw), **WARN** (config-dependent, or a
*silent* semantic break — no exception but wrong/lost data), **SAFE**.

### The matrix

`B` = backward (new code reads old data). `F` = forward (old code reads new data).
Under `FULL` + **strict reader**:

| Change | B (new◄old) | F (old◄new) | What flips it |
|---|:---:|:---:|---|
| Add field **with default** (optional) | SAFE | **WARN→BREAK** | `ignoreUnknownKeys`→SAFE forward |
| Add field **no default / `@Required`** | **BREAK** | **WARN→BREAK** | backward = `MissingFieldException`; nullable + reader `explicitNulls=false`→**SAFE**³ |
| Remove **optional** field | **WARN→BREAK** | SAFE¹ | `ignoreUnknownKeys`→**WARN** (silent data-loss) backward² |
| Remove **required** field | WARN→BREAK | **BREAK** | forward = old code needs it; nullable + reader `explicitNulls=false`→**WARN**³ |
| **Rename** key (no `@JsonNames`) | **BREAK** | **BREAK** | if new field optional: silent data-loss = **WARN** |
| Rename **with `@JsonNames(old)`** | SAFE | **BREAK** | alias fixes *backward* only |
| optional → **required** | **BREAK** | SAFE | backward = old payloads omit it |
| required → **optional** | SAFE | **WARN→BREAK** | forward depends on `encodeDefaults` |
| non-null → **nullable** (`T`→`T?`) | SAFE | **WARN→BREAK** | forward: old reader chokes on emitted `null` |
| nullable → **non-null** (`T?`→`T`) | **BREAK** | SAFE | backward: old `null` can't decode |
| Change type (`String`↔`Int`, restructure) | **BREAK** | **BREAK** | numeric widen `Int→Long`: B SAFE / F BREAK |
| Enum **add** value | SAFE | **BREAK→WARN** | forward WARN iff `coerceInputValues` AND every reading field has a default (else BREAK) |
| Enum **remove** value | **BREAK** | SAFE | |
| Enum/subtype **rename** (serial name) | **BREAK** | **BREAK** | discriminator/name mismatch |
| Polymorphic **add** subtype | SAFE | **BREAK→WARN** | forward WARN (coerced to the sentinel) iff the base registered a default deserializer, else BREAK |
| Polymorphic **remove** subtype | **BREAK** | SAFE | |
| Change **discriminator** key | **BREAK** | **BREAK** | |
| **Delete** a whole contract type | **BREAK** (persisted) | **BREAK** | |
| **Add** a whole contract type | SAFE | SAFE | |

¹ Forward-safety of removing an optional field also depends on whether *old* code
had it optional — the engine reads both descriptors, so it knows.

² A tolerant (`ignoreUnknownKeys`) reader decodes an old payload without error, but the
removed field's value is silently dropped — a *silent semantic break* (no exception,
lost data), which is exactly the WARN tier's definition above. So removal under a
tolerant reader is **WARN**, never SAFE. This is also the only signal the gate has for
a field **rename** (no `@JsonNames`): the differ decomposes it into remove + add, and the
remove half carries the WARN. (Earlier drafts said `→SAFE`; reconciled to `→WARN` since a
lone SAFE would let a rename silently lose data — see the false-SAFE fixed in #77.) The
remove half only carries the WARN **backward**; the rename's *forward* loss (an old reader
dropping the new key) is forward-`SAFE`, identical to any field addition — so a rename is
surfaced once, as a backward WARN, not flagged in both directions.

³ For a **nullable** field with no default, `explicitNulls=false` on the reader decodes an
*absent* field as `null` (no `MissingFieldException`) — the same tolerance the standard
`val x: T? = null` idiom gets, but without the default. So **adding** such a field is
backward-**SAFE** (the reader is the *new* config; a brand-new field decodes to `null`, the
only sensible value — nothing is lost), and **removing** one is forward-**WARN** (the reader
is the *old* config; the old code silently sees `null` where data once lived — a silent
substitution, not a clean pass, so `WARN`, never `SAFE`, per the silent-data-loss tier). The
asymmetry vs. removing an *optional* field (forward-`SAFE`) is deliberate: a declared default
is an intentional fallback, a config-coerced `null` is not. Under the default
`explicitNulls=true` both stay **BREAK** (#118).

Each row is a **named rule** (`PROPERTY_REMOVED`, `PROPERTY_TYPE_CHANGED`,
`ENUM_VALUE_REMOVED`, `PROPERTY_NULLABILITY`, `DISCRIMINATOR_VALUE_CHANGED`, …) — these
exact strings are the public keys used in `acceptedBreaks` — so findings are greppable and
individually suppressible.

Beyond version-to-version deltas, the differ also surfaces **static model defects** on
the *current* snapshot every run — a `COVERAGE_GAP` for an unanalysable type (§10), and a
`DISCRIMINATOR_COLLISION` when a sealed/polymorphic subtype declares a property whose JSON
key equals the base's class discriminator. That model is unserializable
(kotlinx-serialization throws `JsonEncodingException` on encode), so the gate flags it as a
`BREAK` statically — before the first encode fails — unless `classDiscriminatorMode = NONE`
means no discriminator is emitted (nothing to collide with).

### Escape hatches & accepting a break

- **`@JsonNames` understood as mitigation** — a rename bridged by an alias is
  auto-downgraded (backward), rewarding the right fix.
- **Accepted breaks in a committed exceptions file** `serialkompat-exceptions.yaml`,
  added in the PR that makes the break:
  ```yaml
  - type: com.mercury.orders.OrderEvent
    rule: PROPERTY_REMOVED
    direction: BACKWARD
    reason: "field 'legacyNote' unused since v4; major bump"
    acceptedBy: chrisjenx
  ```
  The gate downgrades matching breaks to **acknowledged** (logged, not failed); an
  **unlisted** break fails with the exact stanza to paste. A reviewer sees the diff
  to this file = the precise breakage the PR sanctions. (An inline
  `@AcceptsBreakingChange(...)` annotation may be a secondary form.)

### Report

Per finding: `type · rule · direction · severity · old→new · human explanation ·
fix hint` (add a default / add `@JsonNames` / bump major / add exception). Emitted
as console + machine JSON; posted as a PR comment on CI (§9).

---

## 8. Type identity & rename/move tracking

A naive differ keyed on fully-qualified class name mis-reports a rename/move as
*delete old + add new* — two false findings, and the "delete" could fire as a
`BREAK` for persisted data.

**Key fact:** for a plain (non-polymorphic) `@Serializable` class, the class
name/package is **not on the wire** — renaming/moving it is wire-neutral. For a
sealed/polymorphic subtype, the `serialName` *is* the discriminator value, so the
same rename **is** a wire break. The runtime walk sees each type's usage context,
so it knows which case applies.

- **Identity key = `serialName`** (default = FQN; pinning `@SerialName` decouples
  identity from code location, making a type immune to move/rename churn for free —
  and pinning is best practice for anything polymorphic or persisted). Matching by
  serialName lets the differ pair versions and diff *contents* instead of
  delete+add.
- **Tracking an intentional move/rename** (serialName changed): `@PreviousSerialName("…")`
  on the type, or a `renames:` map in config. The differ follows the move, keeps
  diffing fields, emits no spurious delete+add.

| Situation | Finding | Severity |
|---|---|---|
| Type used **only non-polymorphically**, renamed/moved | `TYPE_MOVED` | **SAFE** — reported, doesn't fail (the "ignore") |
| Type is **sealed/polymorphic**, serialName changed | `DISCRIMINATOR_VALUE_CHANGED` | **BREAK** — unless `@SerialName` pins old value or an exception is filed |
| Move **not** declared, can't auto-match | rename **detection** heuristic pairs structurally-identical delete+add and *suggests* `@PreviousSerialName`/`renames` | flagged, not silently split |

This keeps the **no-silent-exclusions** invariant honest — a moved type is tracked
to its new home, never quietly dropped.

**Implemented (#12):** the differ takes a `renames` map (old serialName → new)
and follows declared moves — emitting `ContractMoved` and diffing contents
instead of remove+add; the classifier scores a plain move `SAFE` and a
polymorphic move `DISCRIMINATOR_VALUE_CHANGED` (BREAK). Only renames whose both
endpoints exist are honored, so a stale entry can't drop a contract. The
`@PreviousSerialName` annotation form and the structural rename-detection
heuristic remain for v0.5.

---

## 9. Developer workflow & CI integration

### Application (KMP)

```kotlin
plugins {
  kotlin("jvm")
  kotlin("plugin.serialization")
  id("com.chrisjenx.serialkompat")
}

serialkompat {
  types.set(listOf("com.mercury.wire.OrderEvent"))        // roots to check
  jsonInstance.set("com.mercury.wire.WireJson.instance")  // real Json config — read, not re-declared
  direction.set(CompatibilityDirection.FULL)
  baselineRef.set("origin/main")                          // recomputed live from this ref
  failOnBreaking.set(true)
  // Scope by serial-name prefix (exclude wins); a module that never crosses the wire can be left out.
  include.set(listOf(""))                                 // default: everything
  exclude.set(listOf("com.mercury.internal."))
  // Escape hatches:
  acceptedBreaks.set(listOf("com.mercury.wire.OrderEvent PROPERTY_REMOVED"))  // "<serialName> <RULE> [DIRECTION]"
  renames.set(mapOf("com.mercury.old.Name" to "com.mercury.new.Name"))        // old → new serial name
}
```

### Tasks

| Task | Does | Wired to |
|---|---|---|
| `serialkompatCheck` | extract current → resolve baseline → diff → classify → report; non-zero exit on unlisted breaks | **`check` lifecycle** |
| `serialkompatCheckAgainst -Pref=<ref>` | same, against an arbitrary ref | ad-hoc / local |
| `serialkompatExtract` | emit current schema to `build/` (internal) | dependency of the above |

No "commit the baseline" task in the v0 gate path (that was the staleness trap).

### Two loops
- **Local (zero maintenance):** `./gradlew serialkompatCheck` compares working tree
  vs `origin/main`, fast after first cached baseline. Optional pre-push hook.
- **CI gate:** same task; baseline = the PR's target branch. Unlisted break → fail;
  post findings + schema diff as a **sticky PR comment / job summary**.

### Coverage invariant (no silent exclusions)
Every in-scope `@Serializable` type is either checked or *explicitly, visibly*
suppressed; anything that is neither **fails the gate**. Suppressions are listed in
the report. A model cannot silently fall out of the gate.

### Packaging
- **`serialkompat-gradle`** — primary interface (KMP compiles through Gradle anyway).
- **`serialkompat` GitHub Action** — one *unified* action (buf's lesson: don't
  fragment) running the task and posting the PR comment. The Gradle task stays
  CI-agnostic (emits JSON report + exit code); the Action does GitHub-specific
  posting.
- **`serialkompat-cli`** — v1, for non-Gradle / cross-repo use.

---

## 10. Robustness (a gate must never crash, and never silently pass what it can't analyze)

- **Never throw on a model.** Unknown `SerialKind`, unresolved contextual
  serializer, generic instantiations the walk can't fully render → recorded as an
  **opaque node**, not an exception.
- **Custom serializers are the fidelity ceiling.** A `@Serializable(with = …)`
  type's wire shape is whatever its serializer emits; the descriptor may not fully
  describe it. Capture what the descriptor exposes and **mark the type "shape
  derived from custom serializer — may not capture full behavior."**
- **Unanalyzable ≠ safe.** Any type the tool can't faithfully model is surfaced as
  an explicit **coverage gap**, governed by `failOnUnanalyzable` (default: WARN, so
  adoption isn't blocked by one exotic type — but loud, never assumed-safe).
- **Determinism:** sorted + normalized snapshot ⇒ re-runs byte-identical, field
  reordering yields zero diff. BFS + visited-set for cyclic graphs.

---

## 11. Testing the tool (verify rules against the real library)

A wrong compat tool is worse than none, so the rule matrix is validated
empirically. TDD from the fixtures: write fixture + expected finding + oracle
assertion first, then the rule.

| Layer | What it does |
|---|---|
| **Round-trip oracle** (headline) | For each fixture pair: serialize a payload with the *old* model, decode with the *new* one (and vice versa) using **real kotlinx-serialization** under the declared `Json` config; observe actual outcome (success / `MissingFieldException` / unknown-key / silent data-loss) and **assert the classifier predicted it.** Every matrix row grounded in runtime truth. |
| **Golden fixture pairs** | `(old, new)` model pairs, each tagged `{rule, direction, severity}` — one+ per matrix row, incl. moves/renames and config changes. |
| **Snapshot determinism** | extract-twice-identical; reorder-fields-identical. |
| **Extractor fidelity** | rich model (nested, sealed, enums, generics, value classes, contextual) → assert captured structure. |
| **kotlinx version matrix** | run across supported kotlinx-serialization versions. |

---

## 12. `kotlinx-schema` reuse — spike outcome: **vendor the walk** (resolved, #6)

The v0 fidelity spike is done (`DescriptorFidelitySpikeTest`, issue #6). **Decision:
vendor a direct `SerialDescriptor` walk; do not depend on `kotlinx-schema`.**

`kotlinx-schema` **is** published to Maven Central
(`org.jetbrains.kotlinx:kotlinx-schema-generator-json:0.5.0`, 2026-04-07), so
availability was never the problem — **fidelity** is. Its IR is a one-way,
JSON-Schema-shaped projection built for schema *emission*, and it drops or
flattens exactly the facts compatibility turns on:
- **per-element optionality** is collapsed into an `ObjectNode.required` name-set
  plus a `hasDefaultValue` boolean — the serialization path never even sets the
  richer fields;
- **`@JsonNames`** aliases are not captured at all;
- **`@JsonClassDiscriminator`** is ignored (discriminator name hardcoded from
  `Json` config);
- arbitrary element annotations are reduced to a single description string, and
  finer primitive kinds (BYTE/SHORT/CHAR/unsigned) are folded away.

It is also experimental 0.x ("nothing settled") with open bugs on the very path
we'd use (recursion `StackOverflow`; brittle sealed-structure `require`).

The spike confirmed the alternative is trivial and complete: a compiled
`SerialDescriptor` exposes **everything** we need directly —

| Fact | How it's read (verified in the spike) |
|---|---|
| serial name (post-`@SerialName`) | `descriptor.serialName`, `getElementName(i)` |
| per-element optionality | `isElementOptional(i)` (authoritative; no re-derivation) |
| nullability | `getElementDescriptor(i).isNullable` |
| `@JsonNames` aliases | `getElementAnnotations(i).filterIsInstance<JsonNames>()` |
| enum values | `SerialKind.ENUM` + `elementNames` |
| sealed subtypes + discriminator | `PolymorphicKind.SEALED`; element 1 (`"value"`) child descriptors |
| open polymorphism | `SerializersModule.dumpTo(SerializersModuleCollector { polymorphic(...) })` |

So we own a small, stable walk (depends only on `kotlinx-serialization-core`,
which is stable versioned API) behind the `Extractor` interface. The one piece
worth *copying* (not depending on) from `kotlinx-schema` is its
`SerializersModuleCollector` open-polymorphism resolution (~30 lines); that
pattern is reflected in the spike. The walk was never the hard part — the rules are.

---

## 13. Roadmap

- **v0 (MVP):** runtime JVM extractor + `Snapshot` + differ + classifier for the
  full §7 matrix (fields, optionality, nullability, types, enums, sealed/polymorphic,
  type moves) + config read from the `Json` instance + git-ref-live baseline
  (worktree + SHA cache, fail-closed) + `serialkompatCheck` wired to `check` +
  exceptions file + console/JSON report + **round-trip oracle harness**. Default
  `FULL`.
- **v0.5:** unified GitHub Action + sticky PR comment; `serialkompatCheckAgainst`;
  rename-detection heuristic.
- **v1:** append-only published schema history + transitive checks (persisted
  horizon); standalone CLI. (Automated discovery — Approach C — shipped post-v1
  as an extractor class-dir scan, #55; explicit config + the manifest contract
  remain.)
- **Later (only if needed):** CBOR/ProtoBuf rules (field order / `@ProtoNumber`);
  IDE inspection.

---

## 14. Residual risks to validate in the plan
- `@EncodeDefault` mode is **not recoverable** via Approach A — it is not a
  `@SerialInfo` annotation, so it never appears in `getElementAnnotations` (#7).
  The `Element.encodeDefault` field stays null from runtime extraction; a
  compiler-plugin extractor (Approach C) could read it from source.
- A field's **default *value*** is likewise **not recoverable** via Approach A —
  the descriptor exposes `isElementOptional` (that a default exists) but never the
  value itself (it lives in the generated `deserialize`). So the enum coerce-fallback
  fidelity (#129) keys on optionality + *how* the enum is referenced (a defaulted
  direct property can coerce an added value to its default → WARN; a required field,
  a `List`/`Map` usage, or a top-level decode has no default and throws → BREAK),
  not on a recorded sentinel value. A compiler-plugin extractor could record the
  actual default (and a designated `UNKNOWN` sentinel) for a tighter verdict.
  Residual: this is best-effort per snapshot — an enum read *both* by a defaulted
  direct field *and* at a top level or inside an `OPAQUE` contract (neither visible
  as a field) is still classified coercible, so that hidden use is not proven sound.
  It is a narrow gap, and strictly less unsound than the prior config-only rule.
- **`@JvmInline value class`es are unwrapped to their underlying wire type.** A
  serializable inline class serializes as its single underlying value (never a
  wrapper object), so the extractor reads `descriptor.isInline` and records the
  element by the wrapped type — e.g. a field of type `UserId(val raw: Int)` is
  recorded as `kotlin.Int`. Without this, swapping a raw `Int` for a wire-identical
  `UserId` (or back) would surface as a spurious `ElementTypeChanged` and score a
  false `BREAK`. Value classes are transparent: they are not emitted as their own
  contracts, but a value class wrapping a `@Serializable` type still walks that type.
- Generic/parameterized `@Serializable` descriptors (per-instantiation shape).
- Contextual serializers require the `SerializersModule` (supplied by the `Json`
  instance the user points at).
- Rebuilding *recent* refs is reliable; *ancient* ones are not (→ old baselines
  come from published history, not recompilation).
- ~~`kotlinx-schema` IR fidelity~~ — **resolved (#6):** vendor the walk; a compiled
  `SerialDescriptor` exposes everything directly (see §12).

---

## 15. Decisions log (for traceability)
1. **Threat model:** JSON wire, `FULL`, live + persisted, heterogeneous consumers.
2. **Baseline:** git-ref-live (no mutable committed baseline); content-addressed
   cache; fail-closed; v1 append-only published history for persisted horizon.
3. **Platform:** KMP; extraction on the JVM target.
4. **Extractor:** runtime `SerialDescriptor` reflection (Approach A). Spike #6
   resolved: **vendor** the descriptor walk behind an `Extractor` anti-corruption
   layer — `kotlinx-schema`'s IR is too lossy (see §12). Discovery (Approach C)
   shipped as class-dir scanning inside the extractor (#55 re-scope); **KSP
   rejected** (#22); the compiler-plugin producer is retired.
5. **Scope:** check-by-default per applied module, with module/package/file/type
   suppression; no-silent-exclusions coverage invariant.
6. **Config:** read from the real `Json` instance; config is part of the snapshot;
   config changes are classified; strict override for third-party-facing scopes.
7. **Identity:** match by `serialName`; `@PreviousSerialName`/`renames` to track
   moves; plain-type moves SAFE, polymorphic discriminator renames BREAK.
8. **Name:** `serialkompat` (`com.chrisjenx.serialkompat`).
