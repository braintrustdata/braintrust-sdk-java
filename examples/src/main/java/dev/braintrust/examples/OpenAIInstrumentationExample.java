package dev.braintrust.examples;

import com.openai.client.OpenAIClient;
import com.openai.client.okhttp.OpenAIOkHttpClient;
import com.openai.models.ChatModel;
import com.openai.models.chat.completions.ChatCompletionCreateParams;
import com.openai.models.chat.completions.ChatCompletionStreamOptions;
import dev.braintrust.Braintrust;
import dev.braintrust.instrumentation.openai.BraintrustOpenAI;
import dev.langchain4j.model.openai.internal.spi.OpenAiClientBuilderFactory;

/** Basic OTel + OpenAI instrumentation example */
public class OpenAIInstrumentationExample {

    public static void main(String[] args) throws Exception {
        if (null == System.getenv("OPENAI_API_KEY")) {
            System.err.println(
                    "\nWARNING envar OPEN_AI_API_KEY not found. This example will likely fail.\n");
        }
        var braintrust = Braintrust.get();
        var openTelemetry = braintrust.openTelemetryCreate();
        OpenAIClient openAIClient =
                BraintrustOpenAI.wrapOpenAI(openTelemetry, OpenAIOkHttpClient.fromEnv());
        var rootSpan =
                openTelemetry
                        .getTracer("my-instrumentation")
                        .spanBuilder("openai-java-instrumentation-example")
                        .startSpan();
        try (var ignored = rootSpan.makeCurrent()) {
            chatCompletionsExample(openAIClient);
            // chatCompletionsStreamingExample(openAIClient);
        } finally {
            rootSpan.end();
        }
        var url =
                braintrust.projectUri()
                        + "/logs?r=%s&s=%s"
                                .formatted(
                                        rootSpan.getSpanContext().getTraceId(),
                                        rootSpan.getSpanContext().getSpanId());
        System.out.println(
                "\n\n  Example complete! View your data in Braintrust: %s\n".formatted(url));
    }

    private static void chatCompletionsExample(OpenAIClient openAIClient) {
        var request =
                ChatCompletionCreateParams.builder()
                        .model(ChatModel.GPT_4O_MINI)
                        .addSystemMessage("You are a helpful assistant")
                        .addUserMessage("What is the capital of France?")
                        .temperature(0.0)
                        .build();
        var response = openAIClient.chat().completions().create(request);
        System.out.println("\n~~~ CHAT COMPLETIONS RESPONSE: %s\n".formatted(response));
    }

    private static void chatCompletionsStreamingExample(OpenAIClient openAIClient) {
        var request =
                ChatCompletionCreateParams.builder()
                        .model(ChatModel.GPT_4O_MINI)
                        .addSystemMessage("You are a helpful assistant")
                        .addUserMessage("What is the capital of France?")
                        .temperature(0.0)
                        .streamOptions(
                                ChatCompletionStreamOptions.builder().includeUsage(true).build())
                        .build();

        System.out.println("\n~~~ STREAMING RESPONSE:");
        try (var stream = openAIClient.chat().completions().createStreaming(request)) {
            stream.stream()
                    .forEach(
                            chunk -> {
                                if (!chunk.choices().isEmpty()) {
                                    chunk.choices()
                                            .get(0)
                                            .delta()
                                            .content()
                                            .ifPresent(System.out::print);
                                }
                            });
        }
        System.out.println("\n");
    }
}
