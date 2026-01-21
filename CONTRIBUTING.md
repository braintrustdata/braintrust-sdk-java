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
export BRAINTRUST_API_URL="http://localhost:4318" ; export BRAINTRUST_TRACES_PATH="/v1/traces"; export BRAINTRUST_LOGS_PATH="/v1/logs" ; ./gradlew :examples:runOpenAIInstrumentation
```
