package dev.braintrust.instrumentation.anthropic;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.junit.jupiter.api.Assertions.*;

import com.anthropic.client.AnthropicClient;
import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import com.anthropic.models.messages.MessageCreateParams;
import com.anthropic.models.messages.Model;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import dev.braintrust.TestHarness;
import io.opentelemetry.api.common.AttributeKey;
import lombok.SneakyThrows;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

public class BraintrustAnthropicTest {
    private static final ObjectMapper JSON_MAPPER = new ObjectMapper();

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
    void testWrapAnthropic() {
        // Mock the Anthropic API response
        wireMock.stubFor(
                post(urlEqualTo("/v1/messages"))
                        .willReturn(
                                aResponse()
                                        .withStatus(200)
                                        .withHeader("Content-Type", "application/json")
                                        .withBody(
                                                """
                                {
                                  "id": "msg_test123",
                                  "type": "message",
                                  "role": "assistant",
                                  "model": "claude-3-5-haiku-20241022",
                                  "content": [
                                    {
                                      "type": "text",
                                      "text": "The capital of France is Paris."
                                    }
                                  ],
                                  "stop_reason": "end_turn",
                                  "stop_sequence": null,
                                  "usage": {
                                    "input_tokens": 20,
                                    "output_tokens": 8
                                  }
                                }
                                """)));

        // Create Anthropic client pointing to WireMock server
        AnthropicClient anthropicClient =
                AnthropicOkHttpClient.builder()
                        .baseUrl("http://localhost:" + wireMock.getPort())
                        .apiKey("test-api-key")
                        .build();

        // Wrap with Braintrust instrumentation
        anthropicClient = BraintrustAnthropic.wrap(testHarness.openTelemetry(), anthropicClient);

        var request =
                MessageCreateParams.builder()
                        .model(Model.CLAUDE_3_5_HAIKU_20241022)
                        .system("You are a helpful assistant")
                        .addUserMessage("What is the capital of France?")
                        .maxTokens(50)
                        .temperature(0.0)
                        .build();

        var response = anthropicClient.messages().create(request);

        // Verify the response
        assertNotNull(response);
        wireMock.verify(1, postRequestedFor(urlEqualTo("/v1/messages")));
        assertEquals("msg_test123", response.id());
        var contentBlock = response.content().get(0);
        assertTrue(contentBlock.isText());
        assertEquals("The capital of France is Paris.", contentBlock.asText().text());

        // Verify spans were exported
        var spans = testHarness.awaitExportedSpans();
        assertEquals(1, spans.size());
        var span = spans.get(0);

        // Verify standard GenAI attributes
        assertEquals(
                "anthropic", span.getAttributes().get(AttributeKey.stringKey("gen_ai.system")));
        assertEquals(
                "claude-3-5-haiku-20241022",
                span.getAttributes().get(AttributeKey.stringKey("gen_ai.request.model")));
        assertEquals(
                "claude-3-5-haiku-20241022",
                span.getAttributes().get(AttributeKey.stringKey("gen_ai.response.model")));
        assertEquals(
                "[end_turn]",
                span.getAttributes()
                        .get(AttributeKey.stringArrayKey("gen_ai.response.finish_reasons"))
                        .toString());
        assertEquals(
                "chat", span.getAttributes().get(AttributeKey.stringKey("gen_ai.operation.name")));
        assertEquals(
                "msg_test123",
                span.getAttributes().get(AttributeKey.stringKey("gen_ai.response.id")));
        assertEquals(
                "project_name:" + TestHarness.defaultProjectName(),
                span.getAttributes().get(AttributeKey.stringKey("braintrust.parent")));
        assertEquals(
                20L, span.getAttributes().get(AttributeKey.longKey("gen_ai.usage.input_tokens")));
        assertEquals(
                8L, span.getAttributes().get(AttributeKey.longKey("gen_ai.usage.output_tokens")));
        assertEquals(
                0.0,
                span.getAttributes().get(AttributeKey.doubleKey("gen_ai.request.temperature")));
        assertEquals(
                50L, span.getAttributes().get(AttributeKey.longKey("gen_ai.request.max_tokens")));

        // Verify Braintrust-specific attributes
        assertEquals(
                "[{\"content\":\"You are a helpful"
                    + " assistant\",\"role\":\"system\",\"valid\":false},{\"content\":\"What is the"
                    + " capital of France?\",\"role\":\"user\",\"valid\":true}]",
                span.getAttributes().get(AttributeKey.stringKey("braintrust.input_json")));

        // Verify output JSON
        String outputJson =
                span.getAttributes().get(AttributeKey.stringKey("braintrust.output_json"));
        assertNotNull(outputJson);

        var outputMessages = JSON_MAPPER.readTree(outputJson);
        assertEquals(1, outputMessages.size());
        var messageZero = outputMessages.get(0);
        assertEquals("msg_test123", messageZero.get("id").asText());
        assertEquals("message", messageZero.get("type").asText());
        assertEquals("assistant", messageZero.get("role").asText());
        assertEquals(
                "The capital of France is Paris.",
                messageZero.get("content").get(0).get("text").asText());
        assertEquals(8, messageZero.get("usage").get("output_tokens").asInt());
        assertEquals(20, messageZero.get("usage").get("input_tokens").asInt());
    }

