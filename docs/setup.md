# Setup

You can run serialkompat three ways: as a Gradle plugin, as a standalone CLI, or as a
GitHub Action. Most projects start with the Gradle plugin.

!!! note "Install from Maven Central"
    The plugin is published to Maven Central, not the Gradle Plugin Portal. A plain
    `plugins { id("com.chrisjenx.serialkompat") version "..." }` block only resolves
    once you add `mavenCentral()` to `pluginManagement.repositories`, as shown in the
    Gradle plugin tab below.

=== "Gradle plugin"

    ### Gradle plugin {: #gradle-plugin }

    Add Maven Central as a plugin repository:

    ```kotlin title="settings.gradle.kts"
    pluginManagement {
        repositories {
            gradlePluginPortal()
            mavenCentral()
        }
    }
    ```

    Then apply the plugin by id and version, and configure it:

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

    The **baseline** is the schema you compare against. serialkompat doesn't read it
    from a checked-in file. It checks out `baselineRef` in a temporary git worktree and
    runs `serialkompatExtract` there, so the baseline can't go stale. The plugin must
    therefore be applied on `baselineRef` too; see
    [Quick start → Your first run](quickstart.md#2-run-the-check).

    Extraction runs on your project's own runtime classpath, so your models are read
    with the same kotlinx-serialization version they were compiled against.

    #### `serialkompat { }` reference

    The most common options. [Configuration](configuration.md#serialkompat-reference)
    has the full list, including the `history { }` and `reports { }` blocks.

    | Property | Type | Default | Purpose |
    |---|---|---|---|
    | `types` | `ListProperty<String>` | `[]` (required under `EXPLICIT` discovery) | FQNs of `@Serializable` root types to check |
    | `discovery` | `Property<DiscoveryMode>` | `EXPLICIT` | How types are found when `types` is empty: `EXPLICIT`, `OPT_OUT`, or `OPT_IN`. See [Discovery modes](configuration.md#discovery-modes) |
    | `jsonInstance` | `Property<String>` | unset | FQN of a `Json` instance describing the wire; unset = default `Json`. If set but it can't be loaded, extraction fails |
    | `baselineRef` | `Property<String>` | auto-detected | Git ref to check against. Unset ⇒ auto-detect the default branch (`origin/HEAD` → `origin/main` → `origin/master` → local `main`/`master`) |
    | `direction` | `Property<CompatibilityDirection>` | `FULL` | `BACKWARD`, `FORWARD`, or `FULL` |
    | `failOnBreaking` | `Property<Boolean>` | `true` | A `BREAK` finding fails the build |
    | `failOnEmptyBaseline` | `Property<Boolean>` | `true` | An empty baseline fails the build, so removals can't be masked; set `false` for first adoption |
    | `include` | `ListProperty<String>` | `[""]` | Serial-name prefixes in scope (`""` = all) |
    | `exclude` | `ListProperty<String>` | `[]` | Serial-name prefixes excluded |
    | `acceptedBreaks` | `ListProperty<String>` | `[]` | Sanctioned breaks, `"<serialName> <RULE> [DIRECTION]"`. A subtype is `Base/sub` or bare `sub` |
    | `renames` | `MapProperty<String,String>` | `{}` | Declared serial-name moves old→new (avoids a remove + add) |

    If neither `types` nor a non-`EXPLICIT` `discovery` mode is set, every serialkompat
    task is skipped, so applying the plugin never breaks `check` on its own.

    #### Tasks

    | Task | Does |
    |---|---|
    | `serialkompatExtract` | Extracts the current schema to `build/serialkompat/current.snapshot` |
    | `serialkompatCheck` | Extracts and diffs against `baselineRef`; wired into `check` |
    | `serialkompatCheckAgainst` | Same as `serialkompatCheck`, but the ref can be overridden with `-Pserialkompat.ref=<ref>` |
    | `serialkompatRecord` | Records the current schema into the published history (`-Pserialkompat.recordVersion=X.Y.Z`). See [Recipes](recipes.md#persisted-data-horizon-multi-version-history) |
    | `serialkompatCheckHistory` | Checks the current schema against every recorded version; wired into `check` (skipped until a version is recorded) |

    The plugin supports the configuration cache, `--parallel`, and Isolated Projects.
    `serialkompatExtract` is build-cacheable and relocatable. The check tasks are never
    cached, on purpose. See
    [CI setup → Build cache & configuration cache](ci.md#build-cache-configuration-cache).

    See [Quick start](quickstart.md) for running the check and reading a report, and
    [Configuration](configuration.md) for `direction`, `acceptedBreaks`, and `renames` in depth.

=== "CLI"

    ### CLI {: #cli }

    The CLI diffs two snapshot files without a Gradle build in the loop. Use it for
    non-Gradle or cross-repo checks, such as comparing a producer and a consumer
    that live in different repositories.

    The CLI isn't published as a download yet. Build it from a checkout of this
    repository:

    ```console
    $ ./gradlew :serialkompat-cli:installDist
    $ serialkompat-cli/build/install/serialkompat-cli/bin/serialkompat-cli --help
    ```

    The examples on this page call that launcher `serialkompat`; alias or rename it
    as you like.

    ```text
    serialkompat diff <baseline.snapshot> <current.snapshot> [--direction=FULL|BACKWARD|FORWARD] [--format=console|json|sarif|github] [--no-fail] [--allow-empty-baseline]
    ```

    The first file is the old schema and the second is the new one.

    | Flag | Effect |
    |---|---|
    | `--direction=FULL\|BACKWARD\|FORWARD` | Compatibility direction to enforce (default `FULL`) |
    | `--format=console\|json\|sarif\|github` | Output format for the report (default `console`). See [Report formats](report-formats.md) |
    | `--no-fail` | Exit `0` even if the diff finds breaking changes (the report still prints) |
    | `--allow-empty-baseline` | Accept a baseline with no contracts. Use it only for first-time adoption |
    | `--help`, `-h` | Print usage and exit `0` |

    For example, `serialkompat diff a.snapshot b.snapshot --format=sarif > report.sarif`
    writes a SARIF 2.1.0 log. `--format` only changes what is printed, never the exit code.

    The CLI never crashes on bad input. A missing file, an unknown flag, or an invalid
    `--direction` prints `error: <message>` and the usage line, then exits `2`.

    The CLI also fails closed on an empty baseline. If the baseline file has no
    contracts but the current one has some, the diff would read as "everything was
    added" and hide every removal. So the CLI prints an `error:` line, renders no
    report, and exits `2`. `--no-fail` doesn't skip this check. Pass
    `--allow-empty-baseline` when an empty baseline is expected, such as on first-time
    adoption. Two empty snapshots still pass.

    | Exit code | Meaning |
    |---|---|
    | `0` | No breaking findings |
    | `1` | At least one active `BREAK` finding (unless `--no-fail`) |
    | `2` | Usage error, unreadable snapshot, or an empty baseline (unless `--allow-empty-baseline`) |

    #### Producing snapshots

    The CLI only diffs. Extraction needs a compiled classpath, so produce each
    `.snapshot` file with the Gradle plugin's `serialkompatExtract` task: once at your
    baseline, and once at the current commit.

    ```console
    $ ./gradlew serialkompatExtract   # writes build/serialkompat/current.snapshot
    ```

=== "GitHub Action"

    ### GitHub Action {: #github-action }

    The action runs `serialkompatCheckAgainst` (or a task you choose) and posts a sticky
    PR comment with the summary and findings. On pull requests it also adds error
    and warning annotations to the workflow run.

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

    Requires serialkompat 0.1.0 or later. `@v0` is a floating tag that tracks the
    latest stable 0.x release.

    The caller workflow needs two settings:

    - `permissions: pull-requests: write`. Without it, posting the sticky comment
      gets a 403 and the step fails for a reason unrelated to compatibility.
    - `fetch-depth: 0` on `actions/checkout`. The baseline is extracted from a real
      git ref in a temporary worktree, which needs full history, not a shallow clone.

    #### Inputs

    | Input | Default | Purpose |
    |---|---|---|
    | `ref` | `""` (empty) | Baseline git ref to check against, passed as `-Pserialkompat.ref=`. Empty = use the plugin's `baselineRef` (or the auto-detected default branch) |
    | `task` | `serialkompatCheckAgainst` | Gradle task to run |
    | `report-path` | `build/serialkompat/report.json` | Path to the JSON report the sticky comment is built from |
    | `gradle-args` | `""` (empty) | Extra arguments passed to Gradle |

    The action declares no outputs. The job fails when the Gradle task exits
    non-zero. On pull requests, the sticky comment
    (marked `<!-- serialkompat -->` and updated in place on every push) follows the
    check's exit code. It shows ❌ when the check failed, even with no `BREAK` (for
    example an empty baseline). It shows ⚠️ when the check passed with findings,
    including `BREAK`s when `failOnBreaking` is `false`. It shows ✅ when the check
    passed clean. See [CI setup → The sticky comment](ci.md#the-sticky-comment).

    See [CI setup](ci.md) for wiring this into a larger pipeline or other CI systems
    such as GitLab.
