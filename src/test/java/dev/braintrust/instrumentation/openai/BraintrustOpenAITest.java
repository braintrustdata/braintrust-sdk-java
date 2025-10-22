package dev.braintrust.instrumentation.openai;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static dev.braintrust.trace.BraintrustTracingTest.getExportedBraintrustSpans;
import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import com.openai.client.OpenAIClient;
import com.openai.client.okhttp.OpenAIOkHttpClient;
import com.openai.models.ChatModel;
import com.openai.models.chat.completions.*;
import dev.braintrust.api.BraintrustApiClient;
import dev.braintrust.config.BraintrustConfig;
import dev.braintrust.prompt.BraintrustPrompt;
import dev.braintrust.trace.Base64Attachment;
import dev.braintrust.trace.BraintrustTracing;
import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import lombok.SneakyThrows;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

public class BraintrustOpenAITest {
    private static final ObjectMapper JSON_MAPPER =
            new com.fasterxml.jackson.databind.ObjectMapper();

    @RegisterExtension
    static WireMockExtension wireMock =
            WireMockExtension.newInstance().options(wireMockConfig().dynamicPort()).build();

    private final BraintrustConfig config =
            BraintrustConfig.of(
                    "BRAINTRUST_API_KEY", "foobar",
                    "BRAINTRUST_DEFAULT_PROJECT_NAME", "unit-test-project",
                    "BRAINTRUST_JAVA_EXPORT_SPANS_IN_MEMORY_FOR_UNIT_TEST", "true");

    @BeforeEach
    void beforeEach() {
        GlobalOpenTelemetry.resetForTest();
        getExportedBraintrustSpans().clear();
        wireMock.resetAll();
    }

