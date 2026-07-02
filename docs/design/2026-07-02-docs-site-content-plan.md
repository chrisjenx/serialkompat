# Docs Site Content (PR ②, #105) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development. Steps use checkbox (`- [ ]`) syntax.

**Goal:** Fill the skeleton with terse, accurate, visual content — every page written from code-verified facts, an animated pipeline on Home, Mermaid diagrams in the deep dive.

**Architecture:** Content-only PR on branch `docs/site-content` (stacked on `docs/site-design`/PR #108). Pages already exist as stubs; this replaces stub bodies. Facts come from `.superpowers/sdd/ground-truth.md` (code-verified) — implementers MUST read it and MUST NOT invent API. Verification per task: `mkdocs build --strict` passes + content assertions; a final task validates rendered visuals with a headless browser.

**Tech Stack:** MkDocs Material 9.7.6, pymdownx (snippets/superfences/tabbed), Mermaid (Material built-in via the superfences custom fence already configured), one CSS file for the animated pipeline.

## Global Constraints

- Branch `docs/site-content`. Commit trailer: `Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>`.
- **Voice: terse, information-dense. No marketing waffle, no AI filler ("Furthermore", "It's worth noting", "In today's world").** Lead with what the reader gets. Prefer tables and annotated code over paragraphs.
- **Accuracy: every API/flag/rule/coordinate must match `.superpowers/sdd/ground-truth.md`.** If the reference marks something "not yet implemented", either omit it or clearly label it roadmap. License is **Apache 2.0**.
- Keep lines ≤115 chars (repo ktlint/markdown convention).
- Verify each task with `build/docs-venv/bin/mkdocs build --strict` (venv from PR ①). No `plugins:` key added to `mkdocs.yml`. Never `git add -A` — explicit paths only (`.superpowers/` scratch must not be committed).
- The version string shown in install snippets stays literal `0.1.0-SNAPSHOT` in this PR; templating is PR ③ (#106).
- Every internal link must resolve (`--strict` enforces). Cross-page links use relative `.md` paths.

---

### Task 1: Home page + animated pipeline

**Files:**
- Modify: `docs/index.md`
- Create: `docs/stylesheets/pipeline.css`
- Modify: `mkdocs.yml` (add `extra_css`)

**Interfaces:**
- Produces: `.sk-pipeline` CSS component (self-contained, no JS); the is/is-not table; the value proposition. Later tasks link here.

- [ ] **Step 1: Read the reference.** Read `.superpowers/sdd/ground-truth.md` (esp. §Architecture) and the current `README.md` (for voice/claims already vetted). The Home page distills the README's "Why" + "How it works" into a scannable landing page — do not exceed what the README claims.

- [ ] **Step 2: Write `docs/stylesheets/pipeline.css`** — an animated, horizontally-scrolling-safe pipeline. Requirements:
  - Six stages: `@Serializable` → Extractor → Snapshot → Differ → Classifier → Report.
  - Flexbox row, wraps on narrow screens; each stage a rounded box; connectors between them.
  - A pulse/flow animation (e.g. a dot or gradient traveling left→right, or staged fade-in) using `@keyframes`.
  - **MUST honor `@media (prefers-reduced-motion: reduce)`** — disable animation, show the static pipeline.
  - Dark-mode aware: use Material CSS vars (`var(--md-primary-fg-color)`, `var(--md-default-fg-color)`, `var(--md-default-bg-color)`) so it works in both palettes. No hardcoded hex that breaks dark mode.
  - No external assets, no JS.

- [ ] **Step 3: Add the stylesheet to `mkdocs.yml`.** Insert a top-level `extra_css:` key (not inside `theme:`):
```yaml
extra_css:
  - stylesheets/pipeline.css
```

- [ ] **Step 4: Rewrite `docs/index.md`.** Required elements, in order:
  1. H1 `serialkompat` + the one-line positioning (from README: "backward/forward compatibility gate … like `buf breaking`, but for JSON").
  2. The animated pipeline: a `<div class="sk-pipeline">…</div>` block (use `md_in_html`; the six labeled stages as child elements matching the CSS).
  3. One-sentence gloss of each stage OR a caption line (terse).
  4. **is / is-not table** — two columns, honest scoping. "Is": a CI gate for JSON wire/persisted-schema evolution of `@Serializable` models; config- and direction-aware; grounded in real kotlinx-serialization behavior. "Is not": a runtime validator, a migration tool, a replacement for versioning, or a general JSON-schema linter.
  5. Button row: `[Quick start](quickstart.md)`, `[Rules](rules.md)`, `[API](https://chrisjenx.github.io/serialkompat/api/)` using `{ .md-button }` (first `--primary`).
  6. A short status admonition: early development, `0.1.0-SNAPSHOT`, not yet on Maven Central / Plugin Portal.
  Remove the `#105` note stub.

- [ ] **Step 5: Build strict + assert.**
```bash
build/docs-venv/bin/mkdocs build --strict \
  && grep -q "sk-pipeline" site/index.html \
  && grep -qi "is not" site/index.html \
  && test -f site/stylesheets/pipeline.css \
  && grep -q "prefers-reduced-motion" site/stylesheets/pipeline.css
```
Expected: build PASS and all greps succeed (exit 0).

- [ ] **Step 6: Commit.**
```bash
git add docs/index.md docs/stylesheets/pipeline.css mkdocs.yml
git commit -m "docs(site): home page — animated pipeline, is/is-not table (#105)

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

### Task 2: Quick start + Setup

**Files:**
- Modify: `docs/quickstart.md`, `docs/setup.md`

**Interfaces:**
- Consumes: ground-truth §1 (plugin/tasks/DSL), §5 (CLI), §6 (Action), §8 (coordinates).
- Produces: the canonical install snippets other pages link to.

- [ ] **Step 1: Read the reference** (`.superpowers/sdd/ground-truth.md` §1, §4, §5, §6, §8).

- [ ] **Step 2: Rewrite `docs/quickstart.md`** — under-5-minutes path, Gradle plugin only (the common case):
  1. Apply the plugin (real block from §1, minimal: `plugins { … id("com.chrisjenx.serialkompat") }` + a minimal `serialkompat { types.set(listOf("com.example.Order")) }`). Note plugin resolution caveat: not yet on the Plugin Portal — until then resolve via Maven Central / `pluginManagement` (state this honestly; do not fabricate a `plugins {}` coordinate that doesn't resolve).
  2. Run `./gradlew serialkompatCheck`.
  3. Read a real report — embed the console output block from §4 verbatim in a fenced block, annotate the BREAK line (what rule/contract/direction/fix mean).
  4. One line each: exit codes 0/1/2; where the JSON report lands.

- [ ] **Step 3: Rewrite `docs/setup.md`** — three install targets as `=== "Gradle plugin"` / `=== "CLI"` / `=== "GitHub Action"` content tabs (pymdownx.tabbed, already enabled):
  - **Gradle plugin:** full `serialkompat { }` block with the whole DSL table from §1 rendered as a table, each property + default + purpose.
  - **CLI:** the `serialkompat diff …` syntax from §5, flags table, exit codes, and how to produce snapshots (`serialkompatExtract`).
  - **GitHub Action:** the consumer snippet from §6, inputs/outputs table, the `pull-requests: write` + `fetch-depth: 0` requirements. Link onward to the CI page.

- [ ] **Step 4: Build strict + assert.**
```bash
build/docs-venv/bin/mkdocs build --strict \
  && grep -q "serialkompatCheck" site/quickstart/index.html \
  && grep -q "com.chrisjenx.serialkompat" site/setup/index.html \
  && grep -q "serialkompat diff" site/setup/index.html
```
Expected: PASS + greps exit 0.

- [ ] **Step 5: Commit.**
```bash
git add docs/quickstart.md docs/setup.md
git commit -m "docs(site): quick start + setup (plugin / CLI / Action tabs) (#105)

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

### Task 3: CI setup + Configuration

**Files:**
- Modify: `docs/ci.md`, `docs/configuration.md`

**Interfaces:**
- Consumes: ground-truth §1 (DSL, tasks, `-Pserialkompat.ref`), §6 (Action).

- [ ] **Step 1: Read the reference** (§1, §6).

- [ ] **Step 2: Rewrite `docs/ci.md`:**
  1. **GitHub Actions** (recommended) — the `chrisjenx/serialkompat@v1` composite snippet from §6, explain the sticky PR comment (❌/⚠️/✅), the `permissions` and `fetch-depth: 0` requirements, and `ref`/`task` inputs.
  2. **Manual Gradle** tab/section — for any CI: `./gradlew serialkompatCheck` (or `serialkompatCheckAgainst -Pserialkompat.ref=origin/main`), and the exit-code contract (0 pass / 1 breaking / 2 usage) as the universal integration point.
  3. **GitLab CI** example — a `.gitlab-ci.yml` job running the Gradle task, keyed off the exit code. Keep it minimal and correct.
  4. Note: the baseline is recomputed live from the ref via a git worktree, so CI needs full history (`fetch-depth: 0`).

- [ ] **Step 3: Rewrite `docs/configuration.md`:**
  1. The full `serialkompat { }` DSL table (§1) — property, type, default, purpose. (May duplicate Setup's table intentionally; this page is the reference.)
  2. One fully-annotated `build.gradle.kts` block (code annotations `# (1)!` style) explaining direction, `jsonInstance`, `acceptedBreaks` format (`"<serialName> <RULE> [DIRECTION]"`), `renames`, `failOnEmptyBaseline` (first-adoption note).
  3. A short "choosing a direction" subsection mapping BACKWARD/FORWARD/FULL to real scenarios (rolling deploys, persisted data, public APIs) — from §2.

- [ ] **Step 4: Build strict + assert.**
```bash
build/docs-venv/bin/mkdocs build --strict \
  && grep -q "chrisjenx/serialkompat@v1" site/ci/index.html \
  && grep -q "acceptedBreaks" site/configuration/index.html \
  && grep -qi "gitlab" site/ci/index.html
```
Expected: PASS + greps exit 0.

- [ ] **Step 5: Commit.**
```bash
git add docs/ci.md docs/configuration.md
git commit -m "docs(site): CI setup (Actions/Gradle/GitLab) + configuration reference (#105)

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

### Task 4: Rules page (the centerpiece matrix)

**Files:**
- Modify: `docs/rules.md`

**Interfaces:**
- Consumes: ground-truth §3 (rules + severity), §7 (config awareness).
- Produces: the hand-written matrix that PR ③ (#106) will later replace with a generated one — so structure it as a single clear table PR ③ can regenerate.

- [ ] **Step 1: Read the reference** (§3, §7, §2).

- [ ] **Step 2: Rewrite `docs/rules.md`:**
  1. Lead: severity legend — `SAFE` / `WARN` (config-dependent or silent semantic break) / `BREAK` (decode fails). Use Material admonitions or a small table.
  2. Direction primer (2 lines): BACKWARD = new reads old; FORWARD = old reads new; FULL = both.
  3. **The matrix** — one table with columns: Rule, Detects, Backward, Forward, Config-aware. All 21 rules from §3 verbatim (CONTRACT_REMOVED … COVERAGE_GAP). Use ✅/⚠️/❌ glyphs mapped to SAFE/WARN/BREAK with the legend.
  4. **Config awareness** subsection — how `ignoreUnknownKeys`, `encodeDefaults`, `explicitNulls`, `coerceInputValues`, `namingStrategy`, `classDiscriminator` flip verdicts (§7 table). This is the key differentiator; keep it tight.
  5. One paragraph on the oracle guarantee: every rule is backed by a round-trip test against real kotlinx-serialization; `COVERAGE_GAP` means unanalysable, never silently safe.
  6. A note that in PR ③ this matrix becomes generated from the shipped rule set.

- [ ] **Step 3: Build strict + assert all rules present.**
```bash
build/docs-venv/bin/mkdocs build --strict
for r in CONTRACT_REMOVED PROPERTY_ADDED PROPERTY_REMOVED PROPERTY_OPTIONALITY \
  PROPERTY_NULLABILITY PROPERTY_TYPE_CHANGED PROPERTY_JSON_NAMES ENUM_VALUE_ADDED \
  ENUM_VALUE_REMOVED SUBTYPE_ADDED SUBTYPE_REMOVED DISCRIMINATOR_CHANGED \
  DISCRIMINATOR_VALUE_CHANGED CONFIG_NAMING_STRATEGY CONFIG_DISCRIMINATOR \
  CONFIG_READER_STRICTNESS CONFIG_ENCODE_DEFAULTS CONFIG_EXPLICIT_NULLS \
  CONFIG_COERCE_INPUT COVERAGE_GAP; do
    grep -q "$r" site/rules/index.html || echo "MISSING: $r"
done
```
Expected: build PASS and NO "MISSING:" lines.

- [ ] **Step 4: Commit.**
```bash
git add docs/rules.md
git commit -m "docs(site): rules — breaking-change matrix + config semantics (#105)

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

### Task 5: Recipes + Deep dive (with Mermaid) + fix the changelog link

**Files:**
- Modify: `docs/recipes.md`, `docs/deep-dive.md`, `CHANGELOG.md`

**Interfaces:**
- Consumes: ground-truth §Architecture, §1 (baseline worktree), §5 (CLI), §3 (classifier/oracle).

- [ ] **Step 1: Read the reference** (§Architecture, §1, §3, §5).

- [ ] **Step 2: Rewrite `docs/recipes.md`** — task-oriented, each a short section:
  - **Cross-repo / non-Gradle diff** with the CLI: `serialkompatExtract` in each repo → `serialkompat diff baseline.snapshot current.snapshot` (from §5).
  - **First-time adoption**: `failOnEmptyBaseline.set(false)` for the first run, then remove.
  - **Sanctioning a deliberate break**: `acceptedBreaks` with the exact string format and a note it's logged as acknowledged.
  - **Monorepo**: apply per-module, use `include`/`exclude` serial-name prefixes to scope.
  - (Do NOT include the append-only history recipe — not implemented; mention it as roadmap in one line at most.)

- [ ] **Step 3: Rewrite `docs/deep-dive.md`** — terse, human, with diagrams. Sections:
  1. **Architecture** — a Mermaid `flowchart LR` of the pipeline (`@Serializable` → Extractor → Snapshot → Differ → Classifier → Report), 3-4 sentences on the separation (core is pure, no I/O).
  2. **Extraction** — walks the compiled `SerialDescriptor`, sees real wire keys (post-`@SerialName`/namingStrategy), optionality, nullability, enums, polymorphism; opaque types become `COVERAGE_GAP` (never throws, never silently safe).
  3. **Classification & the oracle** — direction- and config-aware; every rule verified by a round-trip oracle (serialize old, decode new, assert prediction). A Mermaid diagram of the round-trip is welcome.
  4. **Git-ref baseline** — extracted live from a git ref via a temporary worktree, cached by content hash; no baseline file to maintain or forget. A Mermaid `sequenceDiagram` or `flowchart` of the flow is welcome.
  5. Link to the in-repo engineering spec (`docs/design/`) on GitHub for full detail — use the **absolute GitHub URL** `https://github.com/chrisjenx/serialkompat/tree/main/docs/design`, NOT a relative path.

- [ ] **Step 4: Fix the PR ① Minor — the CHANGELOG dead link.** In `CHANGELOG.md`, find the `[docs/design](docs/design)` (or similar relative link to `docs/design`) and change the target to the absolute URL `https://github.com/chrisjenx/serialkompat/tree/main/docs/design` so it resolves on the rendered changelog page. Change only the link target.

- [ ] **Step 5: Build strict + assert Mermaid + link fix.**
```bash
build/docs-venv/bin/mkdocs build --strict \
  && grep -q "mermaid" site/deep-dive/index.html \
  && grep -q "serialkompat diff" site/recipes/index.html \
  && ! grep -q 'href="docs/design"' site/changelog/index.html
```
Expected: build PASS; first two greps exit 0; the last (negated) confirms the dead relative link is gone.

- [ ] **Step 6: Commit.**
```bash
git add docs/recipes.md docs/deep-dive.md CHANGELOG.md
git commit -m "docs(site): recipes + deep dive (Mermaid); fix changelog design link (#105)

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

### Task 6: Visual validation (controller + headless browser)

**Files:** none (validation only; may produce fix commits if rendering is broken).

**Interfaces:**
- Consumes: the full built site.

This task is executed by the controller using the Playwright MCP browser against a local `mkdocs serve`, because rendered visuals (animated pipeline, Mermaid, dark mode, tables) cannot be verified from HTML greps alone.

- [ ] **Step 1:** Serve the site: `build/docs-venv/bin/mkdocs serve -a 127.0.0.1:8001` (background).
- [ ] **Step 2:** Navigate + screenshot: Home (confirm the pipeline renders as connected stages, not raw HTML; is/is-not table present), Rules (matrix renders with glyphs, not markdown source), Deep dive (Mermaid diagrams render as SVG, not code fences), Setup (tabs work). Capture both light and dark palette on Home.
- [ ] **Step 3:** For any broken rendering, dispatch a fix subagent with the specific defect; re-validate.
- [ ] **Step 4:** Stop the server. Record validation results (with screenshot observations) in the ledger.

---

## Self-review checklist (controller, before final review)
- Every page's stub `#105` note removed.
- No "not yet implemented" feature documented as available; license Apache 2.0 everywhere.
- All install snippets show `0.1.0-SNAPSHOT` and `com.chrisjenx`.
- Matrix has all 21 rules; config-awareness section present.
- Animated pipeline honors `prefers-reduced-motion`; Mermaid renders; tabs render.
- `mkdocs build --strict` green; changelog dead link fixed.
