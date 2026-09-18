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

### Instrumentation origin versions

Instrumentation tests compare exported `span_origin.instrumentation.version` against the
minimum passing version declared in that module's `muzzle` configuration. Gradle supplies
the expected version through the test JVM property `braintrust.muzzle.minimumVersion` and
tracks it as a test input. No Maven version lookup or full muzzle run is needed.

The minimum comes from inclusive range lower bounds or pinned versions, ignoring `fail`
directives. Multiple passing directives for the same artifact use their lowest version;
different artifacts in one module must agree. Unbounded, exclusive, or skipped lower bounds
are rejected rather than guessing a supported version.

When changing a module's minimum supported version, update both its muzzle configuration
and its Java `INSTRUMENTATION_VERSION` constant. Run the module's Gradle `test` task to
check that the emitted origin matches.