    @Test
    @SneakyThrows
    void testWrapOpenAi() {
        // Mock the OpenAI API response
        wireMock.stubFor(
                post(urlEqualTo("/chat/completions"))
                        .willReturn(
                                aResponse()
                                        .withStatus(200)
                                        .withHeader("Content-Type", "application/json")
                                        .withBody(
                                                """
                                {
                                  "id": "chatcmpl-test123",
                                  "object": "chat.completion",
                                  "created": 1677652288,
                                  "model": "gpt-4o-mini",
                                  "choices": [
                                    {
                                      "index": 0,
                                      "message": {
                                        "role": "assistant",
                                        "content": "The capital of France is Paris."
                                      },
                                      "finish_reason": "stop"
                                    }
                                  ],
                                  "usage": {
                                    "prompt_tokens": 20,
                                    "completion_tokens": 8,
                                    "total_tokens": 28
                                  }
                                }
                                """)));

        var openTelemetry = (OpenTelemetrySdk) BraintrustTracing.of(config, true);

        // Create OpenAI client pointing to WireMock server
        OpenAIClient openAIClient =
                OpenAIOkHttpClient.builder()
                        .baseUrl("http://localhost:" + wireMock.getPort())
                        .apiKey("test-api-key")
                        .build();

        // Wrap with Braintrust instrumentation
        openAIClient = BraintrustOpenAI.wrapOpenAI(openTelemetry, openAIClient);

        var request =
                ChatCompletionCreateParams.builder()
                        .model(ChatModel.GPT_4O_MINI)
                        .addSystemMessage("You are a helpful assistant")
                        .addUserMessage("What is the capital of France?")
                        .temperature(0.0)
                        .build();

        var response = openAIClient.chat().completions().create(request);

        // Verify the response
        assertNotNull(response);
        wireMock.verify(1, postRequestedFor(urlEqualTo("/chat/completions")));
        assertEquals("chatcmpl-test123", response.id());
        assertEquals(
                "The capital of France is Paris.",
                response.choices().get(0).message().content().get());

        // Verify spans were exported
        assertTrue(
                openTelemetry
                        .getSdkTracerProvider()
                        .forceFlush()
                        .join(10, TimeUnit.SECONDS)
                        .isSuccess());
        var spanData =
                getExportedBraintrustSpans().get(config.getBraintrustParentValue().orElseThrow());
        assertNotNull(spanData);
        assertEquals(1, spanData.size());
        var span = spanData.get(0);

        assertEquals("openai", span.getAttributes().get(AttributeKey.stringKey("gen_ai.system")));
        assertEquals(
                "gpt-4o-mini",
                span.getAttributes().get(AttributeKey.stringKey("gen_ai.request.model")));
        assertEquals(
                "gpt-4o-mini",
                span.getAttributes().get(AttributeKey.stringKey("gen_ai.response.model")));
        assertEquals(
                "[stop]",
                span.getAttributes()
                        .get(AttributeKey.stringArrayKey("gen_ai.response.finish_reasons"))
                        .toString());
        assertEquals(
                "chat", span.getAttributes().get(AttributeKey.stringKey("gen_ai.operation.name")));
        assertEquals(
                "chatcmpl-test123",
                span.getAttributes().get(AttributeKey.stringKey("gen_ai.response.id")));
        assertEquals(
                "[{\"role\":\"system\",\"parts\":[{\"type\":\"text\",\"content\":\"You are a"
                    + " helpful"
                    + " assistant\"}]},{\"role\":\"user\",\"parts\":[{\"type\":\"text\",\"content\":\"What"
                    + " is the capital of France?\"}]}]",
                span.getAttributes().get(AttributeKey.stringKey("gen_ai.input.messages")));
        assertEquals(
                "project_name:unit-test-project",
                span.getAttributes().get(AttributeKey.stringKey("braintrust.parent")));
        assertEquals(
                20L, span.getAttributes().get(AttributeKey.longKey("gen_ai.usage.input_tokens")));
        assertEquals(
                8L, span.getAttributes().get(AttributeKey.longKey("gen_ai.usage.output_tokens")));
        assertEquals(
                0.0,
                span.getAttributes().get(AttributeKey.doubleKey("gen_ai.request.temperature")));

        String outputJson =
                span.getAttributes().get(AttributeKey.stringKey("gen_ai.output.messages"));
        assertEquals(
                "[{\"role\":\"assistant\",\"parts\":[{\"type\":\"text\",\"content\":\"The capital"
                        + " of France is Paris.\"}],\"finish_reason\":\"stop\"}]",
                outputJson);

        assertEquals(
                "chatcmpl-test123",
                span.getAttributes().get(AttributeKey.stringKey("gen_ai.response.id")));
    }

