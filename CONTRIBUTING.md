# SDK Developer Documentation

This file documents developing the SDK itself. If you simply wish to use the SDK or run examples, see [README.md](./README.md)

Because the SDK is new and under active development, third-party contribution best-practices are still being established. If you wish to contribute please open a github issue explaining what you'd like to achieve and a developer will follow-up with you.

## Setup

- Install JDK 17
  - Recommended to use SDK Man: https://sdkman.io/ and `sdk use java 17.0.16-tem`
- Ensure you can run all tests and checks: `./gradlew check build`
- IDE Setup
  - Intellij Community
    - Ubuntu: `sudo snap install intellij-idea-community`
    - Other: https://www.jetbrains.com/idea/download/
- (Optional) Install pre-commit hooks: `./gradlew installGitHooks`
  - These hooks automatically run common checks for you but CI also runs the same checks before merging to the main branch is allowed
  - NOTE: this will overwrite existing hooks. Take backups before running

## Releasing

Releases are driven end-to-end from a single GitHub Actions workflow. You do not need to tag locally or push tags from your machine.

To cut a release:

1. Make sure everything you want included is merged to `main` and CI is green.
2. Go to **Actions → Release → Run workflow**.
3. Enter:
   - `version`: the release version as `vX.Y.Z` (semver, no `-SNAPSHOT`).
   - `sha`: the **full 40-character commit SHA** on `main` you want to release. Copy it from the commit page on GitHub using "Copy full SHA". A branch name is intentionally not accepted — pinning to a SHA prevents commits that land on `main` during the approval gate from sneaking into the release.
4. The job runs in the protected `release` GitHub Environment and will pause for **required-reviewer approval** before doing anything. Approve from the workflow run page (or the repo's Deployments tab).
5. Once approved, the `Release` workflow will, in one job:
   - Validate the version and the SHA, and verify the SHA is reachable from `origin/main`.
   - Check out the pinned SHA and run `./gradlew check`.
   - Create and push the annotated tag `vX.Y.Z` pointing at the SHA (using the default `GITHUB_TOKEN` — no separate bot identity is needed since the publish steps are in the same workflow).
   - Check out the tag, re-run `./gradlew check`, and build release artifacts.
   - Create the GitHub Release with the SDK, agent, and OTel extension jars attached.
   - Publish to Maven Central via Sonatype, signed with the project GPG key.
   - Poll Maven Central until the new version is visible (this can take many hours).

The Sonatype and GPG signing secrets (`SONATYPE_USERNAME`, `SONATYPE_PASSWORD`, `GPG_SIGNING_KEY`, `GPG_SIGNING_PASSWORD`) are scoped to the `release` environment.

The SDK version is computed from git tags at build time (see `generateVersion()` in `build.gradle`) and embedded into `braintrust.properties`, so there are no version constants to bump in source.

### Re-publishing a failed release

If the workflow fails partway through, re-run **Release** with the same version. The workflow detects that the tag already exists, skips tag creation, and resumes from the build/publish steps against the existing tag. GitHub Release asset uploads use `--clobber` so partial uploads from a prior run are replaced.

### Local fallback

`scripts/release.sh` can still create and push a tag from a clean local checkout if the Actions-driven flow is unavailable. Prefer the workflow.

## Misc Tips

### Running a local OpenTelemetry collector

OpenTelemetry provides a local collector with a debug exporter which logs all traces, logs, and metrics to stdout.

To run a local collector:

```
# Assumes you're in the repo root
docker run --rm -p 4318:4318 -v "$PWD/localcollector/collector.yaml:/etc/otelcol/config.yaml" otel/opentelemetry-collector:0.136.0 # latest release will probably also work
```

To send Braintrust otel data to the local collector:

```
# assumes you have BRAINTRUST_API_KEY and OPENAI_API_KEY exported
export BRAINTRUST_API_URL="http://localhost:4318" ; export BRAINTRUST_TRACES_PATH="/v1/traces"; export BRAINTRUST_LOGS_PATH="/v1/logs" ; ./gradlew :examples:openai-instrumentation:run
```
