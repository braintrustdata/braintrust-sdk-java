package dev.braintrust.instrumentation.anthropic;

import static org.junit.jupiter.api.Assertions.*;

import com.anthropic.client.AnthropicClient;
import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import com.anthropic.models.messages.MessageCreateParams;
import com.anthropic.models.messages.Model;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.braintrust.TestHarness;
import io.opentelemetry.api.common.AttributeKey;
import lombok.SneakyThrows;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

public class BraintrustAnthropicTest {
    private static final ObjectMapper JSON_MAPPER = new ObjectMapper();

    private TestHarness testHarness;

    @BeforeEach
    void beforeEach() {
        testHarness = TestHarness.setup();
    }

    @Test
    @SneakyThrows
    void testWrapAnthropic() {
        AnthropicClient anthropicClient =
                AnthropicOkHttpClient.builder()
                        .baseUrl(testHarness.anthropicBaseUrl())
                        .apiKey(testHarness.anthropicApiKey())
                        .build();

        anthropicClient = BraintrustAnthropic.wrap(testHarness.openTelemetry(), anthropicClient);

        var request =
                MessageCreateParams.builder()
                        .model(Model.CLAUDE_3_HAIKU_20240307)
                        .system("You are a helpful assistant")
                        .addUserMessage("What is the capital of France?")
                        .maxTokens(50)
                        .temperature(0.0)
                        .build();

        var response = anthropicClient.messages().create(request);

        // Verify the response
        assertNotNull(response);
        assertNotNull(response.id());
        var contentBlock = response.content().get(0);
        assertTrue(contentBlock.isText());
        assertNotNull(contentBlock.asText().text());

        // Verify spans were exported
        var spans = testHarness.awaitExportedSpans();
        assertEquals(1, spans.size());
        var span = spans.get(0);

        assertEquals("anthropic.messages.create", span.getName());

        // Verify span_attributes
        String spanAttributesJson =
                span.getAttributes().get(AttributeKey.stringKey("braintrust.span_attributes"));
        assertNotNull(spanAttributesJson);
        JsonNode spanAttributes = JSON_MAPPER.readTree(spanAttributesJson);
        assertEquals("llm", spanAttributes.get("type").asText());

        // Verify metadata
        String metadataJson =
                span.getAttributes().get(AttributeKey.stringKey("braintrust.metadata"));
        assertNotNull(metadataJson);
        JsonNode metadata = JSON_MAPPER.readTree(metadataJson);
        assertEquals("anthropic", metadata.get("provider").asText());
        assertTrue(
                metadata.get("model").asText().startsWith("claude-3-haiku"),
                "model should start with claude-3-haiku");

        // Verify input
        String inputJson =
                span.getAttributes().get(AttributeKey.stringKey("braintrust.input_json"));
        assertNotNull(inputJson);
        JsonNode input = JSON_MAPPER.readTree(inputJson);
        assertTrue(input.isArray());
        assertTrue(input.size() > 0);

        // Verify output — full Message object
        String outputJson =
                span.getAttributes().get(AttributeKey.stringKey("braintrust.output_json"));
        assertNotNull(outputJson);
        JsonNode outputMessage = JSON_MAPPER.readTree(outputJson);
        assertNotNull(outputMessage.get("id"));
        assertEquals("message", outputMessage.get("type").asText());
        assertEquals("assistant", outputMessage.get("role").asText());
        assertNotNull(outputMessage.get("content").get(0).get("text"));
        assertTrue(outputMessage.get("usage").get("output_tokens").asInt() > 0);
        assertTrue(outputMessage.get("usage").get("input_tokens").asInt() > 0);

        // Verify metrics — tokens; non-streaming should NOT have time_to_first_token
        String metricsJson = span.getAttributes().get(AttributeKey.stringKey("braintrust.metrics"));
        assertNotNull(metricsJson);
        JsonNode metrics = JSON_MAPPER.readTree(metricsJson);
        assertTrue(metrics.has("prompt_tokens"), "prompt_tokens should be present");
        assertTrue(metrics.has("completion_tokens"), "completion_tokens should be present");
        assertTrue(metrics.has("tokens"), "tokens should be present");
        assertFalse(
                metrics.has("time_to_first_token"),
                "time_to_first_token should not be present for non-streaming");
    }

    @Test
    @SneakyThrows
    void testWrapAnthropicStreaming() {
        AnthropicClient anthropicClient =
                AnthropicOkHttpClient.builder()
                        .baseUrl(testHarness.anthropicBaseUrl())
                        .apiKey(testHarness.anthropicApiKey())
                        .build();

        anthropicClient = BraintrustAnthropic.wrap(testHarness.openTelemetry(), anthropicClient);

        var request =
                MessageCreateParams.builder()
                        .model(Model.CLAUDE_3_HAIKU_20240307)
                        .system("You are a helpful assistant")
                        .addUserMessage("What is the capital of France?")
                        .maxTokens(50)
                        .temperature(0.0)
                        .build();

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

        assertFalse(fullResponse.toString().isEmpty());

        var spans = testHarness.awaitExportedSpans();
        assertEquals(1, spans.size());
        var span = spans.get(0);

        assertEquals("anthropic.messages.create", span.getName());

        // Verify metadata
        String metadataJson =
                span.getAttributes().get(AttributeKey.stringKey("braintrust.metadata"));
        assertNotNull(metadataJson);
        JsonNode metadata = JSON_MAPPER.readTree(metadataJson);
        assertEquals("anthropic", metadata.get("provider").asText());

        // Verify input
        assertNotNull(span.getAttributes().get(AttributeKey.stringKey("braintrust.input_json")));

        // Verify output — full Message object assembled from SSE stream
        String outputJson =
                span.getAttributes().get(AttributeKey.stringKey("braintrust.output_json"));
        assertNotNull(outputJson);
        JsonNode outputMessage = JSON_MAPPER.readTree(outputJson);
        assertEquals("assistant", outputMessage.get("role").asText());
        assertFalse(
                outputMessage.get("content").get(0).get("text").asText().isEmpty(),
                "content should not be empty");

        // Verify metrics — tokens and time_to_first_token
        String metricsJson = span.getAttributes().get(AttributeKey.stringKey("braintrust.metrics"));
        assertNotNull(metricsJson);
        JsonNode metrics = JSON_MAPPER.readTree(metricsJson);
        assertTrue(metrics.has("prompt_tokens"), "prompt_tokens should be present");
        assertTrue(metrics.has("completion_tokens"), "completion_tokens should be present");
        assertTrue(metrics.has("time_to_first_token"), "time_to_first_token should be present");
        assertTrue(
                metrics.get("time_to_first_token").asDouble() >= 0.0,
                "time_to_first_token should be non-negative");
    }
}
