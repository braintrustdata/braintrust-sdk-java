package dev.braintrust.examples;

import com.openai.client.OpenAIClient;
import com.openai.client.okhttp.OpenAIOkHttpClient;
import com.openai.models.ChatModel;
import com.openai.models.chat.completions.*;
import dev.braintrust.Braintrust;
import dev.braintrust.instrumentation.openai.BraintrustOpenAI;
import dev.braintrust.trace.Base64Attachment;
import java.util.Arrays;

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

    private static void chatCompletionsExample(OpenAIClient openAIClient) throws Exception {
        // Read and encode the image
        String imageDataUrl =
                Base64Attachment.ofFile(
                                Base64Attachment.ContentType.IMAGE_PNG, "/tmp/large-png-3mb.png")
                        .getBase64Data();

        // Create text content part
        ChatCompletionContentPartText textPart =
                ChatCompletionContentPartText.builder()
                        .text("What do you see in this image?")
                        .build();
        ChatCompletionContentPart textContentPart = ChatCompletionContentPart.ofText(textPart);

        // Create image content part
        ChatCompletionContentPartImage imagePart =
                ChatCompletionContentPartImage.builder()
                        .imageUrl(
                                ChatCompletionContentPartImage.ImageUrl.builder()
                                        .url(imageDataUrl)
                                        .detail(ChatCompletionContentPartImage.ImageUrl.Detail.HIGH)
                                        .build())
                        .build();
        ChatCompletionContentPart imageContentPart =
                ChatCompletionContentPart.ofImageUrl(imagePart);

        // Create user message with both text and image
        ChatCompletionUserMessageParam userMessage =
                ChatCompletionUserMessageParam.builder()
                        .contentOfArrayOfContentParts(
                                Arrays.asList(textContentPart, imageContentPart))
                        .build();

        var request =
                ChatCompletionCreateParams.builder()
                        .model(ChatModel.GPT_4O_MINI)
                        .addSystemMessage("You are a helpful assistant that can analyze images")
                        .addMessage(userMessage)
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
