# Changelog

All notable changes to this project are documented here.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

### Fixed
- CLI never crashes on bad input (design §10). A missing/unreadable/malformed snapshot file, an unknown flag, or an invalid `--direction` value now produce a controlled exit code 2 with an `error:` message instead of an uncaught exception + stack trace. Added `--help`/`-h`. Exit codes: 0 ok, 1 breaking, 2 usage/input error.
- `@JvmInline value class`es are now unwrapped to their underlying wire type during extraction. A serializable inline class serializes as its single underlying value, so a field typed `UserId(val raw: Int)` is recorded as `kotlin.Int`. Previously the wrapper's serial name was recorded, so swapping a raw primitive for a wire-identical value class (or back) produced a spurious `ElementTypeChanged` and a **false `BREAK`**. Value classes are transparent — never emitted as their own contracts — but a value class wrapping a `@Serializable` type still walks that type.

### Added
- Producer-agnostic `@Serializable` discovery: when no `types` are configured, the extractor reads FQNs from a classpath manifest (`META-INF/serialkompat/serializable-types.txt`, one per line, `#` comments and blanks ignored). The manifest may be authored by hand or emitted by a build-time step. An automated producer, if built, is a Kotlin compiler plugin — **not KSP** (issue #22).
- Persisted-data horizon: `TransitiveCompatibility.checkAgainstHistory` verifies the current schema against *every* published version (Confluent `*_TRANSITIVE` semantics), catching a break vs an old version even when the latest looks fine; `PublishedHistory` is an append-only per-version snapshot store (refuses overwrites).
- Dokka 2.0 multi-module API docs (`dokkaGenerate` aggregates core/extractor/gradle) published to GitHub Pages via a `Docs` workflow on `main`.
- `serialkompat-cli` module: a standalone CLI (`serialkompat diff <baseline> <current>`) for non-Gradle / cross-repo use that diffs two snapshot files through the same engine + report, with `--direction` and `--no-fail`. Exit codes: 0 ok, 1 breaking, 2 usage.
- Unified GitHub composite action (`action.yml`): runs the gate and posts a sticky PR comment built from the JSON report (icon + breaking/warning/acknowledged summary + per-finding detail), then fails the job on incompatible changes. The Gradle task stays CI-agnostic; the action does the GitHub-specific posting.
- `serialkompatCheckAgainst` task: check against an ad-hoc ref via `-Pserialkompat.ref=<ref>` without editing config, falling back to the configured `baselineRef`.
- Gradle plugin wired end-to-end: `serialkompatExtract` runs the extractor on the target project's runtime classpath (JavaExec) and dumps the current schema; `serialkompatCheck` recomputes the baseline from `baselineRef` (git-ref-live) and fails on unacknowledged breaks. `serialkompat { … }` config extension (types, jsonInstance, baselineRef, direction, failOnBreaking, include/exclude). Proven by a TestKit functional test against a real Kotlin project; applying the plugin without configuring `types` is a safe no-op.
- `CompatibilityEngine`: the single end-to-end entry point tying the pure pipeline together — restrict to a `Scope`, diff, classify under a `CompatibilityProfile`, and fold declared renames and accepted breaks into a `Report`. Every front end (Gradle task, CLI) shares this one implementation.
- git-ref-live baseline: `GitRefBaseline` recomputes the target ref's schema from source in a throwaway detached worktree (no committed baseline to go stale), with a content-addressed `SnapshotCache` keyed by commit SHA and fail-closed ref resolution. `GitCommands`/`SystemGit` seam is unit-tested with a fake and a real-git integration test.
- `Scope` + `Coverage` + `Snapshot.applyScope`: restrict checking by serial-name prefix (include/exclude, exclude wins) so a module that never crosses the wire can be left out — with the excluded set enumerable, keeping the no-silent-exclusions invariant honest.
- Graceful degradation: `ContractKind.OPAQUE` + a crash-proof extractor — an unknown `SerialKind`, unresolved contextual/custom serializer, or extraction failure becomes an explicit opaque coverage gap (recorded, round-trippable, diffable), never an exception or a silent omission (design §10).
- Rename/move tracking: the differ takes a `renames` map (old→new serial name) and follows a declared move as a `ContractMoved` — diffing contents instead of a spurious remove+add. The classifier scores a plain move safe and a polymorphic move a discriminator BREAK. Stale renames can't silently drop a contract.
- Round-trip oracle test harness: cross-checks the rule matrix against real kotlinx-serialization by encoding with one schema version and decoding with the other, asserting the classifier is **sound** (never misses a real break). Corpus covers add/remove field, nullability narrowing, type change, numeric widening, and enum-value add.
- `Report` + `ConsoleReporter` + `JsonReporter` + `AcceptedBreak`: pure rendering of findings to console text and machine JSON, an accepted-breaks model that downgrades sanctioned findings to *acknowledged*, and a `shouldFail(floor)` gate decision.
- Config-change classification: the classifier now judges `Json` config deltas (design §6) — naming-strategy / discriminator changes are BREAK, tightening `ignoreUnknownKeys` / disabling `encodeDefaults` / `coerceInputValues` are WARN, loosening is safe.
- `JsonConfigReader`: reads the wire-relevant settings off a user's real `Json.configuration` (naming strategy, discriminator, ignoreUnknownKeys, encodeDefaults, explicitNulls, coerceInputValues) into a `SnapshotConfig` — binding to the actual config rather than a redeclared copy.
- `Classifier` + `Finding` + `Rules` + `CompatibilityProfile`: applies the full rule matrix (design §7), turning structural `Change`s into per-direction, severity-ranked findings using the reader/writer `Json` config (`ignoreUnknownKeys`, `encodeDefaults`, `coerceInputValues`) and numeric-widening awareness. Reports only actionable (WARN/BREAK) findings.
- `SnapshotExtractor` + `DescriptorSnapshotExtractor`: vendored runtime `SerialDescriptor` walk (BFS + visited-set) reading serial names, per-element optionality, nullability, `@JsonNames`, enum values, sealed subtypes, and `SerializersModule`-resolved open polymorphism into a `Snapshot`.
- `SnapshotDiffer` + `Change` taxonomy: pure structural, direction-neutral diff of two snapshots into typed changes (contract/element add·remove, type/optionality/nullability, enum & subtype add·remove, discriminator, config). Deterministic output; field reordering yields no change.
- `Snapshot` model (`Contract`, `Element`, `Subtype`, `SnapshotConfig`, `ContractKind`, `EncodeDefaultMode`) — the canonical, order-normalized representation of a JSON wire contract.
- `SnapshotFormat`: deterministic, byte-stable, sorted-by-serial-name text codec that round-trips losslessly (`parse(serialize(s)) == s`) and is invariant to field ordering.
- Project scaffold: `serialkompat-core`, `serialkompat-extractor`, `serialkompat-gradle` modules.
- Runtime descriptor reference extractor (reads element names and optionality from a `SerialDescriptor`).
- `SerialkompatPlugin` registering `serialkompatCheck` and wiring it into the `check` lifecycle.
- Tooling: Spotless (ktlint), Kover, binary-compatibility-validator, CI.
- Design document ([`docs/design`](docs/design)).

[Unreleased]: https://github.com/chrisjenx/serialkompat/commits/main
