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

## Development

See [AGENTS.md](./AGENTS.md) for best practices developing, testing, and releasing the SDK.

### CodeQL result caching

CI installs the recommended CodeQL bundle before looking up cached SARIF results.
The exact-match key covers the Git source tree (not commit history), build/workflow
configuration, actual CodeQL version and bundled queries, resolved external Gradle
artifacts, Java toolchains, runner environment, and fetched OpenAPI spec. Identical
trees can reuse results after squashing; changes to any fingerprinted input cause
a fresh scan. There is no time-based expiry in the key.

On a miss, CI runs the same full-repository `compileJava` analysis as the local
scanner, not a PR-diff-filtered analysis. Compilation runs offline with task-output
caching disabled, using the dependencies and spec resolved before cache lookup.
Successful reports are uploaded for the current commit/ref on both hits and
misses, including reports with findings. Failed scans are never saved. PR cache
writes are scoped to that PR; successful default-branch reports can be shared.

`codeqlCacheKey` requires `CODEQL_PATH` and `--refresh-dependencies`; CI also passes
`--no-configuration-cache --no-daemon --no-build-cache --rerun-tasks`. It writes
`build/codeql/cache-inputs.json` for diagnosing misses and
`build/codeql/spec-input/openapi/spec.yaml` for the offline scan. Input-resolution
failures fail the job rather than fall back to stale results. Cached scans require
the bundled query packs and reproducible dependency versions.

`./gradlew checkCodeQL` still performs a fresh local scan and fails on findings.
Run cache-key regressions with
`./gradlew -p buildSrc test --tests dev.braintrust.gradle.CodeqlCacheKeyTest`.
