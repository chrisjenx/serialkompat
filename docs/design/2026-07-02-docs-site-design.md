# Docs site design

**Date:** 2026-07-02 · **Status:** approved · **Owner:** @chrisjenx

A documentation site for serialkompat on GitHub Pages: concise, human, visual, and provably in sync
with the code. Replaces the current Dokka-only Pages deployment with a full site; Dokka nests under
`/api/`.

## Goals

- A reader can answer "what is this / is it for me?" in 30 seconds and be running a check in 5 minutes.
- Every code sample compiles in CI. The rule matrix is generated from the shipped rule set. Docs
  cannot silently rot.
- Terse, information-dense prose. No marketing waffle, no AI filler. Visual where a diagram beats a
  paragraph.

## Stack & layout

- **MkDocs Material**, config in `mkdocs.yml` at repo root, content in `docs/`.
- Python tooling appears in CI only (pinned `requirements-docs.txt`); contributors write Markdown.
- Dokka HTML is assembled into the published artifact at `/api/` — one Pages deployment, one URL.
- `docs/design/` stays the engineering source of truth. Excluded from the built site
  (`exclude_docs`); the deep dive links to it on GitHub.

## Pages

| Nav | Content |
|---|---|
| Home | One-liner, **animated pipeline**, is / is-not table, three links: quick start · rules · API. |
| Quick start | Apply plugin → run `serialkompatCheck` → read a real report. Under 5 minutes. |
| Setup | Gradle plugin, CLI, GitHub Action — tabbed. Install snippets show the live version. |
| CI setup | GitHub Actions via `chrisjenx/serialkompat@v1` composite action + manual-Gradle tab; GitLab CI example; exit-code contract for any CI. |
| Configuration | Extension DSL: direction, `Json` config, roots, failure modes. One table + one annotated snippet. |
| Rules | The **generated** breaking-change matrix + direction/config semantics. The centerpiece. |
| Recipes | Cross-repo CLI diff, persisted-data (append-only history), monorepo. |
| Deep dive | 3–4 short pages: architecture, extraction, classification + round-trip oracle, git-ref baseline model. Rewritten for humans — the spec stays a spec. |
| API | Dokka at `/api/`. |
| Changelog | `CHANGELOG.md` included verbatim (snippet include, not a copy). |

## Visuals

- **Animated pipeline** on Home: `@Serializable → Extractor → Snapshot → Differ → Classifier →
  Report` as inline SVG with CSS animation. Self-contained (no JS libs), honors
  `prefers-reduced-motion`, degrades to the static diagram.
- **Mermaid** for static diagrams: architecture, git-ref baseline flow, classification flow.
- Material admonitions, content tabs, annotated code blocks, dark-mode-aware palette + logo.

## Sync gates

1. **`docs-samples` module** — **DEFERRED** (follow-up, not in PR ③). Converting every doc snippet
   to embed from a compiled, unpublished Gradle module is a sizeable architectural change with high
   doc churn; it warrants its own focused, human-reviewed PR. The matrix-completeness gate (#4 below)
   and version templating (#3) already give strong anti-rot coverage, so shipping them first — and the
   samples module later — is the pragmatic order. Original intent retained for the follow-up: every
   Kotlin sample embedded from its sources via `pymdownx.snippets` region markers (`--8<-- [start:name]`),
   the module compiling and testing under `./gradlew build`, and Gradle-config snippets exercised by
   `serialkompat-gradle` functional tests.
2. **Strict docs build on PRs** — CI job runs `mkdocs build --strict` on PRs touching `docs/`,
   `mkdocs.yml`, or samples. Broken links, missing pages, bad nav fail the PR, not the deploy.
   *(Shipped in PR ①.)*
3. **Version templating** — `mkdocs-macros-plugin`; the version is read from `gradle.properties`
   (or an injected `SERIALKOMPAT_VERSION`) into the build via a `skversion` macro, so install snippets
   never show a stale version. Local builds fall back to `dev`. *(Shipped in PR ③.)*
4. **Rule-matrix completeness gate** — a root Gradle task `checkRulesDoc`, wired into `check`, reads
   every `Rules.*` constant from `serialkompat-core`'s `Finding.kt` and fails the build if any is
   absent from `docs/rules.md`. This is the pragmatic form of the "generated matrix" idea: the full
   verdict matrix is authored by hand (the verdicts live imperatively in `Classifier.kt`, not as a
   declarative table), but no rule can be silently added to the code without being documented.
   Full auto-generation of the verdict cells would require a declarative rule catalog in core and is a
   possible future enhancement. *(Shipped in PR ③.)*

## CI / workflow changes

- `docs.yml`: build Dokka + `mkdocs build`, assemble `site/` + `site/api/`, deploy Pages. Same
  permissions/concurrency as today.
- New PR-triggered strict-build job (path-filtered). The matrix verify and samples compile ride the
  existing `./gradlew build` gate — no new required checks for code-only PRs.

## Non-goals (YAGNI)

Versioned docs (`mike`) until post-1.0 · custom domain · blog · i18n · screenshots that rot
(prefer embedded real report output from tests).

## Delivery

1. **PR ①** — site skeleton: MkDocs Material theme, nav stubs, Pages assembly with Dokka, strict
   build job.
2. **PR ②** — content: all pages, animated pipeline, Mermaid diagrams.
3. **PR ③** — sync gates: version macro + `checkRulesDoc` matrix-completeness gate. (The
   `docs-samples` compiled-snippets module is deferred to a follow-up — see Sync gates §1.)
