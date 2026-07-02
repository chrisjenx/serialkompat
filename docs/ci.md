# CI setup

Every path into serialkompat ends at the same contract: an exit code. `0` pass,
`1` breaking, `2` usage error. The GitHub Action wraps that contract with a sticky
PR comment; anything else — GitLab, Jenkins, Buildkite — just needs to run a Gradle
task and check its exit code.

=== "GitHub Actions"

    ### GitHub Actions {: #github-actions }

    Recommended for GitHub repos. Runs a Gradle task and posts a sticky PR comment
    with the summary and findings — no report parsing to write yourself.

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
    - `fetch-depth: 0` on `actions/checkout` — the baseline isn't a file, it's
      extracted live from `ref` via a temporary git worktree. A shallow clone
      doesn't have the history to check that ref out.

    #### Inputs

    | Input | Default | Purpose |
    |---|---|---|
    | `ref` | `""` (empty) | Baseline git ref to check against; passed as `-Pserialkompat.ref=`. Empty = use the plugin's configured `baselineRef` |
    | `task` | `serialkompatCheckAgainst` | Gradle task to run |
    | `report-path` | `build/serialkompat/report.json` | Path to the JSON report the sticky comment is built from |
    | `gradle-args` | `""` (empty) | Extra arguments passed to Gradle |

    #### Output

    | Output | Value |
    |---|---|
    | `exit_code` | The Gradle task's exit code |

    The workflow step fails whenever `exit_code != 0`. The sticky comment (marked
    `<!-- serialkompat -->`, updated in place across pushes rather than duplicated)
    runs on `pull_request`, `always()` — it posts even if the check itself failed —
    and shows:

    - ❌ at least one active `BREAK` finding
    - ⚠️ `WARN` findings only, nothing breaking
    - ✅ clean

=== "Manual Gradle"

    ### Manual Gradle (any CI) {: #manual-gradle }

    No Action available for your runner? Run the task directly and branch on the
    exit code — this is the same mechanism the GitHub Action uses internally.

    ```console
    $ ./gradlew serialkompatCheckAgainst -Pserialkompat.ref=origin/main
    ```

    `serialkompatCheckAgainst` accepts `-Pserialkompat.ref=<ref>` to override the
    baseline per-invocation (e.g. the PR's target branch), without editing
    `build.gradle.kts`. Omit the property and it falls back to the plugin's
    configured `baselineRef` — or use plain `serialkompatCheck`, which always uses
    the configured `baselineRef` and is already wired into `check`.

    #### The exit-code contract

    Every integration — Action, plain Gradle, the CLI — resolves to this:

    | Code | Meaning |
    |---|---|
    | `0` | No breaking findings (there may still be `WARN`s) |
    | `1` | At least one active `BREAK` finding |
    | `2` | Usage error (bad config, unreadable snapshot, etc.) |

    Same rule as the Action: the checkout needs full history (`fetch-depth: 0` on
    GitHub Actions, `GIT_DEPTH: 0`/`--unshallow` elsewhere), because the baseline is
    recomputed live from the ref via a temporary git worktree, not read from a
    committed file.

=== "GitLab CI"

    ### GitLab CI {: #gitlab-ci }

    A minimal job: full-history checkout, JDK, run the check, let the exit code
    decide the job's pass/fail.

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

    1. Disables GitLab's default shallow clone. Same reason as `fetch-depth: 0`
       on GitHub Actions — the baseline is checked out from `ref` via a git
       worktree at run time, so the runner's clone needs full history to reach it.

    GitLab surfaces the job's exit code directly (no extra `if` needed): `0`
    passes the job, `1` or `2` fails it. There's no built-in sticky-comment
    equivalent — parse `build/serialkompat/report.json` yourself and post via the
    GitLab MR notes API if you want inline findings.

## Next

- [Configuration](configuration.md) — the full `serialkompat { }` DSL, direction, `acceptedBreaks`.
- [Rules](rules.md) — every rule the classifier can raise.
