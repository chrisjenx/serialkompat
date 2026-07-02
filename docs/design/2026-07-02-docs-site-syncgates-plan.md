# Docs Site Sync Gates (PR ③, #106) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development. Steps use checkbox (`- [ ]`) syntax.

**Goal:** Make the docs provably track the code with two automated gates: a rule-matrix completeness check wired into `./gradlew check`, and version templating so install snippets never show a stale version.

**Architecture:** Branch `docs/site-syncgates`, stacked on `docs/site-content` (PR #109). Gate 1 is a root Gradle verification task (`checkRulesDoc`) reading `Finding.kt`'s `Rules` constants and asserting each appears in `docs/rules.md`; wired into `check`. Gate 2 uses `mkdocs-macros-plugin` to inject the version from `gradle.properties` into install snippets at build time.

**Tech Stack:** Gradle (Kotlin DSL) verification task; MkDocs Material + `mkdocs-macros-plugin` 1.5.0.

## Global Constraints

- Branch `docs/site-syncgates`. Commit trailer: `Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>`.
- Push workflow-free changes over SSH (no `.github/workflows/` change in this PR).
- JVM target 17; do not add a toolchain that downloads a JDK. Config-cache is on — the verification task must not capture `Project` at execution time (read files via task inputs / providers).
- Do NOT break the existing default MkDocs `search` plugin: when adding a `plugins:` key you MUST list `search` explicitly alongside `macros`.
- Version literal today is `0.1.0-SNAPSHOT`; after Gate 2 the install snippets render it from the macro, and a local build with no injected version must still produce a sensible value (fallback to reading `gradle.properties`).
- Deferred (out of scope, tracked): the compiled `docs-samples` module (design's sync gate #1). Update the design doc's sync-gates section to mark it deferred with a one-line rationale, and open/note a follow-up issue.
- `git add` explicit paths only — never `git add -A` (`.superpowers/` scratch must not be committed).

---

### Task 1: `checkRulesDoc` — rule-matrix completeness gate

**Files:**
- Modify: `build.gradle.kts` (root) — register the task, wire into `check`.
- Create: `gradle/rules-doc-check.md` is NOT needed; the task reads existing files.

**Interfaces:**
- Produces: Gradle task `checkRulesDoc` that fails the build if any `Rules.*` constant is absent from `docs/rules.md`.

- [ ] **Step 1: Write a failing check first.** Temporarily remove one rule row (e.g. `COVERAGE_GAP`) from `docs/rules.md` in your working tree (do NOT commit this), so you can watch the new task fail for the right reason in Step 3.

- [ ] **Step 2: Add the task to root `build.gradle.kts`.** Register a verification task that:
  - Declares as inputs the file `serialkompat-core/src/main/kotlin/com/chrisjenx/serialkompat/core/Finding.kt` and `docs/rules.md` (so it's config-cache-safe and up-to-date-checked).
  - At execution, reads the `Rules` object constants by regex `const val (\w+): String = "\1"` (capture the constant name), reads `docs/rules.md` text, and collects any constant name NOT present in the doc.
  - Throws `GradleException` listing missing rules if the set is non-empty; else prints "checkRulesDoc: N/N rules documented".
  - Wire it: `tasks.named("check") { dependsOn("checkRulesDoc") }` (guard for the task existing in the root, or apply to the root `check` lifecycle — the root has the `base` plugin via convention; if `check` is absent at root, register the task and add it to each library module's check, or apply `base`). Prefer: apply `base` plugin at root if not present, then wire `check`.

  Exact task body (Kotlin DSL, place near the other root config):
```kotlin
val checkRulesDoc by tasks.registering {
    group = "verification"
    description = "Fails if any Rules.* constant is undocumented in docs/rules.md."
    val findingKt = layout.projectDirectory.file(
        "serialkompat-core/src/main/kotlin/com/chrisjenx/serialkompat/core/Finding.kt",
    )
    val rulesDoc = layout.projectDirectory.file("docs/rules.md")
    inputs.file(findingKt)
    inputs.file(rulesDoc)
    doLast {
        val constRegex = Regex("""const val (\w+): String = "\1"""")
        // The above backreference form is illustrative; use the two-group form below.
        val decl = Regex("""const val (\w+)\s*:\s*String\s*=\s*"([A-Z_]+)"""")
        val ruleIds = decl.findAll(findingKt.asFile.readText())
            .map { it.groupValues[2] }
            .filter { it.isNotEmpty() }
            .toList()
        require(ruleIds.isNotEmpty()) { "checkRulesDoc: found no Rules constants — regex/paths wrong?" }
        val docText = rulesDoc.asFile.readText()
        val missing = ruleIds.filter { !docText.contains(it) }
        if (missing.isNotEmpty()) {
            throw GradleException(
                "docs/rules.md is missing rule row(s): ${missing.joinToString(", ")}. " +
                    "Update the matrix in docs/rules.md when you add or rename a rule.",
            )
        }
        logger.lifecycle("checkRulesDoc: ${ruleIds.size}/${ruleIds.size} rules documented")
    }
}

tasks.matching { it.name == "check" }.configureEach { dependsOn(checkRulesDoc) }
if (tasks.findByName("check") == null) {
    // Ensure a root `check` exists to hang the gate on.
    plugins.apply("base")
    tasks.named("check") { dependsOn(checkRulesDoc) }
}
```
  (Use the `decl` two-group regex; delete the illustrative `constRegex` line. Adjust wiring to match the repo's existing root plugin setup — the intent: `./gradlew check` runs `checkRulesDoc`.)

- [ ] **Step 3: Run it and watch it FAIL** (with the row still removed from Step 1):
```bash
./gradlew checkRulesDoc
```
Expected: FAIL — `docs/rules.md is missing rule row(s): COVERAGE_GAP`.

- [ ] **Step 4: Restore the removed row** in `docs/rules.md` (revert Step 1), then run again:
```bash
./gradlew checkRulesDoc
```
Expected: PASS — `checkRulesDoc: 21/21 rules documented`.

- [ ] **Step 5: Confirm it's wired into `check`:**
```bash
./gradlew check --dry-run 2>&1 | grep -i checkRulesDoc
```
Expected: `checkRulesDoc` appears in the `check` task graph.

- [ ] **Step 6: Commit.**
```bash
git add build.gradle.kts
git commit -m "build: checkRulesDoc gate — every Rules.* constant must be in docs/rules.md (#106)

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

### Task 2: Version templating via mkdocs-macros

**Files:**
- Modify: `requirements-docs.txt`, `mkdocs.yml`
- Create: `docs/_macros/main.py` (macros module) — or root `main.py`; use `macros: module_name`.
- Modify: `docs/quickstart.md`, `docs/setup.md`, `docs/configuration.md` (replace the `0.1.0-SNAPSHOT` literal in install snippets with `{{ skversion }}`).

**Interfaces:**
- Consumes: `gradle.properties` `version=` line.
- Produces: a `skversion` macro variable available in all pages.

- [ ] **Step 1: Pin the plugin.** Append to `requirements-docs.txt`:
```text
mkdocs-macros-plugin==1.5.0
```
Reinstall into the venv: `build/docs-venv/bin/pip install -r requirements-docs.txt`.

- [ ] **Step 2: Write the macros module** `main.py` (repo root — mkdocs-macros default looks for `main.py`):
```python
"""mkdocs-macros hooks. Exposes the project version so install snippets never go stale."""
import os
import re


def _read_version() -> str:
    # Prefer an explicitly injected version (CI can set SERIALKOMPAT_VERSION); else read gradle.properties.
    env = os.environ.get("SERIALKOMPAT_VERSION")
    if env:
        return env
    try:
        with open("gradle.properties", encoding="utf-8") as fh:
            for line in fh:
                m = re.match(r"\s*version\s*=\s*(\S+)", line)
                if m:
                    return m.group(1)
    except OSError:
        pass
    return "dev"


def define_env(env):
    env.variables["skversion"] = _read_version()
```

- [ ] **Step 3: Enable the plugin in `mkdocs.yml`.** Add a top-level `plugins:` key — MUST include `search` explicitly (adding `plugins:` disables the built-in default search otherwise):
```yaml
plugins:
  - search
  - macros
```

- [ ] **Step 4: Templatize the version in install snippets.** In `docs/quickstart.md`, `docs/setup.md`, and `docs/configuration.md`, replace the literal `0.1.0-SNAPSHOT` in the plugin `version "..."` install lines with `{{ skversion }}`. Do NOT change the changelog include or the status admonitions that describe the release state in prose — only the machine-copyable install coordinates. (If a snippet uses a fenced block that macros would ignore, confirm macros renders inside fenced code — mkdocs-macros DOES render `{{ }}` inside code fences by default; verify in Step 5.)

- [ ] **Step 5: Build strict + assert the version rendered (not the literal `{{ }}`):**
```bash
build/docs-venv/bin/mkdocs build --strict \
  && grep -q '0.1.0-SNAPSHOT' site/setup/index.html \
  && ! grep -q 'skversion' site/setup/index.html
```
Expected: build PASS; the version string is present in the rendered HTML and the raw `{{ skversion }}` is gone. Also sanity-check search still works: `test -d site/search && test -f site/search/search_index.json`.

- [ ] **Step 6: Verify the injected-version path** (CI override):
```bash
SERIALKOMPAT_VERSION=9.9.9-TEST build/docs-venv/bin/mkdocs build --strict \
  && grep -q '9.9.9-TEST' site/setup/index.html
```
Expected: PASS — the env override wins. (Rebuild without the env var afterward so `site/` reflects the real version.)

- [ ] **Step 7: Commit.**
```bash
build/docs-venv/bin/mkdocs build --strict   # restore site/ to real version
git add requirements-docs.txt mkdocs.yml main.py docs/quickstart.md docs/setup.md docs/configuration.md
git commit -m "docs(site): template install version from gradle.properties via mkdocs-macros (#106)

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

### Task 3: Mark the compiled-samples gate deferred (design honesty)

**Files:**
- Modify: `docs/design/2026-07-02-docs-site-design.md`

**Interfaces:** none.

- [ ] **Step 1:** In the design doc's "Sync gates" section, mark gate 1 (`docs-samples` compiled snippets) as **Deferred** with a one-line rationale: it is a larger architectural change (new Gradle module + re-embedding every snippet) best done as its own human-reviewed PR; the rule-matrix completeness check and version templating (this PR) already provide anti-rot coverage. Reference a follow-up.

- [ ] **Step 2:** Commit.
```bash
git add docs/design/2026-07-02-docs-site-design.md
git commit -m "docs: defer compiled docs-samples gate to a follow-up; note rationale (#106)

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

### Task 4: Push, open PR, verify CI (controller)

- [ ] Push `docs/site-syncgates`; open PR with base `docs/site-content` (stacked). Verify `./gradlew build` (which now runs `checkRulesDoc`) is green on CI, and the Docs strict build passes with macros enabled.
