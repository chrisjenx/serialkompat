# Contributing to serialkompat

Thanks for your interest! This project is built **in the open, test-first, one reviewed PR at a time**. This guide explains how we work.

## Ground rules

- Be kind. See the [Code of Conduct](https://github.com/chrisjenx/serialkompat/blob/main/CODE_OF_CONDUCT.md).
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
./gradlew build            # compile + test + spotlessCheck + apiCheck + docs gates + docs-sample tests
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

The `Docs` workflow runs on any PR touching `docs/**`, `docs-samples/**`, `mkdocs.yml`, `requirements-docs.txt`, `main.py` (the version macro and redirect stubs), `CHANGELOG.md`, `CONTRIBUTING.md`, `scripts/check_docs_site.py` or `.github/workflows/docs.yml`. It runs `dokkaGenerate`, then `mkdocs build --strict`, then `scripts/check_docs_site.py site` (redirect stubs and anchors). Broken links, broken nav, a page missing from the nav, or a broken redirect fail the PR.

Where the docs come from, and the build gates that keep them in step with the code (all run by `./gradlew build`):

- **`docs-samples/`** holds the Kotlin models and the `snippets/*.gradle.kts` blocks the pages embed with `--8<--`. `build` compiles and tests it, and `DocsSnippetsFunctionalTest` applies each snippet in a TestKit build (`help` only, so it checks DSL names and types, not values such as `acceptedBreaks` strings). Edit the sample, not a copy in the page.
- **`docs/snippets/reports/*`** are rendered by the real reporters in `DocsReportSamplesTest` (`serialkompat-core`), which fails if they drift. Regenerate them with `./gradlew :serialkompat-core:test -Pserialkompat.updateDocs=true`.
- **`checkDocsReference`** fails if a DSL property or block, a task, a `-Pserialkompat.*` property or a CLI flag is undocumented. It wants the exact backticked name: DSL members in `docs/configuration.md` (nested ones as dotted paths, such as `history.dir` or `reports.json.required`), tasks as `` `serialkompatCheck` ``, and properties and flags as `` `-Pserialkompat.ref=<ref>` `` or `` `--format` `` in `docs/tasks-cli.md`.
- **`checkRulesDoc`** fails if any rule the classifier ships (a `Rules.*` constant) is missing from `docs/rules.md`.
- **`checkRulesProof`** fails if a rule has no `` ### `RULE_ID` `` section under the page's [Rule reference](https://chrisjenx.github.io/serialkompat/rules/#rule-reference), if a section has no **Proof** line citing a test, or if a cited test isn't declared in the file its link points at. Wrap each test name in backticks in the link text. The allowed files are the JSON and CBOR round-trip oracles plus a short list of unit tests (`proofTestPaths` in the root `build.gradle.kts`).

## Before you push

Run the full local gate. CI runs the same `build`, plus the secret scan and (for docs changes) the strict docs build:

```console
./gradlew spotlessApply && ./gradlew build
```

- **Formatting** is enforced by Spotless + ktlint. `spotlessApply` fixes most issues.
- **Public API stability** is enforced by [binary-compatibility-validator](https://github.com/Kotlin/binary-compatibility-validator). If you intentionally change a module's public API, run `./gradlew apiDump` and commit the updated `*.api` file. The diff is part of your PR review. Run `apiDump` and `apiCheck` as separate Gradle invocations; `./gradlew apiDump apiCheck` fails with a validation error.
- **Docs** are gated too (`checkRulesDoc`, `checkRulesProof`, `checkDocsReference`; see [Docs site](#docs-site)). Add the rule's row and section when you add a rule, and document a new option, task or flag.
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
- Expect review focused on correctness, test quality, and adherence to the [design](https://github.com/chrisjenx/serialkompat/tree/main/docs/design).

## Project layout

| Module | Responsibility |
|---|---|
| `serialkompat-core` | Pure-Kotlin model, differ, classifier, rules, report. No I/O. |
| `serialkompat-extractor` | Runtime `SerialDescriptor` extraction (JVM). |
| `serialkompat-gradle` | The Gradle plugin. |
| `serialkompat-cli` | Standalone `serialkompat diff <baseline> <current>` CLI. Not published; no tracked API. |
| `serialkompat-annotations` | `@SerialkompatIgnore` / `@SerialkompatChecked` discovery markers (Kotlin Multiplatform). |

The authoritative design lives in [`docs/design`](https://github.com/chrisjenx/serialkompat/tree/main/docs/design). If a change deviates from the design, say so in the PR and we'll update the design together.

## Licensing of contributions

By contributing, you agree that your contributions are licensed under the [Apache License 2.0](https://github.com/chrisjenx/serialkompat/blob/main/LICENSE), the same license as the project.
