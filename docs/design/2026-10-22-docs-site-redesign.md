# serialkompat docs redesign: design

Date: 2026-10-22. Status: approved. Supersedes
[2026-07-02-docs-site-design.md](2026-07-02-docs-site-design.md).

## Intent

Make the serialkompat docs clearer, easier to follow and better looking, for humans and
for agents (LLMs reading the README or the site). No fluff: every page cuts to the
chase. The main thing to fix is the opening path. A new reader should go
**problem → solution → getting started** without wading. Beyond that, the
whole site should be easier to scan and nicer to look at.

The content is mostly accurate today. The problems are structure and presentation:

- The same topics are explained in several places. For example, "direction" is defined
  five times.
- Critical onboarding facts are buried. The first-PR gotcha lives in Recipes.
- The home page explains internals instead of value.
- Several gaps: what to do when you get a BREAK, troubleshooting, a single tasks/CLI
  reference, KMP/Android/multi-module guidance, a glossary.
- Some content is stale: the 0.1.0 caveats, and a SARIF example that lists 25 of the 33
  rules with version 0.1.0.
- Only the home page has any visual design.

The maintainer pointed to the SQLDelight and Metro docs as the bar. They asked for
"whatever is the best convention that is easy to keep in sync with code and looks clean
and modern".

## Goals

1. **Home page:** problem → story → proof → install, in the approved layout (below).
2. **Structure:** five top tabs (Home · Get started · Guides · Reference · How it works),
   and one home per topic. Other pages link to that home instead of restating it.
3. **Fill the gaps:**
   - Concepts
   - Got a BREAK?
   - Tasks & CLI reference
   - Limitations
   - Troubleshooting & FAQ
   - KMP/Android and multi-module install guidance
4. **Visuals:** a clean, modern, consistent look across every page, not only the home page.
5. **Docs that stay in sync with code:** examples and samples come from compiled or
   generated sources, and gates fail the build on drift.
6. **Agent-readable:** a README that follows the same story, plus generated
   `llms.txt` / `llms-full.txt`.
7. Fix all stale content.

## Non-goals

- No change to product behaviour or to user-visible output strings. Report wording and
  rule ids stay exactly as they are. The one exception: a classifier bug found by a new
  oracle test gets fixed in its own PR (see "Rules page").
- No migration to Zensical (see Constraints).
- No versioned docs (`mike`) while on 0.x.
- No social-card plugin; it needs cairo/pillow image dependencies in CI.
- No rewrite of `docs/design/**` engineering specs. They stay excluded from the site.

## Anti-goals (a failure even if it technically works)

- **A marketing tone or filler.** Avoid "blazing fast", "powerful", or a paragraph where
  a table or a line would do.
- **Breaking existing inbound URLs.** SARIF rule help links point at `/rules/`. Old
  releases' READMEs and people's bookmarks point at current page paths.
- **A docs claim the code doesn't back.** Example: claiming Android support without
  checking.
- **Re-introducing duplication:** the same explanation in two places.
- **A red `./gradlew build` or `mkdocs build --strict`** at any PR boundary.

## Constraints (from the repo)

- **Engine.** Stay on MkDocs Material, the engine SQLDelight and Metro use. Keep
  `mkdocs.yml` compatible with Zensical so a later switch is cheap. Metro has partly
  migrated. We don't, because the `macros` plugin (`{{ skversion }}` from `main.py`) and
  the `--8<--` snippet includes are load-bearing and Zensical's plugin support isn't
  confirmed. *Maintainer agreed.*
- **Fixed paths.**
  - `docs/rules.md` stays at its path. `SarifReporter.kt` hard-codes
    `https://chrisjenx.github.io/serialkompat/rules/`, and the `checkRulesDoc` and
    `checkRulesProof` gates (root `build.gradle.kts`) read that file.
  - The gates need every `Rules.*` id somewhere on the page.
  - Every `**Proof:**` line must cite an existing oracle test by name, as a
    backtick-wrapped link text.
  - `### \`RULE_ID\`` headings mark the per-rule sections.
  - These pages also keep their current paths: `quickstart.md`, `ci.md`,
    `configuration.md`, `cbor.md`, `report-formats.md`, `changelog.md`, `index.md`.
