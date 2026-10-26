# Installation

How to add serialkompat to a project: the Gradle plugin, the GitHub Action, or the
standalone CLI. Most projects start with the Gradle plugin and add the Action in CI.

The check compares your schema against a baseline in both directions: [backward (new
code reads old data)](concepts.md#backward-vs-forward) and [forward (old code reads new
data)](concepts.md#backward-vs-forward).

## Gradle plugin { #gradle-plugin }

The plugin is on the
[Gradle Plugin Portal](https://plugins.gradle.org/plugin/com.chrisjenx.serialkompat),
so `gradlePluginPortal()` (Gradle's default plugin repository) is all you need. Apply
it by id and version, and configure it:

```kotlin title="build.gradle.kts"
--8<-- "docs-samples/snippets/installation.gradle.kts:imports"

plugins {
    kotlin("jvm")
    kotlin("plugin.serialization")
    id("com.chrisjenx.serialkompat") version "{{ skversion }}"
}

--8<-- "docs-samples/snippets/installation.gradle.kts:config"
```

The [baseline](concepts.md#the-baseline) isn't read from a checked-in file. serialkompat
checks out `baselineRef` in a temporary git worktree and runs `serialkompatExtract`
there, so the baseline can't go stale. The plugin must therefore be applied on
`baselineRef` too; see [Quick start → Your first PR](quickstart.md#first-time-adoption).

Extraction runs on your project's own runtime classpath, so your models are read with
the same kotlinx-serialization version they were compiled against.

<div class="grid cards" markdown>

- **Every option**

    [Configuration](configuration.md#serialkompat-reference) lists the full
    `serialkompat { }` DSL, including `history { }` and `reports { }`.

- **Every task**

    [Tasks & CLI](tasks-cli.md#tasks) lists what each Gradle task does and when it runs.

</div>

## GitHub Action { #github-action }

The Action runs `serialkompatCheckAgainst` and posts a sticky comment on the pull
request, with annotations on the workflow run. A minimal workflow:

```yaml title=".github/workflows/serialkompat.yml"
name: serialkompat
on: [pull_request, push]
jobs:
  serialkompat:
    runs-on: ubuntu-latest
    permissions:
      pull-requests: write
    steps:
      - uses: actions/checkout@v7
        with: { fetch-depth: 0 }
      - uses: actions/setup-java@v6
        with: { distribution: temurin, java-version: "17" }
      - uses: chrisjenx/serialkompat@v0
        with:
          ref: origin/main
```

Keep `fetch-depth: 0` and `pull-requests: write`: the baseline needs full git history,
and the sticky comment needs the permission. [CI setup](ci.md) has the inputs, the
sticky comment, versioning (`@v0`) and other CI systems such as GitLab.

## Multi-module builds { #multi-module }

Apply the plugin in each module that holds `@Serializable` wire models, and configure
`types` (or `discovery`) per module. There is no repo-wide config: each module has its own
`types`, `baselineRef` and scope. Modules that hold no wire models can leave the plugin
off. [Monorepos](monorepos.md#many-modules) covers scoping and `--parallel`.

Two things to know:

- Gradle stops at the first failing module unless you pass `--continue`. See
  [Troubleshooting](troubleshooting.md#continue).
- The GitHub Action's sticky comment covers one module's report. See
  [the Action in a multi-module build](monorepos.md#the-action) and
  [the sticky comment](ci.md#the-sticky-comment).

## Kotlin Multiplatform & Android { #kmp-android }

Extraction reads compiled JVM descriptors on a JVM runtime classpath, so the module must
produce JVM classes.

- **Kotlin/JVM modules:** supported. The plugin reads the module's `runtimeClasspath`.
- **Kotlin Multiplatform with a `jvm()` target:** supported. The plugin reads
  `jvmRuntimeClasspath` and the `jvm()` target's compiled classes. Models in `commonMain`
  are checked because they are compiled into that target. A functional test covers
  `OPT_OUT` discovery of `commonMain` models in such a module.
- **KMP with no `jvm()` target** (for example JS-only or native-only): the plugin stays a
  safe no-op and doesn't break `check`, but it checks nothing there.
- **Android modules** (`com.android.library`, `com.android.application`): not supported
  directly. The plugin has no Android-specific handling: it looks up only
  `runtimeClasspath` and `jvmRuntimeClasspath`, and it isn't tested on Android. Put the
  wire models in a JVM or KMP-`jvm()` module and apply the plugin there; the Android module
  can depend on that module like any other.

## CLI { #cli }

The CLI diffs two snapshot files without a Gradle build in the loop. Use it for
non-Gradle or cross-repo checks, such as comparing a producer and a consumer that live
in different repositories.

The CLI isn't published as a download yet. Build it from a checkout of this
repository:

```console
$ ./gradlew :serialkompat-cli:installDist
$ serialkompat-cli/build/install/serialkompat-cli/bin/serialkompat-cli --help
```

The docs call that launcher `serialkompat`; alias or rename it as you like.

### Producing snapshots { #producing-snapshots }

The CLI only diffs. Extraction needs a compiled classpath, so produce each `.snapshot`
file with the Gradle plugin's `serialkompatExtract` task: once at your baseline, and
once at the current commit.

```console
$ ./gradlew serialkompatExtract   # writes build/serialkompat/current.snapshot
$ serialkompat diff baseline.snapshot current.snapshot
```

For flags and exit codes, see [Tasks & CLI](tasks-cli.md#cli). For a worked
producer/consumer example, see [Cross-repo with the CLI](cross-repo.md).
