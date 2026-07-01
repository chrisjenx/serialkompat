# Changelog

All notable changes to this project are documented here.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

### Added
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