- **Removed pages get redirects.** `setup.md`, `recipes.md` and `deep-dive.md` are
  retired. Add `mkdocs-redirects` (pin it in `requirements-docs.txt`) with an entry for
  every retired page, pointing at the page that now holds most of its content.
  - Where an old anchor is well known, keep the same anchor id on the new home using
    `{ #id }`. Examples: `recipes.md#first-time-adoption`, `setup.md#gradle-plugin`,
    `#cli`, `#github-action`, `ci.md#build-cache-configuration-cache`.
  - The builder greps the repo (README, CONTRIBUTING, `action.yml`, `serialkompat-*/src`,
    `docs/`) for `github.io/serialkompat` and for relative links, and updates them all.
- **README bump line.** `.github/workflows/release.yml:228` rewrites the README's
  version with this sed:
  `s/(id\(\"com\.chrisjenx\.serialkompat\"\) version \")[^\"]+\"/\1${VERSION}\"/`.
  The README must keep exactly one line of the shape
  `id("com.chrisjenx.serialkompat") version "X.Y.Z"`, or releases silently stop bumping it.
- **Strict build at every PR.** `mkdocs build --strict` fails on orphan pages and broken
  anchors. Every PR must land its renames, nav, redirects and cross-link updates together.
- **CI trigger paths.** `docs.yml` runs only on changes to `docs/**`, `mkdocs.yml`,
  `requirements-docs.txt`, `main.py` and the workflow file itself.
  - Add every file the site pulls in via snippets: `CHANGELOG.md` (already missing),
    `CONTRIBUTING.md`, the `docs-samples/` module, and any generated-sample directory.
    Otherwise a PR that only touches those skips the docs gate.
  - Update the CI line in `CLAUDE.md` to match.
- **`serialkompat-core` stays I/O-free.** Doc-generation code lives in tests or in root
  Gradle tasks, never in core `main`.
- **Golden rules apply** (CLAUDE.md).
  - Gate code is driven by a test, or by a deliberately failing run shown in the PR.
  - Any new per-rule "works / fails" example must be backed by an existing oracle test.
  - If a rule has no oracle test, adding one is TDD work in the same PR, against the real
    library.
- **Design docs.** CLAUDE.md requires the design doc to be updated in the same PR as any
  deviation.
  - This file is the durable design. It supersedes
    `docs/design/2026-07-02-docs-site-design.md`, which carries a "Superseded by" line.
- **Formatting.** Spotless/ktlint: lines ≤115 chars in Kotlin. `apiDump` is only needed if
  public API changes; none is expected.

## Decided: look and feel

*Maintainer approved A ("clean & monochrome", Metro-like) plus B's 1-2-3 story strip.*

**Theme (`mkdocs.yml`):**

