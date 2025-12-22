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
                        HttpClientBuilderLoader.loadHttpClientBuilder());

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

        // Basic span verification - just ensure a span was created
        assertNotNull(span);
        assertNotNull(span.getName());
        assertFalse(span.getName().isEmpty(), "Span name should not be empty");
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
                        HttpClientBuilderLoader.loadHttpClientBuilder());

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
        // NOTE: This will likely fail because streaming instrumentation isn't implemented yet
        var spans = testHarness.awaitExportedSpans();
        assertEquals(1, spans.size(), "Expected one span for streaming chat completion");
        var span = spans.get(0);

        // Basic span verification - just ensure a span was created
        assertNotNull(span);
        assertNotNull(span.getName());
        assertFalse(span.getName().isEmpty(), "Span name should not be empty");
    }
}
