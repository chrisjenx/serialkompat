# Contributing to serialkompat

Thanks for your interest! This project is built **in the open, test-first, one reviewed PR at a time**. This guide explains how we work.

## Ground rules

- Be kind. See the [Code of Conduct](CODE_OF_CONDUCT.md).
- Every change lands via a pull request against `main`, with CI green.
- One logical change per PR. Small, reviewable PRs merge faster.
- Most work is tracked in [issues](https://github.com/chrisjenx/serialkompat/issues). If you want to work on something, comment on the issue (or open one) first so we don't duplicate effort.

## Test-Driven Development (required)

We develop RED → GREEN → REFACTOR:

1. **RED** — write a failing test that captures the desired behaviour. Run it, watch it fail for the *right* reason.
2. **GREEN** — write the minimum code to make it pass.
3. **REFACTOR** — clean up with the tests as your safety net.

Do not open a PR whose production code isn't driven by tests. For the rule engine specifically, correctness is verified against **real kotlinx-serialization** via the round-trip oracle (serialize with the old model, decode with the new one, assert the classifier predicted the actual outcome) — a rule without an oracle-backed test is not done.

## Development setup

Requires **JDK 17+**. Everything runs through the Gradle wrapper — no local Gradle needed.

```console
./gradlew build            # compile + test + spotlessCheck + apiCheck
./gradlew test             # tests only
./gradlew spotlessApply    # auto-format (run before committing)
./gradlew koverHtmlReport  # coverage report -> build/reports/kover
```

## Before you push

Run the full local gate — CI runs the same thing:

```console
./gradlew spotlessApply && ./gradlew build
```

- **Formatting** is enforced by Spotless + ktlint. `spotlessApply` fixes most issues.
- **Public API stability** is enforced by [binary-compatibility-validator](https://github.com/Kotlin/binary-compatibility-validator). If you intentionally change a module's public API, run `./gradlew apiDump` and commit the updated `*.api` file — the diff is part of your PR review.
- **Coverage** is reported by Kover.

## Code style

- Kotlin official style (`.editorconfig` + ktlint). 4-space indent, 120 col.
- Library modules (`-core`, `-extractor`) use `explicitApi()` — declare visibility and public return types explicitly.
- Prefer small, single-purpose types with clear interfaces (see the design doc's isolation principles).
- Public declarations get KDoc.

## Commit messages

Use clear, imperative subject lines (e.g. `Add PROPERTY_NO_DELETE rule`). Reference the issue: `Fixes #12`. Conventional-commit prefixes (`feat:`, `fix:`, `docs:`, `test:`, `chore:`) are welcome but not required.

## Pull requests

- Fill in the PR template.
- Link the issue the PR closes.
- Ensure CI is green and the branch is up to date with `main`.
- Expect review focused on correctness, test quality, and adherence to the [design](docs/design).

## Project layout

| Module | Responsibility |
|---|---|
| `serialkompat-core` | Pure-Kotlin model, differ, classifier, rules, report. No I/O. |
| `serialkompat-extractor` | Runtime `SerialDescriptor` extraction (JVM). |
| `serialkompat-gradle` | The Gradle plugin. |

The authoritative design lives in [`docs/design`](docs/design). If a change deviates from the design, say so in the PR and we'll update the design together.

## Licensing of contributions

By contributing, you agree that your contributions are licensed under the [Apache License 2.0](LICENSE), the same license as the project.