    @Test
    @SneakyThrows
    void testWrapOpenAiStreaming() {
        // Mock the OpenAI API streaming response
        String streamingResponse =
                """
                data: {"id":"chatcmpl-test123","object":"chat.completion.chunk","created":1677652288,"model":"gpt-4o-mini","choices":[{"index":0,"delta":{"role":"assistant","content":""},"finish_reason":null}]}

                data: {"id":"chatcmpl-test123","object":"chat.completion.chunk","created":1677652288,"model":"gpt-4o-mini","choices":[{"index":0,"delta":{"content":"The"},"finish_reason":null}]}

                data: {"id":"chatcmpl-test123","object":"chat.completion.chunk","created":1677652288,"model":"gpt-4o-mini","choices":[{"index":0,"delta":{"content":" capital"},"finish_reason":null}]}

                data: {"id":"chatcmpl-test123","object":"chat.completion.chunk","created":1677652288,"model":"gpt-4o-mini","choices":[{"index":0,"delta":{"content":" of"},"finish_reason":null}]}

                data: {"id":"chatcmpl-test123","object":"chat.completion.chunk","created":1677652288,"model":"gpt-4o-mini","choices":[{"index":0,"delta":{"content":" France"},"finish_reason":null}]}

                data: {"id":"chatcmpl-test123","object":"chat.completion.chunk","created":1677652288,"model":"gpt-4o-mini","choices":[{"index":0,"delta":{"content":" is"},"finish_reason":null}]}

                data: {"id":"chatcmpl-test123","object":"chat.completion.chunk","created":1677652288,"model":"gpt-4o-mini","choices":[{"index":0,"delta":{"content":" Paris"},"finish_reason":null}]}

                data: {"id":"chatcmpl-test123","object":"chat.completion.chunk","created":1677652288,"model":"gpt-4o-mini","choices":[{"index":0,"delta":{"content":"."},"finish_reason":null}]}

                data: {"id":"chatcmpl-test123","object":"chat.completion.chunk","created":1677652288,"model":"gpt-4o-mini","choices":[{"index":0,"delta":{},"finish_reason":"stop"}]}

                data: {"id":"chatcmpl-test123","object":"chat.completion.chunk","created":1677652288,"model":"gpt-4o-mini","choices":[],"usage":{"prompt_tokens":20,"completion_tokens":8,"total_tokens":28}}

                data: [DONE]

                """;

        wireMock.stubFor(
                post(urlEqualTo("/chat/completions"))
                        .willReturn(
                                aResponse()
                                        .withStatus(200)
                                        .withHeader("Content-Type", "text/event-stream")
                                        .withBody(streamingResponse)));

        var openTelemetry = (OpenTelemetrySdk) BraintrustTracing.of(config, true);

        // Create OpenAI client pointing to WireMock server
        OpenAIClient openAIClient =
                OpenAIOkHttpClient.builder()
                        .baseUrl("http://localhost:" + wireMock.getPort())
                        .apiKey("test-api-key")
                        .build();

        // Wrap with Braintrust instrumentation
        openAIClient = BraintrustOpenAI.wrapOpenAI(openTelemetry, openAIClient);

        var request =
                ChatCompletionCreateParams.builder()
                        .model(ChatModel.GPT_4O_MINI)
                        .addSystemMessage("You are a helpful assistant")
                        .addUserMessage("What is the capital of France?")
                        .temperature(0.0)
                        .streamOptions(
                                ChatCompletionStreamOptions.builder().includeUsage(true).build())
                        .build();

        // Consume the stream
        StringBuilder fullResponse = new StringBuilder();
        try (var stream = openAIClient.chat().completions().createStreaming(request)) {
            stream.stream()
                    .forEach(
                            chunk -> {
                                if (!chunk.choices().isEmpty()) {
                                    chunk.choices()
                                            .get(0)
                                            .delta()
                                            .content()
                                            .ifPresent(fullResponse::append);
                                }
                            });
        }

        // Verify the response
        assertEquals("The capital of France is Paris.", fullResponse.toString());
        wireMock.verify(1, postRequestedFor(urlEqualTo("/chat/completions")));

        // Verify spans were exported
        assertTrue(
                openTelemetry
                        .getSdkTracerProvider()
                        .forceFlush()
                        .join(10, TimeUnit.SECONDS)
                        .isSuccess());
        var spanData =
                getExportedBraintrustSpans().get(config.getBraintrustParentValue().orElseThrow());
        assertNotNull(spanData);
        assertEquals(1, spanData.size());
        var span = spanData.get(0);

        // Verify span attributes
        assertEquals("openai", span.getAttributes().get(AttributeKey.stringKey("gen_ai.system")));
        assertEquals(
                "gpt-4o-mini",
                span.getAttributes().get(AttributeKey.stringKey("gen_ai.request.model")));
        assertEquals(
                "gpt-4o-mini",
                span.getAttributes().get(AttributeKey.stringKey("gen_ai.response.model")));
        assertEquals(
                "[stop]",
                span.getAttributes()
                        .get(AttributeKey.stringArrayKey("gen_ai.response.finish_reasons"))
                        .toString());
        assertEquals(
                "chat", span.getAttributes().get(AttributeKey.stringKey("gen_ai.operation.name")));
        assertEquals(
                "chatcmpl-test123",
                span.getAttributes().get(AttributeKey.stringKey("gen_ai.response.id")));

        // Verify usage metrics were captured
        assertEquals(
                20L, span.getAttributes().get(AttributeKey.longKey("gen_ai.usage.input_tokens")));
        assertEquals(
                8L, span.getAttributes().get(AttributeKey.longKey("gen_ai.usage.output_tokens")));

        // Verify output JSON was captured
        String outputJson =
                span.getAttributes().get(AttributeKey.stringKey("braintrust.output_json"));
        assertNotNull(outputJson);
        var outputMessages = JSON_MAPPER.readTree(outputJson);
        assertEquals(1, outputMessages.size());
        var messageZero = outputMessages.get(0);
        assertEquals("The capital of France is Paris.", messageZero.get("content").asText());
    }

