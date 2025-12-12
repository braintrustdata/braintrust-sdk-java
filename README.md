# Braintrust Java Tracing & Eval SDK

[![javadoc](https://javadoc.io/badge2/dev.braintrust/braintrust-sdk-java/javadoc.svg)](https://javadoc.io/doc/dev.braintrust/braintrust-sdk-java)
[![CI](https://github.com/braintrustdata/braintrust-sdk-java/actions/workflows/ci.yml/badge.svg)](https://github.com/braintrustdata/braintrust-sdk-java/actions/workflows/ci.yml)

## Overview

This library provides tools for **evaluating** and **tracing** AI applications in [Braintrust](https://www.braintrust.dev). Use it to:

- **Evaluate** your AI models with custom test cases and scoring functions
- **Trace** LLM calls and monitor AI application performance with OpenTelemetry
- **Integrate** seamlessly with OpenAI, Anthropic, and other LLM providers

This SDK is currently in BETA status and APIs may change.

## See Also

If you're looking to call the Braintrust REST API with Java code, see the [Braintrust API Client](https://github.com/braintrustdata/braintrust-java)

## Quick Start

Add the SDK to your package manager. Latest version and full instructions can be found in [Maven Central](https://central.sonatype.com/artifact/dev.braintrust/braintrust-sdk-java/versions)

build.gradle example:
```gradle
dependencies {
  implementation 'dev.braintrust:braintrust-sdk-java:<version-goes-here>'
}
```

### Evals

```java
var braintrust = Braintrust.get();
var openTelemetry = braintrust.openTelemetryCreate();
var openAIClient = BraintrustOpenAI.wrapOpenAI(openTelemetry, OpenAIOkHttpClient.fromEnv());

Function<String, String> getFoodType =
        (String food) -> {
            var request =
                    ChatCompletionCreateParams.builder()
                            .model(ChatModel.GPT_4O_MINI)
                            .addSystemMessage("Return a one word answer")
                            .addUserMessage("What kind of food is " + food + "?")
                            .maxTokens(50L)
                            .temperature(0.0)
                            .build();
            var response = openAIClient.chat().completions().create(request);
            return response.choices().get(0).message().content().orElse("").toLowerCase();
        };

var eval = braintrust.<String, String>evalBuilder()
                .name("java-eval-x-" + System.currentTimeMillis())
                .cases(
                        DatasetCase.of("asparagus", "vegetable"),
                        DatasetCase.of("banana", "fruit"))
                .taskFunction(getFoodType)
                .scorers(
                        Scorer.of(
                                "exact_match",
                                (expected, result) -> expected.equals(result) ? 1.0 : 0.0))
                .build();
var result = eval.run();
System.out.println("\n\n" + result.createReportString());
```

### OpenAI Tracing

```java
var braintrust = Braintrust.get();
var openTelemetry = braintrust.openTelemetryCreate();
OpenAIClient openAIClient = BraintrustOpenAI.wrapOpenAI(openTelemetry, OpenAIOkHttpClient.fromEnv());

var request =
        ChatCompletionCreateParams.builder()
                .model(ChatModel.GPT_4O_MINI)
                .addSystemMessage("You are a helpful assistant")
                .addUserMessage("What is the capital of France?")
                .temperature(0.0)
                .build();
# openai calls will be automatically traced and reported to braintrust
var response = openAIClient.chat().completions().create(request);
```

## Running Examples

Example source code can be found [here](./examples/src/main/java/dev/braintrust/examples)

```bash
export BRAINTRUST_API_KEY="your-braintrust-api-key"
export OPENAI_API_KEY="your-oai-api-key" # to run oai examples
export ANTHROPIC_API_KEY="your-anthropic-api-key" # to run anthropic examples
# install java 17 or later
brew install openjdk@17 # macOS
sudo apt install openjdk-17-jdk # ubuntu
# to run a specific example
./gradlew :examples:runSimpleOpenTelemetry
# to see all examples
./gradlew :examples:tasks --group="Braintrust SDK Examples"
```

## Logging

The SDK uses a standard slf4j logger and will use the default log level (or not log at all if slf4j is not installed).

All Braintrust loggers will log into the `dev.braintrust` namespace. To adjust the log level, consult your logger documentation.

For example, to enable debug logging for slf4j-simple you would set the system property `org.slf4j.simpleLogger.log.dev.braintrust=DEBUG`
