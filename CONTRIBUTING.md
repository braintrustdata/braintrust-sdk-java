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

## Dependency security alerts

`.github/workflows/dependency-submission.yml` submits the shipped dependency graph
on pushes to `main`, or through a manual run on the default branch. It does not
enable Dependabot update PRs.

The inventory includes the SDK's runtime and embedded inputs, the OTel extension's
runtime dependencies, and the Java agent's bootstrap and internal packaging inputs.
The agent's internal module is also scanned directly because its shaded JAR hides
its bundled dependencies from the outer agent's dependency graph.

Only these packaging projects and configurations contribute to the inventory.
Dependencies used solely by tests, examples, build tooling, or compile-only
instrumentation targets are excluded. Transitive dependencies that ship are still
included, and submitted dependencies are marked as runtime. This is a shipped-product
inventory, not a security inventory of everything executed during development or CI.

When changing JAR assembly or adding a published artifact, update the workflow's
project/configuration filters to cover its dependency inputs.

### Switching from automatic dependency submission

1. Merge the workflow to `main` and confirm **Shipped dependency submission** succeeds.
2. Check **Insights → Dependency graph** for the filtered inventory. It should retain
   Jackson, Byte Buddy, and the agent's OTel dependencies, without test-only frameworks.
3. Under **Settings → Advanced Security → Dependency graph**, disable **Automatic
   dependency submission** to stop the redundant, unfiltered submission job. Leave
   the dependency graph and Dependabot alerts enabled. Security-update PRs can remain
   disabled.

The workflow saves its generated JSON snapshot as an Actions artifact for inspection.
GitHub gives explicit workflow submissions precedence over automatic submissions for
the same manifest, so the filtered inventory can be verified before disabling the
automatic job.
