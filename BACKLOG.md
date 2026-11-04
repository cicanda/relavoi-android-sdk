# Backlog

Work that was found, understood and deliberately not done, with enough detail
that picking it up does not mean rediscovering it. Dated entries, newest first.
Delete an entry when it ships; do not leave it here marked done.

## 2026-11-02

### ktlint in CI

CI builds the release AAR, runs the SDK unit tests and builds the sample app.
Nothing checks Kotlin style or catches the class of problem a linter does, so
this repo is the one SDK with a build gate and no lint gate. The other repos
got ESLint today and this is the Kotlin equivalent.

How to pick it up:

1. Add the plugin to `build.gradle.kts`, where versions live:
   `id("org.jlleitschuh.gradle.ktlint") version "12.1.1" apply false`, then
   apply it in `relavoi-sdk/build.gradle.kts` and `sample-app/build.gradle.kts`.
2. Run `./gradlew ktlintCheck` locally first and see the count. This code has
   never been linted, so expect a batch of findings on first run, most of them
   import order and trailing commas, which `./gradlew ktlintFormat` fixes
   mechanically. Commit that formatting pass on its own, so the commit that
   adds the gate is not also a 40-file reformat.
3. Add a `ktlint` step to `.github/workflows/ci.yml` before the build steps.
   No `continue-on-error`: errors fail the build. ktlint has no warning tier,
   so anything it reports is fatal, which is why the formatting pass has to
   land first.

Worth doing when this SDK is next touched. Note the Kotlin version here is
1.9.24, so pin a ktlint plugin release that supports it rather than taking the
newest.

### swiftlint equivalent

Tracked in the iOS SDK's own BACKLOG.md. Same gap, same reasoning.

## No release-version check

The iOS and Flutter SDKs both have `scripts/check-release-version.sh`, run by
CI on a tag push, which fails the release if the tree disagrees with the tag.
They have it because v0.3.2 of the iOS SDK was tagged with a README still
installing 0.3.1, the release whose `create()` crashed, and force-moving a
published tag made SwiftPM refuse to resolve it.

This repository has the same two places to drift, `RELAVOI_VERSION` in
gradle.properties and the install snippet in the README, and nothing that
checks them. 0.4.0 was bumped by hand.
