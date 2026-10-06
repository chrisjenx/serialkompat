# CI setup

serialkompat fails your CI job when a change would break compatibility with the
baseline. Every integration comes down to an exit code: zero means pass, non-zero
means fail. The GitHub Action adds a sticky PR comment on top. On any other CI
(GitLab, Jenkins, Buildkite) you run a Gradle task and let its exit code decide.

=== "GitHub Actions"

    ### GitHub Actions {: #github-actions }

    Use this for GitHub repos. The Action runs a Gradle task and posts a sticky PR
    comment with the summary and findings, so you don't parse any report yourself.

    !!! note
        Requires serialkompat 0.1.0 or later.

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

    `@v0` is a floating tag. Each stable release moves `v<major>` to the newest
    release with that major version.

    The **calling** workflow needs two settings:

    - `permissions: pull-requests: write`. Without it, posting the sticky comment
      gets a 403 and the step fails for a reason unrelated to compatibility.
    - `fetch-depth: 0` on `actions/checkout`. The baseline isn't a committed file.
      serialkompat checks `ref` out in a temporary git worktree and extracts it, and
      a shallow clone doesn't have the history to do that.

    #### Inputs

    | Input | Default | Purpose |
    |---|---|---|
    | `ref` | `""` (empty) | Baseline git ref to check against; passed as `-Pserialkompat.ref=`. Empty = use the plugin's configured `baselineRef` (or its auto-detected default branch) |
    | `task` | `serialkompatCheckAgainst` | Gradle task to run |
    | `report-path` | `build/serialkompat/report.json` | Path to the JSON report the sticky comment is built from |
    | `gradle-args` | `""` (empty) | Extra arguments passed to Gradle |

    #### Outputs

    The Action declares no outputs. If the Gradle task exits non-zero, the Action's
    last step fails, and so does your job.

    #### The sticky comment

    The comment runs on `pull_request` events only. It posts even when the check
    failed. It is marked `<!-- serialkompat -->` and updated in place on each push,
    not duplicated. Its icon shows:

    - ❌ at least one active `BREAK` finding
    - ⚠️ `WARN` findings only, nothing breaking
    - ✅ clean

    The comment is built from the JSON report. If you turn that report off
    (`reports { json { required.set(false) } }`) or move it, set `report-path` to
    match. Otherwise the comment says no report was produced.

    The default path is relative to the repository root. If you apply the plugin
    to a subproject (say `:shared:api`), point `report-path` at that module's
    report: `shared/api/build/serialkompat/report.json`. The comment covers one
    report, so in a multi-module build pick the module that matters most.

    On pull requests, the Action also posts an **annotation** for each active finding: `BREAK`
    becomes an error and `WARN` a warning. It posts at most 10 errors and 10
    warnings, plus one notice that counts any overflow, so nothing is dropped
    silently. Findings have no source line, so the annotations attach to the run and
    the job summary. See [Report formats](report-formats.md#github-annotations).

=== "Manual Gradle"

    ### Manual Gradle (any CI) {: #manual-gradle }

    No Action for your runner? Run the task directly and let the exit code decide.
    The GitHub Action does the same thing internally.

    ```console
    $ ./gradlew serialkompatCheckAgainst -Pserialkompat.ref=origin/main
    ```

    `-Pserialkompat.ref=<ref>` sets the baseline for one run, for example to the
    PR's target branch, without editing `build.gradle.kts`. Leave it out and
    `serialkompatCheckAgainst` uses the configured `baselineRef`.

    You can also run plain `serialkompatCheck`. It is already wired into `check`,
    always uses the configured `baselineRef`, and ignores `-Pserialkompat.ref`.

    #### The exit-code contract

    | Integration | Pass | Fail |
    |---|---|---|
    | Gradle tasks (and the Action) | `0` | Non-zero. Gradle exits `1` for an active `BREAK` and also for a misconfiguration or any other build failure |
    | CLI (`serialkompat diff`) | `0` | `1` = at least one active `BREAK` finding; `2` = usage error (bad arguments, unreadable snapshot) |

    `0` can still come with `WARN` findings.

    As with the Action, the checkout needs full history: `fetch-depth: 0` on
    GitHub Actions, `GIT_DEPTH: 0` or `git fetch --unshallow` elsewhere.
    serialkompat rebuilds the baseline from the ref in a temporary git worktree. It
    doesn't read it from a committed file.

=== "GitLab CI"

    ### GitLab CI {: #gitlab-ci }

    A minimal job checks out full history, sets up a JDK, and runs the check. The
    exit code decides whether the job passes.

    ```yaml title=".gitlab-ci.yml"
    serialkompat:
      image: eclipse-temurin:17-jdk
      variables:
        GIT_DEPTH: 0 # (1)!
      script:
        - ./gradlew serialkompatCheckAgainst -Pserialkompat.ref=origin/main
      rules:
        - if: $CI_PIPELINE_SOURCE == "merge_request_event"
        - if: $CI_COMMIT_BRANCH == $CI_DEFAULT_BRANCH
    ```

    1. Turns off GitLab's default shallow clone. This is the same reason as
       `fetch-depth: 0` on GitHub Actions: the baseline is checked out from `ref`
       at run time, so the clone needs full history.

    GitLab uses the job's exit code directly, so you don't need an extra `if`.
    `0` passes the job and anything else fails it. There is no built-in sticky
    comment. To show findings on a merge request, parse
    `build/serialkompat/report.json` and post it through the GitLab MR notes API.

## Build cache & configuration cache

- **`serialkompatExtract` is cacheable and relocatable.** Its cache key is the
  module's classpath plus the `types`, `discovery`, and `jsonInstance` settings.
  It contains no absolute paths. With a shared (for example remote) build cache, the
  baseline extraction for a commit CI has already built is restored `FROM-CACHE`
  instead of starting a new JVM, even though it runs in a git worktree at a
  different path.
- **The check tasks are never cached or up-to-date, on purpose.** Their result
  depends on what the baseline ref points at *now*, which isn't a task input. The
  expensive part, the baseline snapshot, is still reused: it is stored per commit
  SHA in `build/serialkompat/baseline/` and benefits from the extract cache above.
- **Parallel multi-module builds are safe.** Under `--parallel`, modules take turns
  extracting their baselines through one shared build service. That avoids
  competing git worktree operations and many nested Gradle builds starting at once.
- All tasks support the configuration cache and Gradle's Isolated Projects mode.

## Next

- [Configuration](configuration.md) — the full `serialkompat { }` DSL, direction, `acceptedBreaks`.
- [Rules](rules.md) — every rule the classifier can raise.
- [Report formats](report-formats.md) — JSON schema, SARIF, and the inline annotations.
