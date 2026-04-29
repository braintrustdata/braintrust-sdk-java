# Braintrust Java Tracing & Eval SDK

[![javadoc](https://javadoc.io/badge2/dev.braintrust/braintrust-sdk-java/javadoc.svg)](https://javadoc.io/doc/dev.braintrust/braintrust-sdk-java)
[![CI](https://github.com/braintrustdata/braintrust-sdk-java/actions/workflows/ci.yml/badge.svg)](https://github.com/braintrustdata/braintrust-sdk-java/actions/workflows/ci.yml)

## Overview

This library provides tools for **evaluating** and **tracing** AI applications in [Braintrust](https://www.braintrust.dev). Use it to:

- **Evaluate** your AI models with custom test cases and scoring functions
- **Trace** LLM calls and monitor AI application performance with OpenTelemetry
- **Integrate** seamlessly with OpenAI, Anthropic, and other LLM providers

This SDK is currently in BETA status and APIs may change.

## Autoinstrumentation Quickstart
The fastest way to report data to braintrust is to add the [braintrust java agent](https://central.sonatype.com/artifact/dev.braintrust/braintrust-java-agent) to your jvm startup args.


springboot+gradle example:

```build.gradle
configurations {
    btAgent
}
dependencies {
    implementation 'org.springframework.boot:spring-boot-starter-web'
    btAgent "dev.braintrust:braintrust-java-agent:<version-goes-here>"
}
bootRun {
    jvmArgs = [
        // NOTE: if you're running with other java agents, add the braintrust agent last
        "-javaagent:${configurations.btAgent.singleFile.absolutePath}",
    ]
}
```

This will automatically instrument major AI clients and frameworks. No code changes required. A list of supported instrumentation can be found [here](./braintrust-sdk/instrumentation)

### Autoinstrumentation with the open telemetry java agent

Users of the [open telemetry java agent](https://opentelemetry.io/docs/zero-code/java/agent/) will follow standard autoinstrumentation instructions and also their otel agent with the [braintrust otel extension](https://central.sonatype.com/artifact/dev.braintrust/braintrust-otel-extension)

```
bootRun {
    jvmArgs = [
        "-javaagent:/path/to/otel-java-agent.jar",
        "-Dotel.javaagent.extensions=/path/to/braintrust-otel-extension.jar",
        "-javaagent:/path/to/braintrust-java-agent.jar",
    ]
}
```

## Eval Quickstart

Add the [Braintrust SDK](https://central.sonatype.com/artifact/dev.braintrust/braintrust-sdk-java) to your package manager.

gradle example:
```gradle
dependencies {
  implementation 'dev.braintrust:braintrust-sdk-java:<version-goes-here>'
}
```

Use the SDK to create and send your eval:
```java
var braintrust = Braintrust.get();
var openTelemetry = braintrust.openTelemetryCreate();
var openAIClient = OpenAIOkHttpClient.fromEnv();

Function<String, String> getFoodType =
        (String food) -> {
            var request =
                    ChatCompletionCreateParams.builder()
                            .model(ChatModel.GPT_4O_MINI)
                            .addSystemMessage("Return a one word answer")
                            .addUserMessage("What kind of food is " + food + "?")
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

### Manual Instrumentation

Alternatively, sdk users can manually apply instrumentation instead of using the java agent.

```java
var braintrust = Braintrust.get();
var openTelemetry = braintrust.openTelemetryCreate();
OpenAIClient openAIClient = BraintrustOpenAI.wrapOpenAI(openTelemetry, OpenAIOkHttpClient.fromEnv());

var request =
        ChatCompletionCreateParams.builder()
                .model(ChatModel.GPT_4O_MINI)
                .addUserMessage("What is the capital of France?")
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
