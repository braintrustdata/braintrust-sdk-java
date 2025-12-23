package dev.braintrust.instrumentation.langchain;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.junit.jupiter.api.Assertions.*;

import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import dev.braintrust.TestHarness;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.http.client.HttpClientBuilderLoader;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.chat.response.StreamingChatResponseHandler;
import dev.langchain4j.model.openai.OpenAiChatModel;
import dev.langchain4j.model.openai.OpenAiStreamingChatModel;
import java.util.concurrent.CompletableFuture;
import lombok.SneakyThrows;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

public class BraintrustLangchainTest {

    @RegisterExtension
    static WireMockExtension wireMock =
            WireMockExtension.newInstance().options(wireMockConfig().dynamicPort()).build();

    private TestHarness testHarness;

    @BeforeEach
    void beforeEach() {
        testHarness = TestHarness.setup();
        wireMock.resetAll();
    }

    @Test
    @SneakyThrows
    void testSyncChatCompletion() {
        // Mock the OpenAI API response
        wireMock.stubFor(
                post(urlEqualTo("/v1/chat/completions"))
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

        // Create LangChain4j client with Braintrust instrumentation
        var clientBuilder =
                BraintrustLangchain.wrap(
                        testHarness.openTelemetry(),
                        HttpClientBuilderLoader.loadHttpClientBuilder(),
                        new BraintrustLangchain.Options("openai"));

        ChatModel model =
                OpenAiChatModel.builder()
                        .apiKey("test-api-key")
                        .baseUrl("http://localhost:" + wireMock.getPort() + "/v1")
                        .modelName("gpt-4o-mini")
                        .temperature(0.0)
                        .httpClientBuilder(clientBuilder)
                        .build();

        // Execute chat request
        var message = UserMessage.from("What is the capital of France?");
        var response = model.chat(message);

        // Verify the response
        assertNotNull(response);
        assertEquals("The capital of France is Paris.", response.aiMessage().text());
        wireMock.verify(1, postRequestedFor(urlEqualTo("/v1/chat/completions")));

        // Verify spans were exported
        var spans = testHarness.awaitExportedSpans();
        assertEquals(1, spans.size(), "Expected one span for sync chat completion");
        var span = spans.get(0);

        // Verify span name
        assertEquals("Chat Completion", span.getName(), "Span name should be 'Chat Completion'");

        // Verify span attributes
        var attributes = span.getAttributes();

        // Verify span type
        assertEquals(
                "llm",
                attributes.get(
                        io.opentelemetry.api.common.AttributeKey.stringKey(
                                "braintrust.span_attributes.type")),
                "Span type should be 'llm'");

        // Verify metadata
        String metadataJson =
                attributes.get(
                        io.opentelemetry.api.common.AttributeKey.stringKey("braintrust.metadata"));
        assertNotNull(metadataJson, "Metadata should be present");
        assertTrue(
                metadataJson.contains("\"provider\":\"openai\""),
                "Metadata should contain provider");
        assertTrue(
                metadataJson.contains("\"model\":\"gpt-4o-mini\""),
                "Metadata should contain model");

        // Verify metrics
        String metricsJson =
                attributes.get(
                        io.opentelemetry.api.common.AttributeKey.stringKey("braintrust.metrics"));
        assertNotNull(metricsJson, "Metrics should be present");
        assertTrue(metricsJson.contains("\"tokens\":28"), "Metrics should contain tokens");
        assertTrue(
                metricsJson.contains("\"prompt_tokens\":20"),
                "Metrics should contain prompt_tokens");
        assertTrue(
                metricsJson.contains("\"completion_tokens\":8"),
                "Metrics should contain completion_tokens");
        assertTrue(
                metricsJson.contains("time_to_first_token"),
                "Metrics should contain time_to_first_token");

        // Verify input
        String inputJson =
                attributes.get(
                        io.opentelemetry.api.common.AttributeKey.stringKey(
                                "braintrust.input_json"));
        assertNotNull(inputJson, "Input should be present");
        assertTrue(
                inputJson.contains("What is the capital of France"),
                "Input should contain the user message");

        // Verify output
        String outputJson =
                attributes.get(
                        io.opentelemetry.api.common.AttributeKey.stringKey(
                                "braintrust.output_json"));
        assertNotNull(outputJson, "Output should be present");
        assertTrue(
                outputJson.contains("The capital of France is Paris"),
                "Output should contain the assistant response");
    }

    @Test
    @SneakyThrows
    void testStreamingChatCompletion() {
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
                post(urlEqualTo("/v1/chat/completions"))
                        .willReturn(
                                aResponse()
                                        .withStatus(200)
                                        .withHeader("Content-Type", "text/event-stream")
                                        .withBody(streamingResponse)));

        // Create LangChain4j streaming client with Braintrust instrumentation
        var clientBuilder =
                BraintrustLangchain.wrap(
                        testHarness.openTelemetry(),
                        HttpClientBuilderLoader.loadHttpClientBuilder(),
                        new BraintrustLangchain.Options("openai"));

        StreamingChatModel model =
                OpenAiStreamingChatModel.builder()
                        .apiKey("test-api-key")
                        .baseUrl("http://localhost:" + wireMock.getPort() + "/v1")
                        .modelName("gpt-4o-mini")
                        .temperature(0.0)
                        .httpClientBuilder(clientBuilder)
                        .build();

        // Execute streaming chat request
        var future = new CompletableFuture<ChatResponse>();
        var responseBuilder = new StringBuilder();

        model.chat(
                "What is the capital of France?",
                new StreamingChatResponseHandler() {
                    @Override
                    public void onPartialResponse(String token) {
                        responseBuilder.append(token);
                    }

                    @Override
                    public void onCompleteResponse(ChatResponse response) {
                        future.complete(response);
                    }

                    @Override
                    public void onError(Throwable error) {
                        future.completeExceptionally(error);
                    }
                });

        // Wait for completion
        var response = future.get();

        // Verify the response
        assertNotNull(response);
        assertEquals("The capital of France is Paris.", responseBuilder.toString());
        wireMock.verify(1, postRequestedFor(urlEqualTo("/v1/chat/completions")));

        // Verify spans were exported
        var spans = testHarness.awaitExportedSpans(1);
        assertEquals(1, spans.size(), "Expected one span for streaming chat completion");
        var span = spans.get(0);

        // Verify span name
        assertEquals("Chat Completion", span.getName(), "Span name should be 'Chat Completion'");

        // Verify span attributes
        var attributes = span.getAttributes();

        // Verify span type
        assertEquals(
                "llm",
                attributes.get(
                        io.opentelemetry.api.common.AttributeKey.stringKey(
                                "braintrust.span_attributes.type")),
                "Span type should be 'llm'");

        // Verify metadata
        String metadataJson =
                attributes.get(
                        io.opentelemetry.api.common.AttributeKey.stringKey("braintrust.metadata"));
        assertNotNull(metadataJson, "Metadata should be present");
        assertTrue(
                metadataJson.contains("\"provider\":\"openai\""),
                "Metadata should contain provider");
        assertTrue(
                metadataJson.contains("\"model\":\"gpt-4o-mini\""),
                "Metadata should contain model");

        // Verify metrics for streaming
        String metricsJson =
                attributes.get(
                        io.opentelemetry.api.common.AttributeKey.stringKey("braintrust.metrics"));
        assertNotNull(metricsJson, "Metrics should be present");
        assertTrue(metricsJson.contains("\"tokens\":28"), "Metrics should contain tokens");
        assertTrue(
                metricsJson.contains("\"prompt_tokens\":20"),
                "Metrics should contain prompt_tokens");
        assertTrue(
                metricsJson.contains("\"completion_tokens\":8"),
                "Metrics should contain completion_tokens");
        assertTrue(
                metricsJson.contains("time_to_first_token"),
                "Metrics should contain time_to_first_token for streaming");

        // Verify input
        String inputJson =
                attributes.get(
                        io.opentelemetry.api.common.AttributeKey.stringKey(
                                "braintrust.input_json"));
        assertNotNull(inputJson, "Input should be present");
        assertTrue(
                inputJson.contains("What is the capital of France"),
                "Input should contain the user message");

        // Verify output (streaming reconstructs the output)
        String outputJson =
                attributes.get(
                        io.opentelemetry.api.common.AttributeKey.stringKey(
                                "braintrust.output_json"));
        assertNotNull(outputJson, "Output should be present");
        assertTrue(
                outputJson.contains("The capital of France is Paris"),
                "Output should contain the complete streamed response");
        assertTrue(
                outputJson.contains("\"finish_reason\":\"stop\""),
                "Output should contain finish_reason");
    }
}
