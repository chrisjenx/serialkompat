# Installation

How to add serialkompat to a project: the Gradle plugin, the GitHub Action, or the
standalone CLI. Most projects start with the Gradle plugin and add the Action in CI.

The check compares your schema against a baseline in both directions: backward (new
code reads old data) and forward (old code reads new data). See
[Concepts](concepts.md) for what those mean.

## Gradle plugin { #gradle-plugin }

The plugin is on the
[Gradle Plugin Portal](https://plugins.gradle.org/plugin/com.chrisjenx.serialkompat),
so `gradlePluginPortal()` (Gradle's default plugin repository) is all you need. Apply
it by id and version, and configure it:

```kotlin title="build.gradle.kts"
import com.chrisjenx.serialkompat.core.CompatibilityDirection

plugins {
    kotlin("jvm")
    kotlin("plugin.serialization")
    id("com.chrisjenx.serialkompat") version "{{ skversion }}"
}

serialkompat {
    types.set(listOf("com.example.wire.OrderEvent", "com.example.wire.Payment"))
    jsonInstance.set("com.example.wire.WireJson.instance")
    // baselineRef is optional. Unset, it auto-detects your default branch.
    direction.set(CompatibilityDirection.FULL)
    failOnBreaking.set(true)
    failOnEmptyBaseline.set(true)
}
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