    @Test
    @SneakyThrows
    void testWrapAnthropicStreaming() {
        // Mock the Anthropic streaming API response
        String streamingResponse =
                """
                event: message_start
                data: {"type":"message_start","message":{"id":"msg_test123","type":"message","role":"assistant","model":"claude-3-5-haiku-20241022","content":[],"stop_reason":null,"stop_sequence":null,"usage":{"input_tokens":20,"output_tokens":1}}}

                event: content_block_start
                data: {"type":"content_block_start","index":0,"content_block":{"type":"text","text":""}}

                event: content_block_delta
                data: {"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"The"}}

                event: content_block_delta
                data: {"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":" capital"}}

                event: content_block_delta
                data: {"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":" of"}}

                event: content_block_delta
                data: {"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":" France"}}

                event: content_block_delta
                data: {"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":" is"}}

                event: content_block_delta
                data: {"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":" Paris"}}

                event: content_block_delta
                data: {"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"."}}

                event: content_block_stop
                data: {"type":"content_block_stop","index":0}

                event: message_delta
                data: {"type":"message_delta","delta":{"stop_reason":"end_turn","stop_sequence":null},"usage":{"output_tokens":8}}

                event: message_stop
                data: {"type":"message_stop"}

                """;

        wireMock.stubFor(
                post(urlEqualTo("/v1/messages"))
                        .willReturn(
                                aResponse()
                                        .withStatus(200)
                                        .withHeader("Content-Type", "text/event-stream")
                                        .withBody(streamingResponse)));

        // Create Anthropic client pointing to WireMock server
        AnthropicClient anthropicClient =
                AnthropicOkHttpClient.builder()
                        .baseUrl("http://localhost:" + wireMock.getPort())
                        .apiKey("test-api-key")
                        .build();

        // Wrap with Braintrust instrumentation
        anthropicClient = BraintrustAnthropic.wrap(testHarness.openTelemetry(), anthropicClient);

        var request =
                MessageCreateParams.builder()
                        .model(Model.CLAUDE_3_5_HAIKU_20241022)
                        .system("You are a helpful assistant")
                        .addUserMessage("What is the capital of France?")
                        .maxTokens(50)
                        .temperature(0.0)
                        .build();

        // Consume the stream
        StringBuilder fullResponse = new StringBuilder();
        try (var stream = anthropicClient.messages().createStreaming(request)) {
            stream.stream()
                    .forEach(
                            event -> {
                                if (event.contentBlockDelta().isPresent()) {
                                    var delta = event.contentBlockDelta().get().delta();
                                    if (delta.text().isPresent()) {
                                        fullResponse.append(delta.text().get().text());
                                    }
                                }
                            });
        }

        // Verify the response
        assertEquals("The capital of France is Paris.", fullResponse.toString());
        wireMock.verify(1, postRequestedFor(urlEqualTo("/v1/messages")));

        // Verify spans were exported
        var spans = testHarness.awaitExportedSpans();
        assertEquals(1, spans.size());
        var span = spans.get(0);

        // Verify standard GenAI attributes
        assertEquals(
                "anthropic", span.getAttributes().get(AttributeKey.stringKey("gen_ai.system")));
        assertEquals(
                "claude-3-5-haiku-20241022",
                span.getAttributes().get(AttributeKey.stringKey("gen_ai.request.model")));
        assertEquals(
                "claude-3-5-haiku-20241022",
                span.getAttributes().get(AttributeKey.stringKey("gen_ai.response.model")));
        assertEquals(
                "chat", span.getAttributes().get(AttributeKey.stringKey("gen_ai.operation.name")));
        assertEquals(
                "msg_test123",
                span.getAttributes().get(AttributeKey.stringKey("gen_ai.response.id")));

        // Verify usage metrics were captured from streaming
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
        assertEquals("assistant", messageZero.get("role").asText());
        assertEquals("The capital of France is Paris.", messageZero.get("content").asText());
    }
}