    @Test
    @SneakyThrows
    void testWrapOpenAiWithImageAttachment() {
        // Mock the OpenAI API response for vision request
        wireMock.stubFor(
                post(urlEqualTo("/chat/completions"))
                        .willReturn(
                                aResponse()
                                        .withStatus(200)
                                        .withHeader("Content-Type", "application/json")
                                        .withBody(
                                                """
                                {
                                  "id": "chatcmpl-test456",
                                  "object": "chat.completion",
                                  "created": 1677652288,
                                  "model": "gpt-4o-mini",
                                  "choices": [
                                    {
                                      "index": 0,
                                      "message": {
                                        "role": "assistant",
                                        "content": "This image shows the Eiffel Tower in Paris, France."
                                      },
                                      "finish_reason": "stop"
                                    }
                                  ],
                                  "usage": {
                                    "prompt_tokens": 150,
                                    "completion_tokens": 15,
                                    "total_tokens": 165
                                  }
                                }
                                """)));

        var openTelemetry = (OpenTelemetrySdk) BraintrustTracing.of(config, true);

        // Create OpenAI client pointing to WireMock server
        OpenAIClient openAIClient =
                OpenAIOkHttpClient.builder()
                        .baseUrl("http://localhost:" + wireMock.getPort())
                        .apiKey("test-api-key")
                        .build();

        // Wrap with Braintrust instrumentation
        openAIClient = BraintrustOpenAI.wrapOpenAI(openTelemetry, openAIClient);

        String imageDataUrl =
                Base64Attachment.ofFile(
                                Base64Attachment.ContentType.IMAGE_JPEG,
                                "src/test/java/dev/braintrust/instrumentation/openai/travel-paris-france-poster.jpg")
                        .getBase64Data();

        // Create text content part
        ChatCompletionContentPartText textPart =
                ChatCompletionContentPartText.builder().text("What's in this image?").build();
        ChatCompletionContentPart textContentPart = ChatCompletionContentPart.ofText(textPart);

        // Create image content part with base64-encoded image
        ChatCompletionContentPartImage imagePart =
                ChatCompletionContentPartImage.builder()
                        .imageUrl(
                                ChatCompletionContentPartImage.ImageUrl.builder()
                                        // .url("https://example.com/eiffel-tower.jpg")
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

        // Verify the response
        assertNotNull(response);
        wireMock.verify(1, postRequestedFor(urlEqualTo("/chat/completions")));
        assertEquals("chatcmpl-test456", response.id());
        assertEquals(
                "This image shows the Eiffel Tower in Paris, France.",
                response.choices().get(0).message().content().get());

        // Verify spans were exported
        assertTrue(
                openTelemetry
                        .getSdkTracerProvider()
                        .forceFlush()
                        .join(10, TimeUnit.SECONDS)
                        .isSuccess());
        var spanData =
                getExportedBraintrustSpans().get(config.getBraintrustParentValue().orElseThrow());
        assertNotNull(spanData);
        assertEquals(1, spanData.size());
        var span = spanData.get(0);

        // Verify span attributes
        assertEquals("openai", span.getAttributes().get(AttributeKey.stringKey("gen_ai.system")));
        assertEquals(
                "gpt-4o-mini",
                span.getAttributes().get(AttributeKey.stringKey("gen_ai.request.model")));
        assertEquals(
                "gpt-4o-mini",
                span.getAttributes().get(AttributeKey.stringKey("gen_ai.response.model")));
        assertEquals(
                "[stop]",
                span.getAttributes()
                        .get(AttributeKey.stringArrayKey("gen_ai.response.finish_reasons"))
                        .toString());
        assertEquals(
                "chat", span.getAttributes().get(AttributeKey.stringKey("gen_ai.operation.name")));
        assertEquals(
                "chatcmpl-test456",
                span.getAttributes().get(AttributeKey.stringKey("gen_ai.response.id")));

        // Verify input JSON captures both text and image content
        String inputJson =
                span.getAttributes().get(AttributeKey.stringKey("gen_ai.input.messages"));
        assertEquals(
                "[{\"role\":\"system\",\"parts\":[{\"type\":\"text\",\"content\":\"You are a helpful assistant that can analyze images\"}]},{\"role\":\"user\",\"parts\":[{\"type\":\"text\",\"content\":\"What's in this image?\"},{\"type\":\"base64_attachment\",\"content\":\"%s\"}]}]"
                        .formatted(imageDataUrl),
                inputJson);
        assertNotNull(inputJson);
        var inputMessages = JSON_MAPPER.readTree(inputJson);
        assertEquals(2, inputMessages.size()); // system message + user message

        // Verify usage metrics
        assertEquals(
                150L, span.getAttributes().get(AttributeKey.longKey("gen_ai.usage.input_tokens")));
        assertEquals(
                15L, span.getAttributes().get(AttributeKey.longKey("gen_ai.usage.output_tokens")));

        // Verify output JSON
        String outputJson =
                span.getAttributes().get(AttributeKey.stringKey("gen_ai.output.messages"));
        assertEquals(
                "[{\"role\":\"assistant\",\"parts\":[{\"type\":\"text\",\"content\":\"This image"
                    + " shows the Eiffel Tower in Paris, France.\"}],\"finish_reason\":\"stop\"}]",
                outputJson);
    }

    @Test
    @SneakyThrows
    void testBuildChatCompletionsPrompt() {
        Map<String, Object> promptContent =
                Map.of(
                        "type",
                        "chat",
                        "messages",
                        List.of(
                                Map.of(
                                        "role", "system",
                                        "content",
                                                "You are a kind chatbot who briefly greets people"),
                                Map.of(
                                        "role", "user",
                                        "content", "What's up my friend? My name is {{name}}")));

        Map<String, Object> options =
                Map.of(
                        "model",
                        "gpt-4o-mini",
                        "params",
                        Map.of("temperature", 0.1, "max_tokens", 102));

        BraintrustApiClient.PromptData promptData =
                new BraintrustApiClient.PromptData(promptContent, options);

        BraintrustApiClient.Prompt promptObject =
                new BraintrustApiClient.Prompt(
                        "test-id",
                        "test-project-id",
                        "test-org-id",
                        "kind-greeter",
                        "kind-greeter-test",
                        Optional.of("Test prompt"),
                        "2025-10-21T21:35:18.287Z",
                        promptData,
                        Optional.empty(),
                        Optional.empty());

        BraintrustPrompt prompt = new BraintrustPrompt(promptObject);

        Map<String, String> parameters = Map.of("name", "Alice");
        ChatCompletionCreateParams renderedParams =
                BraintrustOpenAI.buildChatCompletionsPrompt(prompt, parameters);

        assertEquals(
                ChatCompletionCreateParams.builder()
                        .model(ChatModel.GPT_4O_MINI)
                        .temperature(0.1)
                        .maxTokens(102L)
                        .addSystemMessage("You are a kind chatbot who briefly greets people")
                        .addUserMessage("What's up my friend? My name is Alice")
                        .build(),
                renderedParams);
    }
}
