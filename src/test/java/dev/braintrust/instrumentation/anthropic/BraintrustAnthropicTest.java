package dev.braintrust.instrumentation.anthropic;

import static org.junit.jupiter.api.Assertions.*;

import com.anthropic.client.AnthropicClient;
import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import com.anthropic.models.messages.MessageCreateParams;
import com.anthropic.models.messages.Model;
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
        // Create Anthropic client using VCR
        AnthropicClient anthropicClient =
                AnthropicOkHttpClient.builder()
                        .baseUrl(testHarness.anthropicBaseUrl())
                        .apiKey(testHarness.anthropicApiKey())
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
        assertNotNull(response.id());
        var contentBlock = response.content().get(0);
        assertTrue(contentBlock.isText());
        assertNotNull(contentBlock.asText().text());

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
        assertNotNull(span.getAttributes().get(AttributeKey.stringKey("gen_ai.response.model")));
        assertNotNull(
                span.getAttributes()
                        .get(AttributeKey.stringArrayKey("gen_ai.response.finish_reasons")));
        assertEquals(
                "anthropic.messages.create",
                span.getAttributes().get(AttributeKey.stringKey("gen_ai.operation.name")));
        assertNotNull(span.getAttributes().get(AttributeKey.stringKey("gen_ai.response.id")));
        assertEquals(
                "project_name:" + TestHarness.defaultProjectName(),
                span.getAttributes().get(AttributeKey.stringKey("braintrust.parent")));
        assertNotNull(span.getAttributes().get(AttributeKey.longKey("gen_ai.usage.input_tokens")));
        assertNotNull(span.getAttributes().get(AttributeKey.longKey("gen_ai.usage.output_tokens")));
        assertEquals(
                0.0,
                span.getAttributes().get(AttributeKey.doubleKey("gen_ai.request.temperature")));
        assertEquals(
                50L, span.getAttributes().get(AttributeKey.longKey("gen_ai.request.max_tokens")));

        // Verify Braintrust-specific attributes
        assertNotNull(span.getAttributes().get(AttributeKey.stringKey("braintrust.input_json")));

        // Verify output JSON
        String outputJson =
                span.getAttributes().get(AttributeKey.stringKey("braintrust.output_json"));
        assertNotNull(outputJson);

        var outputMessage = JSON_MAPPER.readTree(outputJson);
        assertNotNull(outputMessage.get("id"));
        assertEquals("message", outputMessage.get("type").asText());
        assertEquals("assistant", outputMessage.get("role").asText());
        assertNotNull(outputMessage.get("content").get(0).get("text"));
        assertTrue(outputMessage.get("usage").get("output_tokens").asInt() > 0);
        assertTrue(outputMessage.get("usage").get("input_tokens").asInt() > 0);

        // Verify time to first token
        Double timeToFirstToken =
                span.getAttributes()
                        .get(AttributeKey.doubleKey("braintrust.metrics.time_to_first_token"));
        assertNotNull(timeToFirstToken, "time_to_first_token should be present");
        assertTrue(timeToFirstToken >= 0.0, "time_to_first_token should be non-negative");
    }

    @Test
    @SneakyThrows
    void testWrapAnthropicStreaming() {
        // Create Anthropic client using VCR
        AnthropicClient anthropicClient =
                AnthropicOkHttpClient.builder()
                        .baseUrl(testHarness.anthropicBaseUrl())
                        .apiKey(testHarness.anthropicApiKey())
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
        assertFalse(fullResponse.toString().isEmpty());

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
        assertNotNull(span.getAttributes().get(AttributeKey.stringKey("gen_ai.response.model")));
        assertEquals(
                "anthropic.messages.create",
                span.getAttributes().get(AttributeKey.stringKey("gen_ai.operation.name")));
        assertNotNull(span.getAttributes().get(AttributeKey.stringKey("gen_ai.response.id")));

        // Verify usage metrics were captured from streaming
        assertNotNull(span.getAttributes().get(AttributeKey.longKey("gen_ai.usage.input_tokens")));
        assertNotNull(span.getAttributes().get(AttributeKey.longKey("gen_ai.usage.output_tokens")));

        // Verify output JSON was captured
        String outputJson =
                span.getAttributes().get(AttributeKey.stringKey("braintrust.output_json"));
        assertNotNull(outputJson);
        var outputMessages = JSON_MAPPER.readTree(outputJson);
        assertTrue(outputMessages.size() > 0);
        var messageZero = outputMessages.get(0);
        assertEquals("assistant", messageZero.get("role").asText());
        assertFalse(messageZero.get("content").asText().isEmpty());

        // Verify time to first token
        Double timeToFirstToken =
                span.getAttributes()
                        .get(AttributeKey.doubleKey("braintrust.metrics.time_to_first_token"));
        assertNotNull(timeToFirstToken, "time_to_first_token should be present for streaming");
        assertTrue(timeToFirstToken >= 0.0, "time_to_first_token should be non-negative");
    }
}
