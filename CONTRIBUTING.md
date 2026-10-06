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

Don't open a PR whose production code isn't driven by tests.

For the rule engine, correctness is checked against **real kotlinx-serialization** with the round-trip oracle: serialize with the old model, decode with the new one, and assert the classifier predicted the actual outcome. A rule without an oracle-backed test is not done.

## Development setup

Requires **JDK 17+**. Everything runs through the Gradle wrapper — no local Gradle needed.

```console
./gradlew build            # compile + test + spotlessCheck + apiCheck + rules-doc gates
./gradlew test             # tests only
./gradlew spotlessApply    # auto-format (run before committing)
./gradlew koverHtmlReport  # coverage report -> build/reports/kover
```

### Docs site

The docs site ([chrisjenx.github.io/serialkompat](https://chrisjenx.github.io/serialkompat/)) is MkDocs Material; content lives in `docs/`. To preview locally:

```console
python3 -m venv build/docs-venv && build/docs-venv/bin/pip install -r requirements-docs.txt
build/docs-venv/bin/mkdocs serve   # live-reload at http://127.0.0.1:8000
```

The `Docs` workflow runs `mkdocs build --strict` on any PR touching `docs/**`, `mkdocs.yml`, or `requirements-docs.txt`. Broken links, broken nav, or a page missing from the nav fail the PR.

## Before you push

Run the full local gate. CI runs the same `build`, plus the secret scan and (for docs changes) the strict docs build:

```console
./gradlew spotlessApply && ./gradlew build
```

- **Formatting** is enforced by Spotless + ktlint. `spotlessApply` fixes most issues.
- **Public API stability** is enforced by [binary-compatibility-validator](https://github.com/Kotlin/binary-compatibility-validator). If you intentionally change a module's public API, run `./gradlew apiDump` and commit the updated `*.api` file. The diff is part of your PR review. Run `apiDump` and `apiCheck` as separate Gradle invocations; `./gradlew apiDump apiCheck` fails with a validation error.
- **Rules docs** are gated too. `checkRulesDoc` fails if a `Rules.*` constant has no row in `docs/rules.md`, and `checkRulesProof` fails if a proof link there cites a missing oracle test. Add the docs row when you add a rule.
- **`serialkompat-annotations`** is Kotlin Multiplatform with Apple targets, which only build on macOS. On Linux they are skipped silently, so a green Linux build doesn't prove that module; CI builds it on a separate macOS job.
- **Coverage** is reported by Kover.
- **Secrets** are scanned by [gitleaks](https://github.com/gitleaks/gitleaks). CI runs it over the full history (`Secret Scan` workflow). To catch a secret before it is ever committed, enable the pre-commit hook once: `pip install pre-commit && pre-commit install`. Never commit credentials or signing keys; publishing secrets live only in CI secrets.
- **The Gradle wrapper** is upgraded only with `./gradlew wrapper --gradle-version <x>`, never by editing `gradle-wrapper.properties` or swapping the jar. A partial bump leaves `gradlew`/`gradlew.bat` on the old version. The `Update Gradle Wrapper` workflow opens wrapper PRs; Dependabot is configured to skip them.

## Code style

- Kotlin official style (`.editorconfig` + ktlint, version pinned in `gradle/libs.versions.toml`). 4-space indent, 120 col.
- Library modules (`-core`, `-extractor`, `-annotations`) use `explicitApi()`. Declare visibility and public return types explicitly.
- Prefer small, single-purpose types with clear interfaces (see the design doc's isolation principles).
- Public declarations get KDoc.

## Commit messages

Use clear, imperative subject lines (e.g. `Add DISCRIMINATOR_COLLISION rule`). Reference the issue: `Fixes #12`. Conventional-commit prefixes (`feat:`, `fix:`, `docs:`, `test:`, `chore:`) are welcome but not required.

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
| `serialkompat-cli` | Standalone `serialkompat diff <baseline> <current>` CLI. Not published; no tracked API. |
| `serialkompat-annotations` | `@SerialkompatIgnore` / `@SerialkompatChecked` discovery markers (Kotlin Multiplatform). |

The authoritative design lives in [`docs/design`](docs/design). If a change deviates from the design, say so in the PR and we'll update the design together.

## Licensing of contributions

By contributing, you agree that your contributions are licensed under the [Apache License 2.0](LICENSE), the same license as the project.
