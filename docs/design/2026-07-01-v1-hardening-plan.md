# v1 hardening plan — pre-merge audit of the PR stack (#35–#54)

**Date:** 2026-07-01
**Status:** the v0+v0.5+v1 roadmap is fully coded as a linear stack of 20 CI-green PRs
(#35→#54, tip `feat/22-ksp`), none merged to `main`. Before that stack lands, each PR
was reviewed against its issue's acceptance criteria, the design doc, and the CLAUDE.md
golden rules by an independent per-PR reviewer (12 correctness-critical PRs).

**Headline: CI-green ≠ v1-ready.** The reviews found genuine correctness gaps — including
*false negatives* (the gate silently passing real wire breaks), which defeat the tool's
entire purpose — plus a Gradle config-cache defect that stops the gate from running at
all for consumers. The KSP mis-implementation (#22, reverted) was the first symptom;
these are the rest.

Every finding below was checked against the **tip** (`feat/22-ksp` = what v1 ships), since
several PRs were reviewed at their own (older) commit and later PRs changed things.

---

## Tier 1 — Correctness: the gate silently passes a real break (BLOCKER)

These violate the core guarantee. **All confirmed present on the tip.**

| # | Finding | Source | Fix location |
|---|---------|--------|--------------|
| 1 | **OPAQUE is classified SAFE.** An unanalysable type (custom serializer) recorded as `OPAQUE` produces no finding: `ContractAdded(OPAQUE)`→SAFE, and OPAQUE↔OPAQUE→no diff. A `@Serializable(with=…)` whose wire shape changes passes green. Violates the "unanalysable ≠ safe" golden rule. | #45/#18 | `Classifier` must emit ≥WARN coverage-gap for any OPAQUE presence/change; needs a `failOnUnanalyzable` floor (design §10). Cross-module (core). |
| 2 | **`classDiscriminatorMode` unread/unmodelled.** Flipping it to `NONE` drops the discriminator key from the wire → polymorphic decode breaks → invisible to the gate. | #40/#8 | Add to `SnapshotConfig` + `JsonConfigReader` + `SnapshotDiffer.diffConfig` + `Classifier` (BREAK for polymorphic). |
| 3 | **Enum-add forward marked SAFE on `coerceInputValues` alone.** Real kotlinx only coerces an unknown enum constant when the field *also* has a default. A non-defaulted enum field throws → gate passes a break. | #39/#11 | `Classifier`: stay BREAK unless provably safe (can't see per-field default from the change). |
| 4 | **Rename with both endpoints present drops the diff.** A `renames` entry `{A→B}` where both `A` and `B` still exist skips *both* from the normal loop, so a real change to still-present `B` is never diffed. | #44/#12 | `SnapshotDiffer`: tighten filter to genuine moves — `n !in oldByName && o !in newByName`. One line + test. |
| 5 | **Corrupt/truncated baseline cache → fail-open.** `SnapshotCache.get` returns any on-disk bytes untrusted; `put` is non-atomic. A partial file (killed CI job) becomes a trusted baseline with fewer contracts → removed fields look absent → missed break. Issue #16's "corrupt cache ⇒ refuse to run" is unimplemented. | #47/#16 | `SnapshotCache`: validate on read (parse/checksum) or treat as miss; write atomically (temp+rename). |

## Tier 2 — Functional: the gate doesn't run / has no escape hatch (BLOCKER)

| # | Finding | Source | Fix location |
|---|---------|--------|--------------|
| 6 | **Config-cache violation in `serialkompatCheck`/`serialkompatCheckAgainst`.** `doLast { runCheck(target, …) }` captures `Project` and calls `project.*` at execution time. The repo enforces `configuration-cache=true` on Gradle 9 → the first real run fails before checking anything. CI never exercises it (only `serialkompatExtract`, a config-cache-safe `JavaExec`, is run end-to-end). | #48/#17 | Move to a typed task / `ValueSource`; capture only serializable inputs. Add a `--configuration-cache` functional test. |
| 7 | **`renames` + `acceptedBreaks` inert from Gradle.** The engine folds both (design §7/§8) but `SerialkompatExtension` exposes neither → users can't acknowledge a rename or sanction a break except by disabling the whole gate (`failOnBreaking=false`). Defeats the "acknowledge, don't block forever" workflow. | #48/#17 | Add `renames: MapProperty` + `acceptedBreaks` to the extension; thread to `CheckExecutor`→`CompatibilityEngine`. |

## Tier 3 — Trust: false positives (over-flagging) — safe, but erode confidence

| # | Finding | Source | Fix location |
|---|---------|--------|--------------|
| 8 | **Config classification is direction-blind.** `ignoreUnknownKeys`, `encodeDefaults`, `coerceInputValues`, `explicitNulls` are each inherently one-directional but scored symmetric backward=forward. All errors are over-flags (never under-flag), so no safety hole — but spurious WARNs. | #41/#13 | `Classifier.configVerdict`: return per-direction severities. |
| 9 | **`non-null → nullable` forward hard-coded BREAK**, ignoring `explicitNulls=false` (nulls omitted → old reader fine → change is actually safe). Blocks a safe change. | #39/#11 | `Classifier`: factor `newConfig.explicitNulls`. |
| 10 | **Canonical-text codec is lossy on delimiter chars** (`,` space `]` ` -> `). A `serialName`/enum value/`jsonNames`/type-ref containing one round-trips to a *different* model. Low-probability for typical FQNs, but silent. | #35/#5 | `SnapshotFormat`/`Contract`/`Element`: validate-or-escape; enforce a charset. |

## Tier 4 — The oracle (golden-rule violation underpinning Tier 1)

| # | Finding | Source | Fix location |
|---|---------|--------|--------------|
| 11 | **The round-trip oracle backs only ~5 of 18 classifier rules** (config, polymorphic, discriminator, contract families entirely unbacked); PR #11 shipped **zero** oracle tests while claiming "oracle-backed, every matrix row"; the #10 harness asserts soundness only (an all-BREAK classifier would pass), never checks a SAFE verdict actually decodes, and can't see silent data-loss (only thrown/not-thrown). This is *why* findings #3, #9 slipped through. | #43/#10, #39/#11 | Extend the oracle harness to cover the full §7 matrix in both directions, assert SAFE-decodes-clean, add a value-preservation (data-loss) outcome, apply the declared `Json` config per case. |

## Tier 5 — Diffing/model gaps + test debt + docs

| # | Finding | Source |
|---|---------|--------|
| 12 | `@JsonNames` alias changes produce no `Change` (silent-pass on §7's own rename mitigation); `useAlternativeNames` unread. Ties to #12 rename work. | #36/#9, #40/#8 |
| 13 | WARN tier is unreachable — classifier only emits SAFE/BREAK, so the `failOn` floor is inert. Either implement WARN or amend design §7 (deviation must be documented per CLAUDE.md). | #39/#11 |
| 14 | `@EncodeDefault` mode changes not diffed/classified — but moot for the runtime extractor (§14: mode is never recoverable, always null). Relevant only to a future compiler-plugin extractor. | #36/#9, #39/#11 |
| 15 | Rule-name scheme (`PROPERTY_REMOVED`…) diverges from design §7's names (`PROPERTY_NO_DELETE`…) — these are the public, suppressible exception-file keys. Reconcile or update design. | #39/#11 |
| 16 | Cache key is SHA-only; design §5 mandates `(SHA + tool version + config hash)`. Stale hit after a tool upgrade / config change → wrong baseline. | #47/#16 |
| 17 | Baseline TOCTOU (resolve ref, then check out ref not SHA) + leftover-worktree blocks re-runs (fails closed, no self-heal). | #47/#16 |
| 18 | Transitive: `load()` orders lexicographically (`1.10.0` < `1.9.0`); dedup omits severity so "worst-case wins" holds only by accident of classifier shape; release-CI publishing + drift audit (§5/#21) not wired. | #53/#21 |
| 19 | Test gaps: negative-path/malformed-input + OBJECT/POLYMORPHIC round-trip (#35); custom-serializer→OPAQUE + move-is-not-a-free-pass (breaking change) + stale-rename-drops-nothing (#44/#45); Gradle check-fails-build functional test (#48); config-flip oracle cases (#41). | multiple |
| 20 | CHANGELOG/design overclaims to reconcile (config-change classification wording #39; "number/format normalization" is vacuous #35). | #35, #39 |

## What the reviews confirmed is **correct** (no rework)

- The classifier's directional SAFE↔BREAK skeleton and reader/writer-config-per-direction routing (the classic trap) — right.
- Differ identity/determinism/direction-neutrality; contract-kind change → remove+add; rename deferral to #12.
- Snapshot equality completeness + order-invariance; the 6 config fields that *are* read map correctly.
- Extractor descriptor-fact fidelity; `@EncodeDefault` correctly left null + documented; never-throws (recording OPAQUE); cycle handling.
- git baseline **fail-closed** invariant holds (no fail-open path); worktree doesn't pollute the user tree.
- Transitive fold covers every published version (break-vs-old-version caught + tested); append-only store refuses overwrites (tested).
- Engine is the single shared entry point; a break is never swallowed; unconfigured = safe no-op.

---

## Remediation phasing (TDD, each phase its own reviewable change)

1. **Oracle first (Tier 4).** Build the full-matrix round-trip harness. It is the tool that *proves* the Tier 1/3 severity fixes and stops regressions. RED before any classifier change.
2. **Tier 1 false-negatives.** OPAQUE-not-safe (+`failOnUnanalyzable`), `classDiscriminatorMode`, enum-add conservative, rename both-endpoints filter, cache validation. Each driven by an oracle/round-trip test.
3. **Tier 2 functional.** Config-cache-safe task + `--configuration-cache` functional test; expose `renames`/`acceptedBreaks` in the extension.
4. **Tier 3 trust.** Direction-aware config classification; `explicitNulls` in nullability; codec charset.
5. **Tier 5 cleanup.** `@JsonNames` diff, WARN tier decision (implement or document), rule-name reconcile, cache key, transitive ordering/dedup, remaining test gaps, doc reconciliation.

## Strategic constraints (need a maintainer decision)

- **Stack structure.** Fixing findings on their *origin* PR branch means cascade-rebasing up to 13 downstream branches per fix — impractical. Recommended: treat the tip `feat/22-ksp` as the v1 integration branch and apply all hardening there, landing v1 as a hardened whole rather than 20 incremental merges.
- **Merge is externally blocked.** The agent lacks `Bash(gh pr merge:*)`, so "v1 ready = merged to `main`" cannot be reached autonomously — the maintainer must grant merge permission or merge. Independent of the code fixes above.
