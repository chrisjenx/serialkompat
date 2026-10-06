# serialkompat — design

**Date:** 2026-06-30
**Status:** Implemented (living design doc). Last reconciled against the code on
2026-10-06 (through #183). Where the shipped behaviour moved away from the
original design, the section carries an **Updated (#NNN):** note rather than a
silent rewrite. The CI-gated, user-facing rule matrix is
[`docs/rules.md`](../rules.md) (`checkRulesDoc` / `checkRulesProof`); if it and
§7 below ever disagree, `docs/rules.md` and the `Classifier` win.
**Repo:** `github.com/chrisjenx/serialkompat` (public, personal)
**Coordinates:** plugin id `com.chrisjenx.serialkompat`, Maven group `com.chrisjenx`
(Maven Central only; the plugin marker is not yet on the Gradle Plugin Portal)

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
                                               never knows their origin
```

The diff/classify engine is fully decoupled from extraction and from where
baselines come from. This is what lets git-ref mode, local mode, the standalone
CLI, and the published-history mode share one engine (`CompatibilityEngine`),
and would let a different extractor drop in without touching the rules.

**Updated (#55, #176):** the diagram originally anticipated a compiler-plugin
extractor. That producer is retired: discovery shipped as class-dir scanning
(§4), and `@EncodeDefault` turned out to be recoverable from bytecode (§14), so
nothing currently needs a compiler plugin.

### Modules

| Module | Responsibility | Depends on |
|---|---|---|
| `serialkompat-core` | `Snapshot` model + canonical serialize/parse, `Differ`, `Classifier`, rule set, `Report`. **Pure Kotlin, no kotlinx-serialization runtime, no I/O.** | — |
| `serialkompat-extractor` | Walk `SerialDescriptor` → build `Snapshot`, behind an `Extractor` interface (anti-corruption layer), incl. `SerializersModule` polymorphism. Vendors its own walk (see §12); does **not** depend on `kotlinx-schema`. Runs on JVM. | kotlinx-serialization |
| `serialkompat-gradle` | `serialkompatExtract` / `serialkompatCheck` / `serialkompatCheckAgainst` / `serialkompatRecord` / `serialkompatCheckHistory` tasks; `serialkompat { }` extension (§9). | core, extractor |
| `serialkompat-cli` | Standalone `serialkompat diff <baseline> <current>` over two snapshot files, for non-Gradle / cross-repo use. Does no extraction. | core |
| `serialkompat-annotations` | `@SerialkompatIgnore` / `@SerialkompatChecked` discovery markers (§4, #115). The only Kotlin Multiplatform module; `RUNTIME` retention so the scanner reads them from bytecode. | — |

### The `Snapshot` format

The canonical model of the wire contract. Serialized to a **deterministic,
sorted, human-readable text form** (BCV's lesson) so it is diffable and
reviewable. (**Updated:** no JSON form of the snapshot shipped; the text form
is the only snapshot format. Machine-readable output exists for the *report*
instead — see §7 "Report".)

**Elements are sorted by serial name, not declaration order** — JSON does not care
about field order, so reordering produces zero diff (and a rename correctly
surfaces as remove+add). Sorted emission with token-escaped free-text fields makes the
text byte-stable across runs.

Canonical form (as implemented in `SnapshotFormat`, issue #5; restructured onto
the `FormatDoc` document AST in #56 — one immutable node tree drives both the
writer and the kind-driven reader, so layout and escaping live in one place).
Separators are single spaces; name-bearing tokens are token-escaped (`\s` for a
space, etc.), so an element line tokenizes unambiguously on whitespace. Enum
`values=[…]` lines are parsed whole (comma-split only) — an enum value may
legally contain a space. List values (`jsonNames`, enum `values`) use the same
escape alphabet as name tokens plus one separator escape (`\,` for a literal
comma), so a list value containing whitespace, a comma, or a newline escapes
into a single delimiter-safe token (#146). Parsing is kind-driven: a body line structurally
invalid for the contract's declared `kind` is rejected loudly rather than
silently mis-mapped (parse is load-bearing — §5). Unknown header tokens,
element flags, and `@config` keys are tolerated for forward compatibility
(#128). Round-trip guarantee: `parse(serialize(s)) == s` for every
extractor-produced snapshot (#146 closed the last whitespace carve-out;
whitespace-free output stays byte-identical).
Blocks are sorted by serial name; within a contract, elements sort by key, enum
values sort, and subtypes sort by discriminator value — so reordering fields
produces a zero diff. An element whose type is another contract simply records
that contract's serial name as its type ref (no distinguishing `->` marker).
Primitive type refs are the descriptor's serial name (`kotlin.String`), and a
generic type-parameter hole renders as `#0` (§4):

```
@contract com.mercury.orders.OrderEvent kind=CLASS
  amountCents: kotlin.Long
  id: kotlin.String
  note: kotlin.String optional nullable encodeDefault=ALWAYS
  status: com.mercury.orders.OrderStatus
  tags: List<kotlin.String> optional jsonNames=[labels]

@contract com.mercury.orders.OrderStatus kind=ENUM
  values=[CANCELLED,CREATED,PAID]

@contract com.mercury.orders.Payment kind=SEALED discriminator=type polymorphicDefault=true
  subtypes:
    ach -> com.mercury.orders.AchPayment
    card -> com.mercury.orders.CardPayment

@config
  classDiscriminator=type
  classDiscriminatorMode=POLYMORPHIC
  coerceInputValues=false
  encodeDefaults=false
  explicitNulls=true
  ignoreUnknownKeys=false
  namingStrategy=none
  useAlternativeNames=true
```

Element flags are emitted only when set: `optional`, `nullable`, `jsonNames=[…]`,
and `encodeDefault=ALWAYS|NEVER|ABSENT` (absent token = mode unknown, #158/#176). The
contract header carries `polymorphicDefault=true` only when the base registered a
default deserializer (#128 for open, #181 for sealed), so older snapshots
round-trip unchanged. `@config` always emits all eight keys in alphabetical order.
`OPAQUE` contracts have a header and no body.

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

**Updated (#180): classpath order.** `serialkompatExtract` is a forked
`JavaExec` whose classpath is, in order: the project's runtime classpath
(`runtimeClasspath`, or `jvmRuntimeClasspath` for KMP), then the project's own
compiled class dirs, then the tool jars. The project's generated serializers
were compiled against the project's kotlinx-serialization and stdlib, so those
must win. The tool jars come from the plugin classloader (the plugin's
kotlinx-serialization, Gradle's embedded stdlib) and only fill what the project
lacks. Earlier builds put the tool jars first and shadowed newer project
runtimes.

The `SerialDescriptor` already contains exactly what wire compatibility depends
on: `elementNames` (real JSON keys), `isElementOptional(i)` (authoritative
optionality — already accounts for `@Required`/`@Transient`/defaults),
`isNullable`, `SerialKind`, enum entries, sealed subtypes. `@JsonNames` is read
via element annotations. `@EncodeDefault` is not a `@SerialInfo` annotation, so it
is read by reflecting on the model class behind the plugin-generated serializer
(the property's RUNTIME-retained synthetic `get<Name>$annotations` method), see §14.

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
Generic classes are resolved as scan roots by extracting their descriptor with
type-parameter *holes* (#139): a root-only generic envelope (`BaseResponse<T>`)
contributes a `CLASS` contract whose type-parameter positions render as a stable
sentinel (`#0`, `List<#0>`), so its own envelope fields (`status`, `count`) are
checked while the holes stay covered at concrete use sites. Resolution is
fill-if-absent — a concrete instantiation reached in the walk wins over the hole
envelope, so nested generics keep their existing shape and their type arguments
are never orphaned. A generic *sealed/polymorphic* hierarchy is out of scope this
cut and degrades to an OPAQUE coverage gap. Unreadable class files degrade to
OPAQUE coverage gaps. **KSP remains rejected** (maintainer decision, issue #22); the
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

**Updated: what shipped.**

- **Cache key is the commit SHA only** (`SnapshotCache`, one
  `build/serialkompat/baseline/<sha>.snapshot` per resolved ref), not
  `(SHA + tool version + config hash)`. It lives in the module's `build/`, so
  `clean` drops it. Note that a tool-version or `serialkompat { }` config change
  does not by itself invalidate an entry; a `clean` does.
- **Fail-closed is "never trust", not "refuse to run".** Writes are atomic
  (temp file + move). A cached entry that does not parse is deleted and treated
  as a miss, so the gate re-extracts rather than diffing against a corrupt
  baseline. A failed baseline extraction fails the check. A stale or
  half-registered worktree from a killed run is pruned before `worktree add`, so
  the gate self-heals.
- **Degenerate baseline (#78).** A baseline with zero contracts while the
  current schema has some fails the gate (`failOnEmptyBaseline`, default `true`),
  since it would otherwise diff as "everything added, all safe". First-time
  adopters set it to `false`.
- **Default ref (#116).** With `baselineRef` unset, the check auto-detects the
  repository's default branch at execution time, so `master` repos work too.
- **Baseline extraction is a nested Gradle build** of
  `<projectPath>:serialkompatExtract` in the worktree (using the repo's
  `gradlew` when present).
- **Build cache (#182).** `serialkompatExtract` is build-cacheable and
  relocatable: its key is the typed `ExtractArguments` inputs (`types`,
  `discovery`, `jsonInstance`, the `@Classpath` class dirs) plus the classpath,
  with no absolute paths. A checkout at another path, such as the baseline
  worktree, can therefore get `FROM-CACHE` from a shared or remote cache. So
  "`main` compiles at most once per commit, reused across all PRs" holds across
  machines only with a shared build cache; otherwise it holds per checkout via the
  SHA cache above. The check tasks are **intentionally never cached**: their
  verdict depends on what the baseline ref points at *now*, which is not a task
  input.
- **Multi-module builds (#183).** A build-wide `BaselineExtractionService`
  (`maxParallelUsages = 1`), used by `serialkompatCheck` and
  `serialkompatCheckAgainst`, makes modules take turns extracting their baseline.
  That avoids concurrent `git worktree add`/`prune` on one repo and N nested
  daemons at once. Extract and compile tasks stay fully parallel. The plugin is
  tested under `--parallel`, the configuration cache, and Gradle Isolated
  Projects (build services are the IP-safe way to share state across projects).

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

**Wired (#88):** the history is a source-controlled directory the consumer
commits, not an artifact repository, and the periodic drift audit (re-extract a
tag, compare to what was recorded) is not implemented. `serialkompatRecord` writes `<version>.snapshot` into a
source-controlled `history { dir }` (default `serialkompat/history/`), keyed by
version, atomically and append-only (refuses to overwrite). Each entry carries an
`@history version=… recordedAt=…` header — a block key `SnapshotFormat` never
emits, so it can't collide with schema content — and load validates every entry,
failing closed on a torn/corrupt one rather than under-reporting. Entries load in
**semver** order (not lexicographic, so `1.9.0` < `1.10.0`).
`serialkompatCheckHistory` runs `TransitiveCompatibility` over the history and is
wired into `check`, but no-ops until a version is recorded. Recording is decoupled
from Maven publishing (#24): a consumer can record + commit manually or from any
release step. `serialkompatRecord` is **not** wired into `check`; it records
under `-Pserialkompat.recordVersion=<X.Y.Z>` (else the project `version`), rejects
an unset or whitespace-bearing version, and refuses to record a snapshot with
zero checked (non-`OPAQUE`) contracts, since an append-only entry can't be
corrected later. The history check writes its own
`build/serialkompat/report-history.{json,sarif}` so it never clobbers the
pairwise `report.json` (#122).

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

**Updated: what shipped.** There is no "explicit config in the extension" step.
`SchemaExtractionMain` loads the `jsonInstance` FQN if set; otherwise (or if it
can't be loaded) it uses kotlinx's default `Json`, which is already the strict
profile (`ignoreUnknownKeys=false`, `explicitNulls=true`, …). The "assuming
default config" warning is logged only when a configured `jsonInstance` fails to
load; leaving `jsonInstance` unset is silent.

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

As shipped, config changes are classified per direction (the rule IDs and exact
verdicts are in `docs/rules.md`): `namingStrategy` → `CONFIG_NAMING_STRATEGY`
and `classDiscriminator` / `classDiscriminatorMode` → `CONFIG_DISCRIMINATOR`
(BREAK both ways); disabling `ignoreUnknownKeys` or `useAlternativeNames` →
`CONFIG_READER_STRICTNESS` and disabling `coerceInputValues` →
`CONFIG_COERCE_INPUT` (backward WARN only); disabling `encodeDefaults` →
`CONFIG_ENCODE_DEFAULTS` (forward WARN only); any `explicitNulls` toggle →
`CONFIG_EXPLICIT_NULLS` (WARN both ways); anything else → `CONFIG_CHANGED` (WARN).
Loosening a reader-side flag is SAFE.

**Honest limit:** reading *your* `Json` config describes the sides you own (Kotlin
producers/consumers). A non-Kotlin client's tolerance is not in there, so for
externally-facing scopes you can pin a stricter assumption
(`readerTolerance = STRICT`) to override "what my own Json does." Multiple wire
boundaries → map scope → `Json` instance.

**Updated: not surfaced yet.** `ReaderTolerance.STRICT` exists in `-core`
(`CompatibilityProfile.readerTolerance`), but neither the Gradle extension nor
the CLI exposes it; both build `CompatibilityProfile(direction = …)` only, so the
reader's own `ignoreUnknownKeys` is always used. There is one `jsonInstance` per
module; per-scope `Json` mapping is not implemented. A multi-boundary project
splits boundaries into modules today.

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

**Updated: knobs as shipped.** Direction is one value per module
(`serialkompat { direction }`, default `FULL`); per-type overrides are not
implemented. Reader tolerance always comes from the reader's `Json` config (the
`STRICT` override is `-core`-only, see §6). The fail floor is fixed at `BREAK`:
`failOnBreaking` (default `true`) toggles whether active BREAK findings fail the
build, and WARN findings are reported but never fail. A finding that is SAFE in a
direction produces no finding at all.

### The matrix

`B` = backward (new code reads old data). `F` = forward (old code reads new data).
Under `FULL` + **strict reader**:

**Updated (2026-10-06):** cells below are corrected to the shipped
`Classifier`. The original draft used `WARN→BREAK` to mean "conditional"; under a
strict reader those cells are plain **BREAK**, and the "What flips it" column
says what downgrades them. The authoritative per-rule table, with rule IDs, is
`docs/rules.md`.

| Change | B (new◄old) | F (old◄new) | What flips it |
|---|:---:|:---:|---|
| Add field **with default** (optional) | SAFE | **BREAK** | old reader `ignoreUnknownKeys`→SAFE forward |
| Add field **no default / `@Required`** | **BREAK** | **BREAK** | backward = `MissingFieldException`; nullable + new reader `explicitNulls=false`→**SAFE**³; forward as above |
| Remove **optional** field | **BREAK** | SAFE¹ | new reader `ignoreUnknownKeys`→**WARN** (silent data-loss) backward² |
| Remove **required** field | **BREAK** | **BREAK** | backward as above; forward = old code needs it; nullable + old reader `explicitNulls=false`→**WARN**³ |
| **Rename** key (no `@JsonNames`) | **BREAK** | **BREAK** | decomposed into remove + add; tolerant readers → backward **WARN** (silent data-loss), forward SAFE² |
| Rename **with `@JsonNames(old)`** | **BREAK** (designed: SAFE) | **BREAK** | **Not implemented:** elements pair by key only, so an alias-bridged rename is still scored as a plain rename (remove + add). Conservative: never a false SAFE |
| optional → **required** | **BREAK** | SAFE | backward = old payloads omit it |
| required → **optional** | SAFE | **BREAK** | forward per field (#158/#176): `@EncodeDefault(ALWAYS)`→SAFE; `NEVER`→BREAK; no annotation→SAFE iff writer `encodeDefaults`; mode unknown→WARN under `encodeDefaults=true`, else BREAK |
| non-null → **nullable** (`T`→`T?`) | SAFE | **BREAK** | forward: old reader chokes on emitted `null`; new writer `explicitNulls=false`→**WARN** |
| nullable → **non-null** (`T?`→`T`) | **BREAK** | SAFE | backward: old `null` can't decode |
| Change type (`String`↔`Int`, restructure) | **BREAK** | **BREAK** | numeric widening (`Byte`/`Short`/`Int`→wider int, `Float→Double`): B SAFE / F BREAK. A change where either side bears a generic hole is not a finding (§14) |
| Drop a `@JsonNames` alias | **WARN** | SAFE | `PROPERTY_JSON_NAMES`; adding an alias is SAFE |
| Enum **add** value | SAFE | **BREAK** | forward WARN iff old reader `coerceInputValues` AND every reading field has a default (#129) |
| Enum **remove** value | **BREAK** | SAFE | |
| Enum/subtype **rename** (serial name) | **BREAK** | **BREAK** | discriminator/name mismatch |
| Polymorphic **add** subtype | SAFE | **BREAK** | forward WARN (coerced to the sentinel) iff the base — open **or sealed** — registered a default deserializer **and** the old reader has `ignoreUnknownKeys` (#128, #181); a strict reader still throws on the new subtype's fields |
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

**Updated (#57): what shipped.** The `@JsonNames` mitigation below is not
implemented (see the matrix). The exceptions *file* and the inline annotation
were not built either. Accepted breaks are declared in the build script instead:
`serialkompat { acceptedBreaks.set(listOf("<serialName> <RULE> [DIRECTION]")) }`
(omit the direction to accept both). The CLI has no equivalent. A matching
finding is downgraded to *acknowledged*: listed with an `[acknowledged]` tag,
never failing. An unlisted break fails, but the console report does not print a
ready-to-paste stanza. The review property still holds: the build-script diff is
the precise breakage the PR sanctions.

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
fix hint` (add a default / add `@JsonNames` / bump major / add exception).
Emitted by pure `serialkompat-core` reporters as **console**, **versioned machine
JSON** (top-level `schemaVersion`, documented and stable — see the "Report formats"
docs page), **SARIF 2.1.0** (logical locations only), and **GitHub Actions
annotations**; posted as a PR comment on CI (§9).

As shipped (#122): the Gradle check always logs the console form and writes the
formats enabled in `serialkompat { reports { json { } sarif { } } }` (JSON on by
default at `build/serialkompat/report.json`, SARIF off by default at
`report.sarif`). The CLI picks one with `--format=console|json|sarif|github`.
The GitHub Action renders annotations itself from `report.json`.

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

**Updated (2026-10-06):** neither `@PreviousSerialName` nor the rename-detection
heuristic has shipped; the `renames` map (Gradle extension only, not the CLI) is
the only way to declare a move. There is no `TYPE_MOVED` rule: a declared plain
move produces **no finding** (SAFE findings are never emitted), only the diff of
its contents. An undeclared rename of a contract type surfaces as
`CONTRACT_REMOVED` (BREAK) for the old name; the new name is a SAFE add.

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
  // or, with types empty: discovery.set(DiscoveryMode.OPT_OUT)  // EXPLICIT (default) | OPT_OUT | OPT_IN (§4)
  jsonInstance.set("com.mercury.wire.WireJson.instance")  // real Json config — read, not re-declared
  direction.set(CompatibilityDirection.FULL)
  baselineRef.set("origin/main")                          // recomputed live; unset = auto-detect default branch (#116)
  failOnBreaking.set(true)
  failOnEmptyBaseline.set(true)                           // false only for first-time adoption (§5)
  // Scope by serial-name prefix (exclude wins); a module that never crosses the wire can be left out.
  include.set(listOf(""))                                 // default: everything
  exclude.set(listOf("com.mercury.internal."))
  // Escape hatches:
  acceptedBreaks.set(listOf("com.mercury.wire.OrderEvent PROPERTY_REMOVED"))  // "<serialName> <RULE> [DIRECTION]"
  renames.set(mapOf("com.mercury.old.Name" to "com.mercury.new.Name"))        // old → new serial name
  history { dir.set(layout.projectDirectory.dir("serialkompat/history")) }   // + sinceVersion / depth / maxAge (§5)
  reports { sarif { required.set(true) } }                                   // json on, sarif off by default (§7)
}
```

The heading says KMP, but the example is a plain JVM module. A KMP module works
the same way provided it declares a `jvm()` target (§4); the plugin then reads
`jvmRuntimeClasspath` and the jvm target's class dirs. Because the plugin is
published to Maven Central only (no Plugin Portal yet), consumers need
`mavenCentral()` in `pluginManagement { repositories { } }`.

### Tasks

| Task | Does | Wired to |
|---|---|---|
| `serialkompatCheck` | extract current → resolve baseline → diff → classify → report; non-zero exit on unlisted breaks | **`check` lifecycle** |
| `serialkompatCheckAgainst -Pserialkompat.ref=<ref>` | same, against an arbitrary ref (falls back to the configured/auto-detected baseline) | ad-hoc / local; the GitHub Action's default task |
| `serialkompatExtract` | emit current schema to `build/serialkompat/current.snapshot`; build-cacheable (§5) | dependency of the above |
| `serialkompatRecord` | append the current schema to the published history (`-Pserialkompat.recordVersion=<X.Y.Z>`) | release step; **not** in `check` |
| `serialkompatCheckHistory` | transitive check vs every retained history entry | **`check` lifecycle**; no-op until a version is recorded |

No "commit the baseline" task in the v0 gate path (that was the staleness trap).

**Updated:** `serialkompatRecord` and `serialkompatCheckHistory` landed with #88.
The ad-hoc ref property is `-Pserialkompat.ref`, not `-Pref`. Every task is gated
by `onlyIf`: with `discovery = EXPLICIT` and no `types`, applying the plugin is a
no-op, so `check` never fails on an unconfigured module. All tasks are
configuration-cache safe (state is captured at configuration time; nothing
touches `Task.project` at execution).

### Two loops
- **Local (zero maintenance):** `./gradlew serialkompatCheck` compares the working
  tree vs `baselineRef` (else the auto-detected default branch), fast after the
  first cached baseline. Optional pre-push hook.
- **CI gate:** same task; baseline = the PR's target branch. Unlisted break → fail;
  post findings + schema diff as a **sticky PR comment / job summary**.

### Coverage invariant (no silent exclusions)
Every in-scope `@Serializable` type is either checked or *explicitly, visibly*
suppressed; anything that is neither **fails the gate**. Suppressions are listed in
the report. A model cannot silently fall out of the gate.

**Updated: how strong this is today.** Suppression is always explicit config
(`include`/`exclude`, `@SerialkompatIgnore`, or not listing a type under
`EXPLICIT`), but the excluded set is not printed in any report (`Coverage.excluded`
is computed in `-core` and then dropped by `CompatibilityEngine`). An
unanalysable type is surfaced as a `COVERAGE_GAP` **WARN**, which is reported on
every run but does not fail the gate (§10). "Fails the gate" is therefore not
yet true for coverage gaps.

### Packaging
- **`serialkompat-gradle`** — primary interface (KMP compiles through Gradle anyway).
- **`serialkompat` GitHub Action** — one *unified* action (buf's lesson: don't
  fragment) running the task and posting the PR comment. The Gradle task stays
  CI-agnostic (emits JSON report + exit code); the Action does GitHub-specific
  posting. It also emits capped inline `error`/`warning` annotations (the first 10 of each, plus
  a `notice` summarizing any overflow) from the same report, and — because findings are
  logical-only (no source `file:line`) — does **not** upload SARIF to GitHub code
  scanning.
- **`serialkompat-cli`** — shipped, for non-Gradle / cross-repo use:
  `serialkompat diff <baseline> <current> [--direction=BACKWARD|FORWARD|FULL]
  [--format=console|json|sarif|github] [--no-fail]`. It diffs two snapshot files
  (no extraction, no `acceptedBreaks`/`renames`/scope). Exit codes: `0` ok, `1`
  breaking, `2` usage error.

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

**Updated: what shipped.**

- `failOnUnanalyzable` was never added. A coverage gap is always a `COVERAGE_GAP`
  **WARN** in both directions: reported on every run, never failing the gate.
  It can be acknowledged through `acceptedBreaks` like any other finding.
- What becomes `OPAQUE`: an unknown `SerialKind`, an unresolved `@Contextual`
  serializer (#131), a type that fails to load or resolve (each root resolves
  independently, #81), an unreadable class file during the scan (#55), and a
  generic sealed/polymorphic hierarchy (#139).
- Custom serializers carry no "shape derived from custom serializer" marker. The
  extractor records whatever the custom descriptor exposes. For example, a
  `@Serializable(with = …)` class whose descriptor is `PrimitiveKind.STRING` is
  recorded as a string-typed field, not as its constructor fields. A custom
  serializer whose descriptor under-describes its real output is therefore the
  remaining fidelity ceiling, and it is not flagged.

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
| **kotlinx version matrix** | run across supported kotlinx-serialization versions. **Updated:** not a CI matrix today. CI runs on JDK 17 and 21; the tool builds against the kotlinx-serialization in `libs.versions.toml`, and one TestKit test extracts a consumer on Kotlin 2.3.21 / kotlinx-serialization 1.9.0 to prove the project-runtime-first classpath (#180). |

Those verdicts are surfaced for readers on the [Rules](../rules.md) page: a
per-rule **Rule reference** section shows the change as a red/green diff, the
good/bad wire outcome (with the real exception), and a link to the oracle test
that proves it. A `checkRulesProof` build gate (sibling to `checkRulesDoc`)
fails CI if a cited test is renamed or removed, and — once every rule has a
section — if a `Rules.*` constant ships without one.

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

**Updated (2026-10-06): status.** v0, v1 and the post-v1 milestones are
implemented, with these exceptions still open: the v0.5 rename-detection
heuristic and `@PreviousSerialName` (§8), the exceptions file (§7, replaced by
`acceptedBreaks`), `@JsonNames` rename mitigation (§7), per-scope reader
tolerance / `Json` mapping (§6), and Gradle Plugin Portal publishing. Shipped
beyond the original roadmap: discovery modes + `serialkompat-annotations`
(#115), generic-root envelopes (#139), JSON/SARIF/GitHub reporters and the
`reports { }` DSL (#122), history retention (#121), `@EncodeDefault` recovery
(#158/#176), sealed default deserializers (#181), a cacheable extract task
(#182), and multi-module/Isolated Projects support (#183).

---

## 14. Residual risks to validate in the plan
- `@EncodeDefault` mode is **not on the descriptor** — it is not a `@SerialInfo`
  annotation, so it never appears in `getElementAnnotations` (#7). It *is*
  RUNTIME-retained, though: Kotlin puts property-targeted annotations on a synthetic
  static `get<Name>$annotations()` method, so the extractor (`EncodeDefaultReader`,
  #158) resolves the model class via the descriptor's plugin-generated serializer
  and reads it there, matching properties by `@SerialName` or JVM getter naming
  (superclasses included). It records `ALWAYS` / `NEVER` / `ABSENT` for optional
  elements on positive evidence only (if any `@EncodeDefault` holder goes unmatched —
  e.g. renamed by `@get:JvmName` — no field in that class is proven `ABSENT`);
  anything unresolved (hand-written
  serializers, unmatched names, reflection failure, or any pre-#158 snapshot) is
  `null` = unknown. `PROPERTY_OPTIONALITY` forward (became optional) uses it:
  `NEVER` → BREAK, `ALWAYS` → SAFE, `ABSENT` → follows `encodeDefaults`, unknown →
  WARN under `encodeDefaults=true` (a hidden `NEVER` must never read as SAFE).
  This relies on kotlinx internals (`PluginGeneratedSerialDescriptor`'s private
  `generatedSerializer` field); if they change, extraction degrades to unknown
  (WARN), never to a false SAFE.
- A field's **default *value*** is likewise **not recoverable** via Approach A —
  the descriptor exposes `isElementOptional` (that a default exists) but never the
  value itself (it lives in the generated `deserialize`). So the enum coerce-fallback
  fidelity (#129) keys on optionality + *how* the enum is referenced (a defaulted
  direct property can coerce an added value to its default → WARN; a required field,
  a `List`/`Map` usage, or a top-level decode has no default and throws → BREAK),
  not on a recorded sentinel value. A compiler-plugin extractor could record the
  actual default (and a designated `UNKNOWN` sentinel) for a tighter verdict;
  that producer is retired (§3), so this stays a known limit.
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
- Generic/parameterized `@Serializable` roots: envelope (non-type-parameter)
  fields are checked via hole resolution (#139); **per-instantiation** shape
  (`BaseResponse<User>` vs `BaseResponse<Order>` — type args are erased in type
  refs) and **generic sealed/polymorphic** hierarchies remain gaps. The classifier
  suppresses an `ElementTypeChanged` whenever *either* side of the change bears a
  hole, which is broader than just container/arity flips: **any** concrete-argument
  change beside a hole is also suppressed this cut, including a real break such as
  `Map<String,#0>` → `Map<Int,#0>` (a map-key type change, not flagged) alongside
  the narrower container/arity case (`List<#0>` → `Set<#0>`). A hole-normalized
  structural compare — diffing only the concrete positions of a hole-bearing type
  string rather than suppressing the whole change — is a possible follow-up to
  tighten this.
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
   suppression; no-silent-exclusions coverage invariant. **Updated (#115):** the
   default is `discovery = EXPLICIT` (only listed `types`); check-by-default is
   opt-in via `OPT_OUT`. Suppression is by module (don't apply the plugin),
   serial-name prefix (`include`/`exclude`), or type (`@SerialkompatIgnore`);
   there is no file-level suppression.
6. **Config:** read from the real `Json` instance; config is part of the snapshot;
   config changes are classified; strict override for third-party-facing scopes.
7. **Identity:** match by `serialName`; `@PreviousSerialName`/`renames` to track
   moves; plain-type moves SAFE, polymorphic discriminator renames BREAK.
8. **Name:** `serialkompat` (`com.chrisjenx.serialkompat`).