- **Palette:** `primary: white` in light mode and `black` in dark mode, with
  `accent: green`. Use three toggles, with system preference first (Metro's pattern).
- **Fonts:** `text: Inter`, `code: Fira Code`.
- **Features:** add these to the existing ones:
  - navigation: `navigation.tabs`, `navigation.top`, `navigation.footer`
  - page actions: `content.action.edit`, `content.action.view`, `content.code.select`
  - search: `search.highlight`
  - `toc` with `permalink: true`
- **Logo and favicon:** a simple monochrome SVG, the builder's design. A geometric mark
  plus the wordmark in Inter works well; keep it legible at 16px.
- **One green** for the accent and success states. It must have AA contrast on white and
  on Material's slate background. BREAK stays red and WARN amber.
  - *As built (PR 1):* no single hex reaches 4.5:1 on both white and slate (#2e303e), so
    `--sk-accent` is one green family that flips per scheme: `#15803d` (5.02:1) on light,
    `#4ade80` (7.49:1) on slate. Buttons use a fixed `--sk-accent-solid: #15803d` fill so
    white text keeps 5.02:1 in both schemes. Tokens (`--sk-accent`, `--sk-break`,
    `--sk-warn`, `--sk-surface`, `--sk-border`) live in `docs/stylesheets/pipeline.css`.
  - `navigation.tabs` is added with the nav in PR 2, not PR 1.

**Components.** Prefer Material built-ins over custom CSS. Use Material's
`<div class="grid cards" markdown>` for card grids.

- **Keep and restyle** the existing `pipeline.css` components to the new palette:
  `sk-hero`, `sk-terminal`, `sk-pipeline`. Their reduced-motion handling stays.
- **Add `sk-steps`,** the numbered 1-2-3 strip. It stacks vertically under ~600px.
- **Mermaid** is already self-hosted. Use it for flow diagrams: direction, the baseline
  flow, the architecture.
- **Visual vocabulary for the whole site:**
  - card grids for "pick your path" choices;
  - step strips for sequences;
  - terminal blocks for output;
  - success/failure admonition pairs for "this works / this breaks";
  - tables for reference only.

**Home page (`index.md`), approved layout, top to bottom.**

1. **H1:** "Catch breaking JSON & CBOR changes before they ship". Under it, one line: "A
   CI gate for kotlinx-serialization `@Serializable` models, like `buf breaking` but for
   JSON and CBOR."
2. **Story strip:**
   1. **You change a model.** Rename a field, drop a default, add a required property.
   2. **Old data is still out there.** Older app versions, queues, caches and databases
      hold the previous shape.
   3. **The PR fails, with a fix.** serialkompat diffs against your target branch and
      says what breaks and how to fix it.
3. **Proof:** the existing diff → terminal hero, side by side on wide screens and
   stacked on narrow ones. Captions: "The one-line change on your PR" and "CI".
4. **"Why it's trustworthy":** three cards.
   - **No baseline file:** the old schema is extracted live from your target branch on
     every run.
   - **Both directions:** new code reading old data, and old code reading new data.
   - **Proven, not guessed:** every rule is round-trip tested against real
     kotlinx-serialization, under your `Json { }` / `Cbor { }` config.
5. **Get started:** the `plugins { id(...) version "{{ skversion }}" }` block, then
   buttons for Quick start (primary), Rules and GitHub Action.
6. **One "is not" line:** "Not a runtime validator, migration tool or JSON-schema
   linter." It replaces today's is/is-not table.

The "How it works" pipeline moves off the home page to Architecture. The copy wording is
the builder's to polish within these points; keep it this short.

## Decided: structure

*Maintainer approved.*
Files stay flat in `docs/`, without subfolders, to keep URLs short and churn low.

| Tab | Page (file) | Content and where it comes from |
|---|---|---|
| Home | `index.md` | The approved layout above |
| Get started | Quick start (`quickstart.md`) | Apply → run → read the report. **"Your first PR" moves up front** (from `recipes.md#first-time-adoption`; keep that anchor id) |
| | Installation (`installation.md`, replaces `setup.md`) | Gradle plugin · GitHub Action (minimal; CI owns the details) · CLI build/run · multi-module · KMP / Android |
| | **Concepts** (`concepts.md`, new) | Baseline (a mermaid flow of the git-ref worktree) · backward vs forward (**one** diagram: old/new writer and reader across a deploy) plus "which direction do I pick" (from `configuration.md`'s table) · BREAK vs WARN, and what each means for you · glossary |
| Guides | CI (`ci.md`) | GitHub Actions · GitLab · any CI. It owns the Action inputs and the sticky comment. The build-cache section moves to Baseline & caching |
| | **Got a BREAK?** (`got-a-break.md`, new) | A decision path: fix it (common fixes per rule family, linking to the rule) · it's intended → accept it (`acceptedBreaks`) · it's a rename → `renames` / `@JsonNames` · bump major |
| | Adopting gradually (`adopting.md`) | Discovery modes and annotations. It owns discovery; `configuration.md` links here |
| | Monorepos (`monorepos.md`) | include/exclude scoping, many modules, the multi-module Action |
| | Persisted data & history (`history.md`) | `serialkompatRecord` / `serialkompatCheckHistory` and retention (from `recipes.md`) |
| | CBOR (`cbor.md`) | As today, with "one format per module" and "adopting CBOR" promoted to H2s. It links to Rules instead of restating rule rows |
| | Cross-repo with the CLI (`cross-repo.md`) | From `recipes.md`. CLI flags move to Tasks & CLI |
| Reference | Configuration (`configuration.md`) | The full `serialkompat { }` DSL. It owns the `reports { }` *options* table; prose about formats stays in Report formats |
| | **Tasks & CLI** (`tasks-cli.md`, new) | Every Gradle task, `-P` property, CLI command, flag and exit code, in one place. Other pages link here |
| | Rules (`rules.md`) | See "Rules page" below |
| | Report formats (`report-formats.md`) | Open with user value, not internals. All samples are generated (see Sync) |
| | **Limitations** (`limitations.md`, new) | What can't be analysed (OPAQUE / COVERAGE_GAP) and what to do about it (from `deep-dive.md` "When a type can't be analysed"), plus known limits (one format per module, and so on) |
| | **Troubleshooting & FAQ** (`troubleshooting.md`, new) | See "New page content" |
| How it works | Architecture (`architecture.md`) | The pipeline (the `sk-pipeline` component or mermaid) and extraction (from `deep-dive.md`) |
| | The oracle (`oracle.md`) | Why the rules can be trusted (from `deep-dive.md` and `rules.md` "The oracle guarantee") |
| | Baseline & caching (`baseline-caching.md`) | The git-ref worktree and robustness (from `deep-dive.md`), and the build/configuration cache (from `ci.md`) |
| | Contributing (`contributing.md`) | `--8<-- "CONTRIBUTING.md"`, the same pattern as `changelog.md`. "Keeping the rules page in sync" moves here from `rules.md` |
| | Changelog (`changelog.md`) · API ↗ | Unchanged |

Retired with redirects:

- `setup.md` → `installation.md`
- `recipes.md` → `adopting.md`, the closest overall
- `deep-dive.md` → `architecture.md`

Pages that are heavily cross-linked keep their anchors via `{ #id }`.

**Old deep links land on the right section** (*my call*). `recipes.md` splits across five
pages, so a page-level redirect isn't enough. Each retired path becomes a small stub page,
outside the nav (`not_in_nav`), so `--strict` accepts it.

- It has a short JS map from old `#anchor` to its new page and anchor.
- If no anchor matches, it falls back to that page's main redirect target.
- It also lists links to every new home, for visitors without JS.

Map every H2/H3 anchor of `setup.md`, `recipes.md` and `deep-dive.md`. That includes
`README.md`'s `recipes/#persisted-data-horizon-multi-version-history` → `history.md`, and
`#first-time-adoption` → `quickstart.md`. Use `mkdocs-redirects` only if it can do this;
otherwise use the stubs alone. Check it with a local `mkdocs serve` and a few old URLs.

**One home per topic.** The builder removes the duplicates the audit found and replaces
them with links:

- direction is defined in `rules.md`, `cbor.md`, `deep-dive.md` and `configuration.md`
  → Concepts;
- exit codes in `ci.md`, `recipes.md` and `report-formats.md` → Tasks & CLI;
- the Action YAML is duplicated in `setup.md` → CI;
- the discovery text is duplicated in `recipes.md` → Adopting gradually;
- the oracle/OPAQUE text is duplicated in `rules.md` → The oracle / Limitations;
- the CSS pipeline on the home page duplicates the architecture mermaid → Architecture
  only.

**Stale content to fix:**

- the "0.1.0 is Maven Central only" and "Requires 0.1.0" caveats (`setup.md:13`,
  `setup.md:174`, `ci.md:16`);
- "the CLI isn't published as a download yet" (re-verify; keep it if still true);
- the SARIF example (fixed by generation);
- the internal-issue pointer in `rules.md` (#119);
- the CBOR footnote numbering in the rules matrix.

**Terminology** (the glossary owns these):

- Use "accepted break" in prose, because the DSL is `acceptedBreaks`. Note once that the
  console report calls these "acknowledged" and SARIF calls them "accepted".
- Always say "backward (new code reads old data)" and "forward (old code reads new
  data)" on first use per page, linking to Concepts.
- Glossary terms: contract, serial name, root type, baseline, snapshot, finding,
  OPAQUE / coverage gap, pairwise vs history check.

## Decided: "How it works" must explain the mechanism

*Maintainer feedback, 2026-10-26:* today's "How it works", a row of component names
(Extractor → Snapshot → Differ → Classifier → Report), "doesn't really explain
anything". Wherever "how it works" appears (`architecture.md` and the README), it must
walk through **one concrete run** of the home page's `Order` example, so a reader
understands *what actually happens and why the verdict is trustworthy*.

1. **Your PR runs `serialkompatCheck`.**
2. **It builds the old schema.** It checks out the baseline ref (your target branch) in a
   temporary git worktree and runs the extractor there.
3. **It builds the new schema** from your PR's compiled classes.
4. **What "extract" means.** It loads your compiled `@Serializable` classes and reads the
   `SerialDescriptor` that kotlinx-serialization itself uses to encode. It doesn't parse
   source. That descriptor is how it sees the real wire keys (after `@SerialName` and the
   naming strategy), optionality, defaults, nullability, enum values and sealed subtypes,
   under *your* `Json { }` / `Cbor { }` instance.
5. **Show the snapshot.** Include a short excerpt of the real snapshot for `Order`,
   before and after: actual `serialkompatExtract` output, copied or generated, never
   invented.
6. **The diff:** "field `note` removed".
7. **The verdict, both ways.**
   - *Backward:* can new code read old data? An old payload still carries `note`. Under a
     strict reader (`ignoreUnknownKeys = false`), decoding it throws, so this is a
     **BREAK**.
   - *Forward:* can old code read new data? `note` has a default, so old code reading a
     payload without it is fine.
   - Show how the verdict flips with config, e.g. `ignoreUnknownKeys = true` makes the
     backward case decode while silently dropping the data. Check the actual rule outcome
     in `rules.md` and the classifier before writing this; the example must match what
     the tool really reports.
8. **Why you can trust that verdict.** Each rule has an oracle test: encode with the old
   model, decode with the new one using real kotlinx-serialization, and assert the
   prediction. Link to the oracle page.

Format: an `sk-steps` strip or a numbered sequence, with one mermaid sequence/flow diagram
of the run (PR → worktree at baseline → two snapshots → diff → classify with config →
report). The component list (Extractor, Snapshot, Differ…) comes **after** the
walkthrough, as a short "Under the hood" reference for contributors. The README gets a
compressed version of the same walkthrough (≤8 lines plus the diagram link), not the
component row.

## Decided: rules page

`rules.md` keeps its path and gate syntax. This work **completes issue #119 (PR 2b)** and
closes it.

- **Grouped matrix with scannable cells.** Groups:
  - JSON properties / types
  - enums
  - polymorphism
  - config
  - CBOR-only

  Each row holds the rule id (linking to its section) and short glyph cells: ✅ safe /
  ⚠️ WARN / ❌ BREAK per direction, plus a ≤10-word "when". Remove the footnotes; the
  nuance goes in the rule's section.
- **Per-rule reference section for every rule** (`### \`RULE_ID\``):
  - one line on what it detects;
  - a before/after snippet;
  - a success/failure admonition pair, the pattern already used at `rules.md:133-145`;
  - config effects;
  - `**Proof:** [\`oracle test name\`](link)`.

  If a rule has no oracle test, add one (TDD, real library) in the same PR. Proof links
  point to the test file on GitHub by method name, not by line number.
- **Turn the gate on.** Flip `enforceComplete = true` in `checkRulesProof`.
- **If a new oracle test disagrees with the classifier** (*maintainer decision*), fix the
  classifier in **its own PR first**. That PR is TDD with the oracle case as the RED test,
  a CHANGELOG `Fixed` entry, and an `.api` update if needed. The rules-page PR lands after
  it, so the docs never describe a verdict known to be wrong. This is the one allowed
  exception to the "no product behaviour change" non-goal.
- **Rules with no meaningful round-trip** (*my call*): `COVERAGE_GAP`, `CONFIG_FORMAT`,
  `DISCRIMINATOR_COLLISION` and the like. Their `**Proof:**` line may cite a
  classifier/extractor unit test instead of an oracle test.
  - Widen `checkRulesProof`'s test-file list to an explicit, named set of test files.
  - Every rule still needs a Proof line.
- **Before/after snippets on the rules page are hand-written** (*my call*). The cited
  oracle test is the proof, and compiling ~68 `V1`/`V2` fixture classes would make the
  docs read worse. `docs-samples` (PR 4) covers the guide pages, not `rules.md`.
- **"Config awareness"** becomes a table with short cells. The oracle prose moves to
  `oracle.md`, and "Keeping this page in sync" moves to `contributing.md`.

## Decided: keeping docs in sync with code

*Maintainer agreed to all four; the builder decides the details.*

1. **Compiled examples: the `docs-samples` module (issue #110, closed by this work).**
   - A new unpublished Gradle module, `docs-samples`. Exclude it from `apiCheck`, Kover
     thresholds and publishing.
   - It holds the Kotlin used in docs (models, `Json { }` / `Cbor { }` instances), marked
     with `--8<-- [start:name]` / `[end:name]` regions and embedded via
     `pymdownx.snippets`.
   - It compiles and is tested under `./gradlew build`. At minimum, a test serializes the
     sample models so they stay valid.
   - Gradle DSL examples (`serialkompat { }` blocks) live as region-marked files under
     `docs-samples/` and are exercised by a `serialkompat-gradle` TestKit functional test
     that applies them.
   - **The `plugins { id(...) version "{{ skversion }}" }` lines stay hand-written in the
     Markdown,** because a compiled file can't carry the macro. Only the blocks after them
     are embedded.
2. **Generated report samples.**
   - One test renders the shared worked example (the one already used on
     `report-formats.md`) through each real reporter: console, JSON, SARIF and GitHub.
     It lives in `serialkompat-core` tests, or in `serialkompat-cli` tests if the console
     path needs the CLI.
   - The output goes to committed golden files under `docs/snippets/reports/`.
   - The test fails when the output differs from the committed files. It rewrites them
     when run with a documented update switch (system property or Gradle `-P`).
   - Pages embed these files, which fixes the stale SARIF example permanently. Pin or
     omit the tool version so output is deterministic; under TestKit `toolVersion` is
     already null.
3. **A gate on the reference pages.** Add a `checkDocsReference` root task next to
   `checkRulesDoc`, inlining its logic (the configuration cache forbids shared helpers;
   see the comment in `build.gradle.kts`). It fails if:
   - a public property of `SerialkompatExtension` and its nested blocks (`history { }`,
     `reports { }`) is missing from `configuration.md`;
   - a task registered in `SerialkompatPlugin`, or a `-Pserialkompat.*` property, is
     missing from `tasks-cli.md`;
   - a CLI flag is missing from `tasks-cli.md`.

   Hook it on `check`.
4. **The existing gates stay:** `checkRulesDoc`, `checkRulesProof` (now enforcing),
   `{{ skversion }}`, and the `CHANGELOG.md` include.

## Decided: README and agents

- **README.md** follows the home page's story:
  1. tagline;
  2. the 1-2-3 story as a numbered list;
  3. the diff + console output;
  4. install, keeping the sed-matched line;
  5. a ≤10-line minimal config;
  6. "Docs" links into the new pages;
  7. a short "Modules" table and "Building";
  8. Contributing and License.

  Remove the Publishing / Release / Marketplace sections.
- **RELEASING.md (new, repo root, maintainer-facing)** takes those sections verbatim,
  tidied: secrets table, release steps, snapshot, Marketplace. The README and CLAUDE.md
  "Releasing" link to it. The CLAUDE.md release checklist stays (agents use it), plus a
  pointer.
- **`llms.txt` / `llms-full.txt`:**
  - Generate them with the `mkdocs-llmstxt` plugin, pinned in `requirements-docs.txt`.
    Give every page a one-line description; this is the plugin's sections config.
  - **Fallback:** if the plugin conflicts with `--strict`, `macros` or snippets, generate
    both files in a `main.py` `on_post_build` hook from the built Markdown sources.
  - Either way they're published at the site root, and `--strict` stays green.
- **Every page opens with a one or two sentence "what this page is for / who it's for"**
  and leads with the task. That also gives agents a summary.

## New page content (minimum)

- **Concepts:** see the structure table. The direction diagram is the centrepiece. Show
  old and new versions of a writer and a reader across a deploy, and which arrow is
  "backward" and which is "forward".
- **Got a BREAK?:** a decision flow (a mermaid flowchart), then a section per exit:
  fix / accept / rename / bump major. Each has a real snippet (from `docs-samples` where
  it's Kotlin) and links to the rule.
- **Tasks & CLI:** tables of tasks (what each does, when it runs, whether it's cached),
  `-P` properties, CLI usage, flags and exit codes (Gradle vs CLI).
- **Limitations:** OPAQUE / COVERAGE_GAP. Covers:
  - what triggers it;
  - why "unanalysable ≠ safe";
  - how to resolve it, including `@Contextual` resolution as of 0.3.1;
  - known limits, with their tracking issues where public: #225, #226, #227.
- **Troubleshooting & FAQ:** at least these entries.
  - the baseline extraction fails on the first PR;
  - shallow clone / `fetch-depth: 0`;
  - `jsonInstance` / `cborInstance` fails to load;
  - "every task was skipped" (no `types` / discovery);
  - an empty baseline;
  - a COVERAGE_GAP warning after upgrading to 0.3.1 (expected once);
  - a slow first run / caching;
  - the sticky comment 403 (`pull-requests: write`);
  - in multi-module builds, one failing module stops the others (`--continue`).

  Each entry is symptom → cause → fix.
- **Installation → KMP / Android:** **the builder verifies from code and tests what is
  actually supported before writing.** Extraction runs on a JVM runtime classpath; the
  annotations module is KMP. State plainly what works (for example: apply to the JVM
  target or a JVM module that holds the shared models) and what doesn't. Never claim
  unverified support.

## PR plan

Each PR passes `./gradlew build` and `mkdocs build --strict`, and is one logical change.

1. **Theme and home.** Covers:
   - palette, fonts, features, logo and favicon;
   - restyled and added components (`sk-steps`);
   - the new `index.md`;
   - the durable design doc in `docs/design/`, plus the supersede note on the old one.

   No nav changes.
2. **Restructure.** Covers:
   - the new nav and the tabs;
   - page splits and moves: `installation`, `adopting`, `monorepos`, `history`,
     `cross-repo`, `architecture`, `oracle`, `baseline-caching`, `contributing`;
   - **Concepts**, which is needed as the single home for direction;
   - **first versions of `got-a-break.md`, `limitations.md` and `tasks-cli.md`,** holding
     the content moved into them (accepting a break, "when a type can't be analysed",
     exit codes and CLI flags). The site deploys on every merge, so no topic may be left
     without its home between PRs (*my call*). PR 3 completes these pages;
   - de-duplication, stale fixes, terminology;
   - `mkdocs-redirects` and all cross-link updates;
   - the `docs.yml` paths and CLAUDE.md CI line.
3. **New pages:** Got a BREAK?, Tasks & CLI, Limitations, Troubleshooting & FAQ, and the
   Installation KMP/Android and multi-module sections.
4. **Sync gates:** the `docs-samples` module plus re-embedding the Kotlin snippets
   (closes #110), the generated report samples, and `checkDocsReference`.
5. **Rules page:** the grouped matrix, every rule section, any missing oracle tests, and
   `enforceComplete = true` (closes #119). If a new oracle test exposes a classifier
   bug, a separate fix PR (5a) merges first.
6. **README, RELEASING.md and agents:** README rewrite, RELEASING.md, `llms.txt` /
   `llms-full.txt`, and the CLAUDE.md pointer updates.

Order: 1 → 2 → 3, then 4 and 5 in either order, then 6. 6 can go any time after 2. Add a
CHANGELOG `[Unreleased]` entry ("Docs: redesigned site…") in the last PR, or one per PR,
the builder's call.

## Left to the builder

- Exact copy, within the approved points and the no-fluff rule.
- The logo design, the exact green, and the CSS details.
- The exact mermaid diagrams.
- How the pages pick up `mkdocs-llmstxt` descriptions.
- Whether golden-file regeneration uses a system property or a Gradle property.
- Where each moved paragraph lands within its new page.
- Whether the docs-samples Gradle DSL test lives in the existing functional-test suite
  or a new class.
