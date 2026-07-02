# Setup

Install serialkompat as a Gradle plugin, a standalone CLI, or a GitHub Action.

!!! warning "Not yet on the Gradle Plugin Portal"
    serialkompat is `0.1.0-SNAPSHOT`, published to Maven Central (snapshots auto-publish
    on push to `main`) but **not yet** on the Gradle Plugin Portal. A plain
    `plugins { id("com.chrisjenx.serialkompat") version "..." }` block will not resolve
    until it is. Until then, add `mavenCentral()` to `pluginManagement.repositories` (Gradle
    plugin tab below) — this is not a hypothetical workaround, it's the only path that
    currently resolves.

=== "Gradle plugin"

    ### Gradle plugin {: #gradle-plugin }

    Add Maven Central as a plugin repository, then apply the plugin by id and version:

    ```kotlin title="settings.gradle.kts"
    pluginManagement {
        repositories {
            gradlePluginPortal()
            mavenCentral()
        }
    }
    ```

    ```kotlin title="build.gradle.kts"
    plugins {
        kotlin("jvm")
        kotlin("plugin.serialization")
        id("com.chrisjenx.serialkompat") version "{{ skversion }}"
    }

    serialkompat {
        types.set(listOf("com.example.wire.OrderEvent", "com.example.wire.Payment"))
        jsonInstance.set("com.example.wire.WireJson.instance")
        baselineRef.set("origin/main")
        direction.set(CompatibilityDirection.FULL)
        failOnBreaking.set(true)
        failOnEmptyBaseline.set(true)
    }
    ```

    The extension is config-cache safe — everything is captured at configuration time,
    and the baseline is recomputed live from `baselineRef` via a temporary git worktree
    (no baseline file to check in or go stale).

    #### `serialkompat { }` reference

    | Property | Type | Default | Purpose |
    |---|---|---|---|
    | `types` | `ListProperty<String>` | — (required) | FQNs of `@Serializable` root types to check |
    | `jsonInstance` | `Property<String>` | empty | FQN of a `Json` instance describing the wire; empty = default `Json{}` |
    | `baselineRef` | `Property<String>` | `"origin/main"` | Git ref the current schema is checked against |
    | `direction` | `Property<CompatibilityDirection>` | `FULL` | `BACKWARD`, `FORWARD`, or `FULL` |
    | `failOnBreaking` | `Property<Boolean>` | `true` | A `BREAK` finding fails the build |
    | `failOnEmptyBaseline` | `Property<Boolean>` | `true` | Empty baseline fails the build (prevents masking removals); set `false` for first adoption |
    | `include` | `ListProperty<String>` | `[""]` | Serial-name prefixes in scope (`""` = all) |
    | `exclude` | `ListProperty<String>` | `[]` | Serial-name prefixes excluded |
    | `acceptedBreaks` | `ListProperty<String>` | `[]` | Sanctioned breaks, `"<serialName> <RULE> [DIRECTION]"` |
    | `renames` | `MapProperty<String,String>` | `{}` | Declared serial-name moves old→new (avoids remove+add) |

    #### Tasks

    | Task | Does |
    |---|---|
    | `serialkompatExtract` | Extracts the current schema to `build/serialkompat/current.snapshot` |
    | `serialkompatCheck` | Extracts + diffs against `baselineRef`; wired into `check` |
    | `serialkompatCheckAgainst` | Same as `serialkompatCheck`; ref overridable via `-Pserialkompat.ref=<ref>` |

    See [Quick start](quickstart.md) for running the check and reading a report, and
    [Configuration](configuration.md) for `direction`, `acceptedBreaks`, and `renames` in depth.

=== "CLI"

    ### CLI {: #cli }

    A standalone binary for non-Gradle or cross-repo use: diff two snapshot files
    produced by `serialkompatExtract` (in different repos, or different checkouts of
    the same repo) without a Gradle build in the loop.

    ```text
    serialkompat diff <baseline.snapshot> <current.snapshot> [--direction=FULL|BACKWARD|FORWARD] [--no-fail]
    ```

    | Flag | Effect |
    |---|---|
    | `--direction=FULL\|BACKWARD\|FORWARD` | Compatibility direction to enforce (default `FULL`) |
    | `--no-fail` | Exit `0` even if the diff finds breaking changes (report still prints) |
    | `--help`, `-h` | Print usage and exit `0` |

    Malformed input never crashes the CLI — a missing file, unknown flag, or invalid
    `--direction` prints `error: <message>` plus usage and exits `2`, never a stack trace.

    | Exit code | Meaning |
    |---|---|
    | `0` | No breaking findings |
    | `1` | At least one active `BREAK` finding (unless `--no-fail`) |
    | `2` | Usage error |

    #### Producing snapshots

    The CLI only diffs — it doesn't extract (extraction needs a compiled classpath).
    Produce the two `.snapshot` files with the Gradle plugin's `serialkompatExtract`
    task, one per side of the comparison (e.g. run it at your baseline ref, then again
    at the current commit):

    ```console
    $ ./gradlew serialkompatExtract   # writes build/serialkompat/current.snapshot
    ```

=== "GitHub Action"

    ### GitHub Action {: #github-action }

    Runs `serialkompatCheck` (or a task of your choice) and posts a sticky PR comment
    with the summary and findings.

    ```yaml title=".github/workflows/serialkompat.yml"
    name: serialkompat
    on: [pull_request, push]
    jobs:
      serialkompat:
        runs-on: ubuntu-latest
        permissions:
          pull-requests: write
        steps:
          - uses: actions/checkout@v5
            with: { fetch-depth: 0 }
          - uses: actions/setup-java@v5
            with: { distribution: temurin, java-version: "17" }
          - uses: chrisjenx/serialkompat@v1
            with:
              ref: origin/main
    ```

    Two settings on the **caller** workflow are required, not optional:

    - `permissions: pull-requests: write` — without it, posting the sticky comment
      gets a 403 and the step fails for a reason unrelated to compatibility.
    - `fetch-depth: 0` on `actions/checkout` — the baseline is extracted from a real
      git ref via a temporary worktree, which needs full history, not a shallow clone.

    #### Inputs

    | Input | Default | Purpose |
    |---|---|---|
    | `ref` | `""` (empty) | Baseline git ref to check against; passed as `-Pserialkompat.ref=`. Empty = use the plugin's configured `baselineRef` |
    | `task` | `serialkompatCheckAgainst` | Gradle task to run |
    | `report-path` | `build/serialkompat/report.json` | Path to the JSON report the sticky comment is built from |
    | `gradle-args` | `""` (empty) | Extra arguments passed to Gradle |

    #### Outputs

    | Output | Value |
    |---|---|
    | `exit_code` | The Gradle task's exit code |

    The workflow fails if `exit_code != 0`. The sticky comment (marked
    `<!-- serialkompat -->`, updated in place across pushes) shows ❌ on a failing
    check, ⚠️ on warnings only, ✅ otherwise.

    See [CI setup](ci.md) for wiring this into a larger pipeline (GitLab, other
    runners) and for what the exit-code contract guarantees.
