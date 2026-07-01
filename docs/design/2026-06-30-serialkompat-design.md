# serialkompat — design

**Date:** 2026-06-30
**Status:** Design approved; ready for implementation planning
**Repo:** `github.com/chrisjenx/serialkompat` (public, personal)
**Coordinates:** plugin id `io.github.chrisjenx.serialkompat`, Maven group `io.github.chrisjenx`

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
                  (KSP later)                    never knows their origin
```

The diff/classify engine is fully decoupled from extraction and from where
baselines come from. This is what lets git-ref mode, local mode, and (v1)
published-history mode share one engine, and lets a KSP extractor drop in later
without touching the rules.

### Modules

| Module | Responsibility | Depends on |
|---|---|---|
| `serialkompat-core` | `Snapshot` model + canonical serialize/parse, `Differ`, `Classifier`, rule set, `Report`. **Pure Kotlin, no kotlinx-serialization runtime, no I/O.** | — |
| `serialkompat-extractor` | Walk `SerialDescriptor` → build `Snapshot`. Reuses `kotlinx-schema`'s introspector behind an anti-corruption layer (incl. `SerializersModule` polymorphism). Runs on JVM. | kotlinx-serialization, kotlinx-schema |
| `serialkompat-gradle` | `serialkompatCheck` / `serialkompatCheckAgainst` / `serialkompatExtract` / `serialkompatDump` tasks; config extension. | core, extractor |
| `serialkompat-cli` | Thin CLI for cross-repo / non-Gradle use. v1. | core, extractor |

### The `Snapshot` format

The canonical model of the wire contract. Serialized to a **deterministic,
sorted, human-readable text form** (BCV's lesson) so it is diffable and
reviewable; a machine-readable JSON form may be emitted alongside for tooling.

**Elements are sorted by serial name, not declaration order** — JSON does not care
about field order, so reordering produces zero diff (and a rename correctly
surfaces as remove+add). Number/format normalization ensures byte-stability.

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

**KSP-based discovery (Approach C)** remains a v1 option if JVM classpath scanning
proves awkward for the KMP module layout — it composes with runtime extraction
(KSP enumerates types portably; runtime walks their descriptors).

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

---

## 6. Config model (bind to the real `Json`, don't re-declare)

Compatibility is a function of `(change, direction, reader/writer config)`. The
same change is safe or breaking depending on the `Json` config, so config is a
first-class input, not an afterthought.

`Json.configuration` is public API. The tool reads the settings straight off the
user's actual `Json` instance:

```kotlin
serialkompat {
  json      = "com.mercury.wire.WireJson.instance"   // real config — read, not re-declared
  direction = FULL                                    // policy; cannot be inferred; stays declared
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
| Add field **no default / `@Required`** | **BREAK** | **WARN→BREAK** | backward = `MissingFieldException` |
| Remove **optional** field | **WARN→BREAK** | SAFE¹ | `ignoreUnknownKeys`→SAFE backward |
| Remove **required** field | WARN→BREAK | **BREAK** | forward = old code needs it |
| **Rename** key (no `@JsonNames`) | **BREAK** | **BREAK** | if new field optional: silent data-loss = **WARN** |
| Rename **with `@JsonNames(old)`** | SAFE | **BREAK** | alias fixes *backward* only |
| optional → **required** | **BREAK** | SAFE | backward = old payloads omit it |
| required → **optional** | SAFE | **WARN→BREAK** | forward depends on `encodeDefaults` |
| non-null → **nullable** (`T`→`T?`) | SAFE | **WARN→BREAK** | forward: old reader chokes on emitted `null` |
| nullable → **non-null** (`T?`→`T`) | **BREAK** | SAFE | backward: old `null` can't decode |
| Change type (`String`↔`Int`, restructure) | **BREAK** | **BREAK** | numeric widen `Int→Long`: B SAFE / F BREAK |
| Enum **add** value | SAFE | **WARN→BREAK** | forward SAFE iff `coerceInputValues` + default |
| Enum **remove** value | **BREAK** | SAFE | |
| Enum/subtype **rename** (serial name) | **BREAK** | **BREAK** | discriminator/name mismatch |
| Polymorphic **add** subtype | SAFE | **WARN→BREAK** | forward SAFE iff default deserializer |
| Polymorphic **remove** subtype | **BREAK** | SAFE | |
| Change **discriminator** key | **BREAK** | **BREAK** | |
| **Delete** a whole contract type | **BREAK** (persisted) | **BREAK** | |
| **Add** a whole contract type | SAFE | SAFE | |

¹ Forward-safety of removing an optional field also depends on whether *old* code
had it optional — the engine reads both descriptors, so it knows.

Each row is a **named rule** (`PROPERTY_NO_DELETE`, `PROPERTY_SAME_TYPE`,
`ENUM_VALUE_NO_DELETE`, `NULLABILITY_NO_NARROW`, `DISCRIMINATOR_VALUE_CHANGED`, …)
so findings are greppable and individually suppressible.

### Escape hatches & accepting a break

- **`@JsonNames` understood as mitigation** — a rename bridged by an alias is
  auto-downgraded (backward), rewarding the right fix.
- **Accepted breaks in a committed exceptions file** `serialkompat-exceptions.yaml`,
  added in the PR that makes the break:
  ```yaml
  - type: com.mercury.orders.OrderEvent
    rule: PROPERTY_NO_DELETE
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

---

## 9. Developer workflow & CI integration

### Application (KMP)

```kotlin
plugins {
  kotlin("multiplatform")
  kotlin("plugin.serialization")
  id("io.github.chrisjenx.serialkompat") version "0.1.0"
}

serialkompat {
  json      = "com.mercury.wire.WireJson.instance"
  direction = FULL
  baseline  = gitRef("origin/main")            // or mergeBase()
  failOn    = BREAKING
  scope {
    // default = every @Serializable in this module; suppress at any granularity:
    exclude(package = "com.mercury.internal.**")
    exclude(path    = "src/**/debug/**")
    // + @IgnoreCompat on a type; all suppressions logged in the report
  }
}
```

### Tasks

| Task | Does | Wired to |
|---|---|---|
| `serialkompatCheck` | extract current → resolve baseline → diff → classify → report; non-zero exit on unlisted breaks | **`check` lifecycle** |
| `serialkompatCheckAgainst -Pref=<ref>` | same, against an arbitrary ref | ad-hoc / local |
| `serialkompatExtract` | emit current schema to `build/` (internal) | dependency of the above |
| `serialkompatDump` | write a shareable snapshot (seeds v1 history; debugging) | manual, **not** required |

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

## 12. `kotlinx-schema` reuse risk

It is **v0.5.0, experimental, `InternalSchemaGeneratorApi`, "nothing settled"**,
and its IR targets schema *emission*, so it may drop fields compat depends on
(`@SerialName`, element order, `@Required`-vs-optional, discriminator config,
value-class inline). Mitigation:
1. **Anti-corruption layer** — our `Snapshot` is the contract; kotlinx-schema sits
   behind an `Extractor` interface so its churn can't reach the rule engine.
2. **v0 fidelity spike** (first plan task): confirm the IR preserves serialName /
   `isElementOptional` / nullability / enum values / sealed subtypes + discriminator.
   Read `SerialDescriptor` directly for anything it drops.
3. **Fallback ready:** vendor our own walk from the `ProtoBufSchemaGenerator`
   reference (a few hundred lines, well-understood) if it's too lossy/unstable. The
   walk was never the hard part — the rules are.

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
  horizon); optional KSP discovery (Approach C) if classpath scan is awkward;
  standalone CLI.
- **Later (only if needed):** CBOR/ProtoBuf rules (field order / `@ProtoNumber`);
  IDE inspection.

---

## 14. Residual risks to validate in the plan
- Generic/parameterized `@Serializable` descriptors (per-instantiation shape).
- Contextual serializers require the `SerializersModule` (supplied by the `Json`
  instance the user points at).
- Rebuilding *recent* refs is reliable; *ancient* ones are not (→ old baselines
  come from published history, not recompilation).
- `kotlinx-schema` IR fidelity for `@SerialName` / optionality / discriminator
  (the §12 spike gates this).

---

## 15. Decisions log (for traceability)
1. **Threat model:** JSON wire, `FULL`, live + persisted, heterogeneous consumers.
2. **Baseline:** git-ref-live (no mutable committed baseline); content-addressed
   cache; fail-closed; v1 append-only published history for persisted horizon.
3. **Platform:** KMP; extraction on the JVM target.
4. **Extractor:** runtime `SerialDescriptor` reflection (Approach A), reusing
   `kotlinx-schema` behind an anti-corruption layer; KSP discovery deferred to v1.
5. **Scope:** check-by-default per applied module, with module/package/file/type
   suppression; no-silent-exclusions coverage invariant.
6. **Config:** read from the real `Json` instance; config is part of the snapshot;
   config changes are classified; strict override for third-party-facing scopes.
7. **Identity:** match by `serialName`; `@PreviousSerialName`/`renames` to track
   moves; plain-type moves SAFE, polymorphic discriminator renames BREAK.
8. **Name:** `serialkompat` (`io.github.chrisjenx.serialkompat`).
