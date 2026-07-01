<!-- Thanks for contributing! Keep PRs small and focused. -->

## What & why

<!-- What does this change do, and why? Link the issue it addresses. -->

Closes #

## How it was tested

<!-- serialkompat is test-first. Describe the RED test(s) you wrote and how they now pass.
     For rule changes, note the round-trip oracle case that backs it. -->

## Checklist

- [ ] Change is driven by tests (RED → GREEN)
- [ ] `./gradlew spotlessApply && ./gradlew build` is green locally
- [ ] Public API changes are reflected via `./gradlew apiDump` (committed `*.api`)
- [ ] Docs / KDoc / CHANGELOG updated if behaviour or public API changed
- [ ] PR is a single logical change and up to date with `main`
