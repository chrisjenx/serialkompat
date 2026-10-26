# Troubleshooting & FAQ

Common failures and questions, each as symptom, cause and fix. It is for anyone whose
serialkompat run failed, was skipped, or printed something unexpected. For what a finding
means, see [Got a BREAK?](got-a-break.md); for exit codes and flags, see
[Tasks & CLI](tasks-cli.md).

Quoted messages are copied from the source. Where an entry has no message of its own
(the failure is Gradle's, git's or GitHub's), it says so and describes what you'll see.

## The baseline extraction fails on the first PR { #first-pr }

**Symptom:** The check fails on the PR that adds the plugin, with one of:

```text
serialkompat: baseline extraction failed in worktree:
<output of the nested Gradle build>
```

```text
serialkompat: the baseline ref has no serialkompat configuration to extract — 'serialkompatExtract' produced no snapshot there (expected <path> or <path>). Check that the baseline ref's committed discovery/types configuration declares at least one type to check.
```

**Cause:** The [baseline](concepts.md#the-baseline) is extracted by running your build at
`baselineRef`, and that ref doesn't have the plugin, or has it without any `types` or
discovery configured. The nested build either fails (the first message, and the log below
it shows why) or skips extraction and writes no snapshot (the second).

**Fix:** Skip the check for the one change that adds serialkompat and merge it. See
[Quick start → Your first PR](quickstart.md#first-time-adoption), which also covers the
variants of this failure.

## Shallow clone, or the baseline ref can't be found { #shallow-clone }

**Symptom:** The check fails before extracting anything, with either of:

```text
serialkompat: could not auto-detect a default baseline branch (looked for origin/HEAD, origin/main, origin/master, main, master). Set serialkompat { baselineRef.set("<ref>") } or pass -Pserialkompat.ref=<ref>.
```

```text
git rev-parse --verify <ref>^{commit} failed (exit 128): <git's own output>
```

The first is serialkompat's. The second is the prefix serialkompat puts on any failed git
command; the text after the colon is git's.

**Cause:** The baseline is a git ref, checked out in a temporary worktree. A shallow
checkout, such as the default `actions/checkout` with `fetch-depth: 1`, doesn't fetch
the ref (typically `origin/main`) at all, so there is nothing to resolve.

**Fix:** Fetch full history: `fetch-depth: 0` on `actions/checkout` in GitHub Actions,
`GIT_DEPTH: 0` or `git fetch --unshallow` elsewhere. Details are in
[CI setup](ci.md). If the ref has a different name, set `baselineRef` or pass
`-Pserialkompat.ref=<ref>`.

## `jsonInstance` / `cborInstance` fails to load { #instance-fails }

**Symptom:** `serialkompatExtract` fails with:

```text
serialkompat: could not load the configured jsonInstance '<fqn>'. Expected `owner.member` naming a kotlinx.serialization.json.Json reachable on the runtime classpath (an object property or a top-level val). Fix or unset serialkompat.jsonInstance.
```

For CBOR the message names `cborInstance` and `kotlinx.serialization.cbor.Cbor`. Two
related errors:

```text
serialkompat: cborInstance is set but format is JSON. Set format = CBOR, or unset cborInstance.
serialkompat: jsonInstance is set but format is CBOR. Use cborInstance for a CBOR module.
```

**Cause:** The name doesn't resolve to a `Json` (or `Cbor`) value on the module's runtime
classpath. serialkompat fails rather than falling back to the default `Json`, because the
instance's settings decide the verdicts, and checking against the wrong wire contract
would be worse than failing. Usual reasons: a typo, the `object` or file living in a
different module, or the member not being a `Json`.

**Fix:** Point `jsonInstance` at `package.Owner.member`, where the owner is an `object`
(or a file's generated class) and the member is a property holding the instance. Keep the
name in step if you move it. The option is described in
[Configuration](configuration.md#serialkompat-reference).

## Every task was skipped { #all-skipped }

**Symptom:** `serialkompatCheck` and the other tasks show as `SKIPPED` and nothing is
checked. There is no serialkompat message; Gradle prints the skip.

**Cause:** The tasks only run when `types` is non-empty or `discovery` is not
`EXPLICIT` (the `onlyIf` in `SerialkompatPlugin.kt`). Applying the plugin with neither
set is a deliberate no-op.

**Fix:** List the root types in `types`, or turn on a discovery mode. See
[Adopting gradually](adopting.md#discovery-modes). A module with neither will stay
skipped, which is correct for modules that hold no wire models.

## The baseline is empty { #empty-baseline }

**Symptom:** The check fails and the console starts with:

```text
serialkompat: the baseline produced 0 contracts but the current schema has N — refusing to pass (check baselineRef/types, or set failOnEmptyBaseline=false if this is first-time adoption).
```

**Cause:** The baseline ref has no analysable types while the current code does. That
would otherwise diff as "everything added, so safe" and hide any real removal. This is the
`failOnEmptyBaseline` guard, on by default. The common cases are a `baselineRef` that
predates your `@Serializable` types, and a `types` list or discovery config that is wrong
at that ref.

**Fix:** If it's a mistake, fix `baselineRef` or `types`. If you're adopting serialkompat
for types that don't exist at the baseline yet, set `failOnEmptyBaseline.set(false)` for
that one change and remove it afterwards. See
[Quick start](quickstart.md#first-time-adoption). The CLI equivalent is
`--allow-empty-baseline`.

## A `COVERAGE_GAP` warning after upgrading to 0.3.1 { #coverage-gap-0-3-1 }

**Symptom:** After upgrading to 0.3.1, a check against a baseline recorded by 0.3.0 or
earlier reports a `COVERAGE_GAP` `WARN` for fields marked `@Contextual`. There is no
break and the build passes.

**Cause:** 0.3.1 started analysing `@Contextual` fields that earlier versions recorded as
unanalysable. The old baseline has no shape to compare with, so each newly resolved field
gets one `WARN` instead of a false type change or `CONTRACT_REMOVED` (see
[the changelog](changelog.md)).

**Fix:** None is needed. This is expected once per previously-unresolved field. The next
baseline is recorded by the new version and compares fully. If a `@Contextual` field is
still reported after that, it has no module entry and no fallback serializer; see
[`@Contextual` resolution](limitations.md#contextual-resolution).

## The first run is slow { #slow-first-run }

**Symptom:** The first check on a commit takes much longer than later ones. No message.

**Cause:** The baseline is extracted by running a nested Gradle build in a temporary git
worktree at `baselineRef`, which compiles your models at that commit. The result is cached
per module, keyed by commit SHA and serialkompat version.

**Fix:** Nothing to fix; later runs against the same commit and version reuse the cache. In
CI, keep Gradle's own caches (and the build cache, since `serialkompatExtract` is
cacheable) between runs. After a serialkompat upgrade the baseline is re-extracted once.
See [Baseline & caching](baseline-caching.md#caching).

## The sticky comment fails with a 403 { #sticky-comment-403 }

**Symptom:** The Action step that posts the pull-request comment fails with an HTTP 403
from GitHub. The message is GitHub's, not serialkompat's, and the compatibility check
itself isn't the cause.

**Cause:** The calling workflow's token can't write pull-request comments, because the job
lacks the permission.

**Fix:** Add `permissions: pull-requests: write` to the job. See
[CI setup](ci.md) for the Action's requirements and the sticky comment.

## In a multi-module build, one failing module stops the others { #continue }

**Symptom:** With several modules using serialkompat, the build stops at the first
module whose check fails, and the other modules' checks never run, so you see only one
module's findings.

**Cause:** This is Gradle's default, not serialkompat's: when a task fails, Gradle stops
scheduling further tasks unless told otherwise.

**Fix:** Run with `--continue` (for example `./gradlew serialkompatCheck --continue`) to
run every module's check and report all failures at the end. The sticky comment still
shows one module's report; see
[the Action in a multi-module build](monorepos.md#the-action).
