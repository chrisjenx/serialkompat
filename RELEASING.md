# Releasing serialkompat

Maintainer-facing: how serialkompat is published and released. Releases are the maintainer's call.

## Publishing

`serialkompat-core`, `-extractor`, `-gradle` (with its plugin marker), and `-annotations` publish to **Maven Central** through the [vanniktech `maven-publish`](https://github.com/vanniktech/gradle-maven-publish-plugin) plugin. The Gradle plugin also publishes to the **Gradle Plugin Portal** through [`com.gradle.plugin-publish`](https://plugins.gradle.org/docs/publish-plugin) (final and prerelease versions; the Portal rejects SNAPSHOTs). Credentials live only in repository secrets:

| Repo secret | Maps to |
|---|---|
| `MAVEN_CENTRAL_USERNAME` / `MAVEN_CENTRAL_PASSWORD` | `ORG_GRADLE_PROJECT_mavenCentralUsername` / `…mavenCentralPassword` |
| `SIGNING_KEY_ID` / `SIGNING_KEY` / `SIGNING_KEY_PASSWORD` | `ORG_GRADLE_PROJECT_signingInMemoryKeyId` / `…signingInMemoryKey` / `…signingInMemoryKeyPassword` |
| `GRADLE_PUBLISH_KEY` / `GRADLE_PUBLISH_SECRET` | the env vars of the same name (locally: `gradle.publish.key` / `gradle.publish.secret` in `~/.gradle/gradle.properties`) |

To check the Portal metadata without publishing: `./gradlew :serialkompat-gradle:publishPlugins --validate-only -Pversion=X.Y.Z`.

## Release

First, in a PR, rename `## [Unreleased]` in `CHANGELOG.md` to `## [X.Y.Z] - YYYY-MM-DD` and add a fresh empty `## [Unreleased]` above it. Then dispatch the `Release` workflow from `main` with the version: `X.Y.Z`, or `X.Y.Z-suffix` for a prerelease. It runs these jobs in order:

1. **Validate.** Fails before anything ships if the run isn't on `main`, any of the seven secrets is missing, the version is malformed, tag `vX.Y.Z` already exists, or (for a final release) `CHANGELOG.md` has no `## [X.Y.Z]` section.
2. **Test.** `./gradlew build` on JDK 17 and 21, on macOS so the KMP klibs are complete.
3. **Publish.** `publishAndReleaseToMavenCentral`, then `:serialkompat-gradle:publishPlugins` to the Plugin Portal in a separate job. A Portal failure doesn't stop tagging; re-run that job once the cause is fixed.
4. **Tag and release.** Tags `vX.Y.Z` on the tested commit and creates the GitHub release (notes: that version's CHANGELOG section, then GitHub's generated PR list), marked as a prerelease if the version has a suffix. Stable releases also move the floating major tag that Action users pin (`v0` for 0.x, `v1` for 1.x).
5. **Bump.** Opens a PR moving `gradle.properties` to the next `-SNAPSHOT` (the next patch after a final release, or the same version after a prerelease: `1.0.0-rc1` → `1.0.0-SNAPSHOT`) and, for a final release, the README install snippet to `X.Y.Z`, because `main` is branch-protected. This needs **Settings → Actions → General → "Allow GitHub Actions to create and approve pull requests"**. Without it the release still completes, and the job warns you to bump the version by hand.

Tagging, the GitHub release, and the bump PR all use the workflow's own `GITHUB_TOKEN`.

Afterwards, merge the bump PR (its CI is dispatched by the workflow). Its merge redeploys the docs site, whose `{{ skversion }}` comes from the newest `vX.Y.Z` tag. Then verify with `gh release view vX.Y.Z`, the artifacts on Maven Central (can take ~30 min to appear), and the version on [plugins.gradle.org](https://plugins.gradle.org/plugin/com.chrisjenx.serialkompat).

Semver while 0.x: a breaking change to the DSL, snapshot format or public API bumps the minor.

## Snapshot

Pushes to `main` that touch module sources or build files publish a `-SNAPSHOT`. The `Snapshot Publish` workflow refuses to run if `gradle.properties` holds a non-SNAPSHOT version.

Locally, `./gradlew publishToMavenLocal` publishes to `~/.m2`. Signing uses your `signing.*` Gradle properties.

## GitHub Actions Marketplace

`action.yml` already carries the Marketplace metadata (name, description, `branding`). Listing it is a **one-time manual step** after the first stable release, because GitHub can't automate the Marketplace toggle:

1. Cut a stable release with the **Release** workflow. This creates the `vX.Y.Z` release and moves the major tag.
2. On that release's page (**Releases → Edit**), tick **"Publish this Action to the GitHub Marketplace"** and accept the Marketplace Developer Agreement (repo owner, first time only).
3. Pick the primary and secondary categories, then save.

Listing is for discoverability only. `uses: chrisjenx/serialkompat@v0` works as soon as the `v0` tag exists.
