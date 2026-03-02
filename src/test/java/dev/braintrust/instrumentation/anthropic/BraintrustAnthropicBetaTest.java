package dev.braintrust.instrumentation.anthropic;

import static org.junit.jupiter.api.Assertions.*;

import com.anthropic.client.AnthropicClient;
import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import com.anthropic.models.beta.messages.MessageCreateParams;
import com.anthropic.models.messages.Model;
import dev.braintrust.TestHarness;
import dev.braintrust.json.BraintrustJsonMapper;
import io.opentelemetry.api.common.AttributeKey;
import lombok.SneakyThrows;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

public class BraintrustAnthropicBetaTest {
    private TestHarness testHarness;

    @BeforeEach
    void beforeEach() {
        testHarness = TestHarness.setup();
    }

    @Test
    @SneakyThrows
    void testWrapAnthropicBeta() {
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

        var response = anthropicClient.beta().messages().create(request);

        assertNotNull(response);
        assertNotNull(response.id());
        var contentBlock = response.content().get(0);
        assertTrue(contentBlock.isText());
        assertNotNull(contentBlock.asText().text());

        var spans = testHarness.awaitExportedSpans();
        assertEquals(1, spans.size());
        var span = spans.get(0);

        assertEquals(
                "anthropic",
                span.getAttributes().get(AttributeKey.stringKey("gen_ai.provider.name")));
        assertEquals(
                "claude-3-haiku-20240307",
                span.getAttributes().get(AttributeKey.stringKey("gen_ai.request.model")));
        assertNotNull(span.getAttributes().get(AttributeKey.stringKey("gen_ai.response.model")));
        assertNotNull(
                span.getAttributes()
                        .get(AttributeKey.stringArrayKey("gen_ai.response.finish_reasons")));
        assertEquals(
                "anthropic.messages.create",
                span.getAttributes().get(AttributeKey.stringKey("gen_ai.operation.name")));
        assertNotNull(span.getAttributes().get(AttributeKey.stringKey("gen_ai.response.id")));
        assertNotNull(span.getAttributes().get(AttributeKey.longKey("gen_ai.usage.input_tokens")));
        assertNotNull(span.getAttributes().get(AttributeKey.longKey("gen_ai.usage.output_tokens")));

        assertNotNull(span.getAttributes().get(AttributeKey.stringKey("braintrust.input_json")));

        String outputJson =
                span.getAttributes().get(AttributeKey.stringKey("braintrust.output_json"));
        assertNotNull(outputJson);
        var outputMessage = BraintrustJsonMapper.get().readTree(outputJson);
        assertNotNull(outputMessage.get("id"));
        assertEquals("message", outputMessage.get("type").asText());
        assertEquals("assistant", outputMessage.get("role").asText());

        Double timeToFirstToken =
                span.getAttributes()
                        .get(AttributeKey.doubleKey("braintrust.metrics.time_to_first_token"));
        assertNotNull(timeToFirstToken, "time_to_first_token should be present");
        assertTrue(timeToFirstToken >= 0.0, "time_to_first_token should be non-negative");
    }

    @Test
    @SneakyThrows
    void testWrapAnthropicBetaStreaming() {
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
        try (var stream = anthropicClient.beta().messages().createStreaming(request)) {
            stream.stream()
                    .forEach(
                            event -> {
                                if (event.contentBlockDelta().isPresent()) {
                                    var delta = event.contentBlockDelta().get();
                                    if (delta.delta().text().isPresent()) {
                                        fullResponse.append(delta.delta().text().get().text());
                                    }
                                }
                            });
        }

        assertFalse(fullResponse.toString().isEmpty());

        var spans = testHarness.awaitExportedSpans();
        assertEquals(1, spans.size());
        var span = spans.get(0);

        assertEquals(
                "anthropic",
                span.getAttributes().get(AttributeKey.stringKey("gen_ai.provider.name")));
        assertEquals(
                "claude-3-haiku-20240307",
                span.getAttributes().get(AttributeKey.stringKey("gen_ai.request.model")));
        assertNotNull(span.getAttributes().get(AttributeKey.stringKey("gen_ai.response.model")));
        assertEquals(
                "anthropic.messages.create",
                span.getAttributes().get(AttributeKey.stringKey("gen_ai.operation.name")));
        assertNotNull(span.getAttributes().get(AttributeKey.stringKey("gen_ai.response.id")));
        assertNotNull(span.getAttributes().get(AttributeKey.longKey("gen_ai.usage.input_tokens")));
        assertNotNull(span.getAttributes().get(AttributeKey.longKey("gen_ai.usage.output_tokens")));

        String outputJson =
                span.getAttributes().get(AttributeKey.stringKey("braintrust.output_json"));
        assertNotNull(outputJson);
        var outputMessages = BraintrustJsonMapper.get().readTree(outputJson);
        assertTrue(outputMessages.size() > 0);
        var messageZero = outputMessages.get(0);
        assertEquals("assistant", messageZero.get("role").asText());
        assertFalse(messageZero.get("content").asText().isEmpty());

        Double timeToFirstToken =
                span.getAttributes()
                        .get(AttributeKey.doubleKey("braintrust.metrics.time_to_first_token"));
        assertNotNull(timeToFirstToken, "time_to_first_token should be present for streaming");
        assertTrue(timeToFirstToken >= 0.0, "time_to_first_token should be non-negative");
    }
}
