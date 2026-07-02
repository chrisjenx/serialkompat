# Docs Site Skeleton (PR ①, #104) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** MkDocs Material site skeleton on the existing GitHub Pages deployment — nav + stub pages, Dokka nested at `/api/`, `mkdocs build --strict` gating PRs.

**Architecture:** `mkdocs.yml` at repo root, content in `docs/` (design docs excluded from the built site). The `Docs` workflow builds MkDocs + Dokka into one Pages artifact; a PR-triggered job runs the strict build only. Content (#105) and sync gates (#106) come later — this PR ships structure.

**Tech Stack:** MkDocs Material 9.7.6 (Python, CI-only), Dokka 2.0 (already wired), GitHub Pages via `actions/deploy-pages`.

## Global Constraints

- Work on branch `docs/site-design` (already holds the design spec commit); PR closes #104.
- Commit trailer: `Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>`.
- Push via SSH remote (the `gh` token lacks the `workflow` scope needed for `.github/workflows/` changes).
- No Python tooling required of contributors; pin everything in `requirements-docs.txt`.
- Stub pages get one terse real sentence + a pointer to #105 — no lorem, no "TBD" walls.
- Design docs (`docs/design/`) must NOT appear in the built site.
- Do not add a `plugins:` key to `mkdocs.yml` (would disable default `search`; macros lands in #106).

---

### Task 1: MkDocs toolchain, config, and stub pages

**Files:**
- Create: `requirements-docs.txt`
- Create: `mkdocs.yml`
- Create: `docs/index.md`, `docs/quickstart.md`, `docs/setup.md`, `docs/ci.md`, `docs/configuration.md`, `docs/rules.md`, `docs/recipes.md`, `docs/deep-dive.md`, `docs/changelog.md`
- Modify: `.gitignore` (ignore `site/`)

**Interfaces:**
- Produces: `mkdocs build --strict` as the docs test command (venv at `build/docs-venv/`); page filenames above are the nav contract #105 fills in.

- [ ] **Step 1: Pin the toolchain and create the venv**

Create `requirements-docs.txt`:

```text
mkdocs-material==9.7.6
```

Run:

```bash
python3 -m venv build/docs-venv && build/docs-venv/bin/pip install -r requirements-docs.txt
```

Expected: install succeeds (`build/` is already gitignored).

- [ ] **Step 2: Write `mkdocs.yml` (the failing test — nav references pages that don't exist yet)**

```yaml
site_name: serialkompat
site_description: A backward/forward compatibility gate for kotlinx-serialization @Serializable models
site_url: https://chrisjenx.github.io/serialkompat/
repo_url: https://github.com/chrisjenx/serialkompat
repo_name: chrisjenx/serialkompat
edit_uri: edit/main/docs/

# Engineering specs stay on GitHub, not in the site.
exclude_docs: |
  design/

theme:
  name: material
  palette:
    - media: "(prefers-color-scheme: light)"
      scheme: default
      primary: indigo
      toggle:
        icon: material/brightness-7
        name: Switch to dark mode
    - media: "(prefers-color-scheme: dark)"
      scheme: slate
      primary: indigo
      toggle:
        icon: material/brightness-4
        name: Switch to light mode
  features:
    - navigation.sections
    - navigation.footer
    - content.code.copy
    - content.code.annotate
    - content.tabs.link
    - search.suggest

markdown_extensions:
  - admonition
  - attr_list
  - md_in_html
  - tables
  - pymdownx.details
  - pymdownx.superfences:
      custom_fences:
        - name: mermaid
          class: mermaid
          format: !!python/name:pymdownx.superfences.fence_code_format
  - pymdownx.tabbed:
      alternate_style: true
  - pymdownx.snippets:
      check_paths: true
      base_path: ["."]

nav:
  - Home: index.md
  - Quick start: quickstart.md
  - Setup: setup.md
  - CI setup: ci.md
  - Configuration: configuration.md
  - Rules: rules.md
  - Recipes: recipes.md
  - Deep dive: deep-dive.md
  - API: https://chrisjenx.github.io/serialkompat/api/
  - Changelog: changelog.md
```

- [ ] **Step 3: Run the strict build — verify it fails for the right reason**

```bash
build/docs-venv/bin/mkdocs build --strict
```

Expected: FAIL — `A reference to 'index.md' is included in the 'nav' configuration, which is not found in the documentation files` (aborted with `--strict`).

- [ ] **Step 4: Create the stub pages**

`docs/index.md`:

```markdown
# serialkompat

**A backward/forward compatibility gate for [kotlinx-serialization](https://github.com/Kotlin/kotlinx.serialization) `@Serializable` models — like [`buf breaking`](https://buf.build/docs/breaking/), but for JSON.**

It reads the JSON wire schema out of your compiled models, diffs it against a baseline from a git ref, and fails CI on incompatible changes.

[Quick start](quickstart.md){ .md-button .md-button--primary } [Rules](rules.md){ .md-button } [API](https://chrisjenx.github.io/serialkompat/api/){ .md-button }

!!! note
    Full content — animated pipeline, is/is-not table — lands with [#105](https://github.com/chrisjenx/serialkompat/issues/105).
```

Each remaining page follows this exact shape (H1, one real sentence, the #105 note). Sentences:

| Page | H1 | Sentence |
|---|---|---|
| `quickstart.md` | Quick start | Apply the plugin, run `serialkompatCheck`, read a report — under five minutes. |
| `setup.md` | Setup | Install serialkompat as a Gradle plugin, a CLI, or a GitHub Action. |
| `ci.md` | CI setup | Wire the compatibility gate into GitHub Actions, GitLab CI, or any runner via the exit-code contract. |
| `configuration.md` | Configuration | The `serialkompat { }` extension: direction, `Json` config, roots, and failure modes. |
| `rules.md` | Rules | What counts as breaking, by direction and reader config — generated from the shipped rule set. |
| `recipes.md` | Recipes | Cross-repo diffs with the CLI, persisted-data checks, and monorepo layouts. |
| `deep-dive.md` | Deep dive | How extraction, classification, and the git-ref baseline model actually work. |
| `changelog.md` | Changelog | *(no sentence — Task 2 fills this file; create it with just `# Changelog` for now)* |

- [ ] **Step 5: Run the strict build — verify it passes**

```bash
build/docs-venv/bin/mkdocs build --strict
```

Expected: PASS — `Documentation built in …`.

- [ ] **Step 6: Ignore the output dir and commit**

Append to `.gitignore` under the Gradle block:

```text
# MkDocs output
site/
```

```bash
git add requirements-docs.txt mkdocs.yml docs/*.md .gitignore
git commit -m "docs(site): MkDocs Material skeleton — nav, stub pages, strict build (#104)

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

### Task 2: Changelog include + design-doc exclusion, verified

**Files:**
- Modify: `docs/changelog.md`

**Interfaces:**
- Consumes: `pymdownx.snippets` with `check_paths: true`, `base_path: ["."]` (Task 1).
- Produces: `docs/changelog.md` renders the repo-root `CHANGELOG.md` verbatim — #105 must not copy changelog content anywhere else.

- [ ] **Step 1: Verify the exclusion already holds (regression check on Task 1's config)**

```bash
build/docs-venv/bin/mkdocs build --strict && ls site/design 2>&1
```

Expected: build PASS, then `ls: site/design: No such file or directory`. If `site/design/` exists, `exclude_docs` is wrong — fix before continuing.

- [ ] **Step 2: Write the failing check — changelog page has no release content yet**

```bash
grep -c "0.1.0" site/changelog/index.html
```

Expected: `0` (FAIL state — the page is an empty stub).

- [ ] **Step 3: Make `docs/changelog.md` include the real changelog**

```markdown
# Changelog

--8<-- "CHANGELOG.md"
```

- [ ] **Step 4: Rebuild and verify the include rendered**

```bash
build/docs-venv/bin/mkdocs build --strict && grep -c "0.1.0" site/changelog/index.html
```

Expected: build PASS; grep prints a count ≥ 1. (A typo'd include path would fail the strict build via `check_paths` — that's the sync gate working.)

- [ ] **Step 5: Commit**

```bash
git add docs/changelog.md
git commit -m "docs(site): include CHANGELOG.md verbatim on the changelog page (#104)

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

### Task 3: Pages workflow — build both, assemble one artifact, strict-check PRs

**Files:**
- Modify: `.github/workflows/docs.yml` (full rewrite below)
- Modify: `CONTRIBUTING.md` (local docs-build instructions)

**Interfaces:**
- Consumes: `./gradlew dokkaGenerate` → `build/dokka/html` (existing); `mkdocs build` → `site/`.
- Produces: published site layout `/{page}` + `/api/` — #105/#106 link against these paths.

- [ ] **Step 1: Rehearse the assembly locally (the workflow's core commands)**

```bash
./gradlew dokkaGenerate --stacktrace \
  && build/docs-venv/bin/mkdocs build --strict \
  && mkdir -p site/api && cp -R build/dokka/html/. site/api/ \
  && ls site/index.html site/api/index.html
```

Expected: both `index.html` paths listed.

- [ ] **Step 2: Rewrite `.github/workflows/docs.yml`**

```yaml
name: Docs

on:
  push:
    branches: [main]
  pull_request:
    paths:
      - "docs/**"
      - "mkdocs.yml"
      - "requirements-docs.txt"
      - ".github/workflows/docs.yml"
  workflow_dispatch:

permissions:
  contents: read

concurrency:
  group: docs-${{ github.ref }}
  # Don't cancel an in-flight main deploy; superseded PR builds are safe to cancel.
  cancel-in-progress: ${{ github.event_name == 'pull_request' }}

jobs:
  # PRs: strict build is the gate. Pushes to main: also produces the deploy artifact.
  build:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v7
      - uses: actions/setup-java@v5
        with:
          distribution: temurin
          java-version: "17"
      - uses: gradle/actions/setup-gradle@v6
      - uses: actions/setup-python@v6
        with:
          python-version: "3.12"
          cache: pip
      - run: pip install -r requirements-docs.txt
      - name: Generate API docs
        run: ./gradlew dokkaGenerate --stacktrace
      - name: Build site (strict)
        run: mkdocs build --strict
      - name: Nest API docs under /api
        run: mkdir -p site/api && cp -R build/dokka/html/. site/api/
      - uses: actions/upload-pages-artifact@v5
        with:
          path: site

  publish:
    if: github.event_name != 'pull_request'
    needs: build
    runs-on: ubuntu-latest
    permissions:
      pages: write
      id-token: write
    environment:
      name: github-pages
      url: ${{ steps.deploy.outputs.page_url }}
    steps:
      # enablement:true lets the workflow turn on GitHub Pages (Actions source) itself.
      - uses: actions/configure-pages@v5
        with:
          enablement: true
      - id: deploy
        uses: actions/deploy-pages@v5
```

- [ ] **Step 3: Document the local docs build in `CONTRIBUTING.md`**

Insert after the "Development setup" code block:

````markdown
### Docs site

The docs site ([chrisjenx.github.io/serialkompat](https://chrisjenx.github.io/serialkompat/)) is MkDocs Material; content lives in `docs/`. To preview locally:

```console
python3 -m venv build/docs-venv && build/docs-venv/bin/pip install -r requirements-docs.txt
build/docs-venv/bin/mkdocs serve   # live-reload at http://127.0.0.1:8000
```

CI runs `mkdocs build --strict` on any PR touching docs — broken links or nav fail the PR.
````

- [ ] **Step 4: Commit and push (SSH — workflow file changed)**

```bash
git add .github/workflows/docs.yml CONTRIBUTING.md
git commit -m "ci(docs): build MkDocs + Dokka into one Pages artifact; strict-check PRs (#104)

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
git push -u origin docs/site-design
```

Expected: push accepted (SSH remote has workflow scope).

---

### Task 4: Open the PR and verify the gate fires

**Files:** none (GitHub state only).

**Interfaces:**
- Consumes: branch `docs/site-design` (spec + Tasks 1–3 commits).

- [ ] **Step 1: Open the PR**

```bash
gh pr create --title "docs(site): MkDocs Material skeleton + Pages assembly + strict CI" --body "Closes #104 (epic #107). Design: docs/design/2026-07-02-docs-site-design.md

- MkDocs Material skeleton: nav + terse stub pages; design docs excluded from the built site
- Changelog page includes CHANGELOG.md verbatim (snippets, \`check_paths\`)
- Docs workflow builds MkDocs + Dokka into one Pages artifact (site root + \`/api/\`); PRs touching docs get \`mkdocs build --strict\` as a gate
- Note: on merge, the Pages root changes from raw Dokka to the site; Dokka moves to \`/api/\`

🤖 Generated with [Claude Code](https://claude.com/claude-code)"
```

- [ ] **Step 2: Verify the Docs check runs on the PR and is green**

```bash
gh pr checks --watch
```

Expected: `Docs / build` appears (path filter matched) and passes, alongside the usual CI/Secret Scan checks.

- [ ] **Step 3: Confirm no publish happened from the PR**

```bash
gh run list --workflow=Docs --limit 1 --json databaseId --jq '.[0].databaseId' | xargs -I{} gh run view {} --json jobs --jq '.jobs[] | "\(.name): \(.conclusion // .status)"'
```

Expected: `build: success` and `publish: skipped`.

**Merge is a user decision** — report green and stop. After merge, verify the deployed site: root serves the skeleton, `/api/` serves Dokka.
