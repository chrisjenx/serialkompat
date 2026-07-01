# Changelog

All notable changes to this project are documented here.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

### Added
- Project scaffold: `serialkompat-core`, `serialkompat-extractor`, `serialkompat-gradle` modules.
- Runtime descriptor reference extractor (reads element names and optionality from a `SerialDescriptor`).
- `SerialkompatPlugin` registering `serialkompatCheck` and wiring it into the `check` lifecycle.
- Tooling: Spotless (ktlint), Kover, binary-compatibility-validator, CI.
- Design document ([`docs/superpowers/specs`](docs/superpowers/specs)).

[Unreleased]: https://github.com/chrisjenx/serialkompat/commits/main
