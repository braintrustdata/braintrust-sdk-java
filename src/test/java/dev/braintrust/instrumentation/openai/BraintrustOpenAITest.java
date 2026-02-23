package dev.braintrust.instrumentation.openai;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.openai.client.OpenAIClient;
import com.openai.client.okhttp.OpenAIOkHttpClient;
import com.openai.models.ChatModel;
import com.openai.models.chat.completions.*;
import dev.braintrust.TestHarness;
import io.opentelemetry.api.common.AttributeKey;
import lombok.SneakyThrows;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

public class BraintrustOpenAITest {
    private static final ObjectMapper JSON_MAPPER = new ObjectMapper();

    private TestHarness testHarness;

    @BeforeEach
    void beforeEach() {
        testHarness = TestHarness.setup();
    }

    @Test
    @SneakyThrows
    void testWrapOpenAi() {
        // Create OpenAI client using TestHarness configuration
        // TestHarness automatically provides the correct base URL and API key
        OpenAIClient openAIClient =
                OpenAIOkHttpClient.builder()
                        .baseUrl(testHarness.openAiBaseUrl())
                        .apiKey(testHarness.openAiApiKey())
                        .build();

        // Wrap with Braintrust instrumentation
        openAIClient = BraintrustOpenAI.wrapOpenAI(testHarness.openTelemetry(), openAIClient);

        var request =
                ChatCompletionCreateParams.builder()
                        .model(ChatModel.GPT_4O_MINI)
                        .addSystemMessage("You are a helpful assistant")
                        .addUserMessage("What is the capital of France?")
                        .temperature(0.0)
                        .build();

        var response = openAIClient.chat().completions().create(request);

        // Verify the response (same assertions work for both modes)
        assertNotNull(response);
        assertNotNull(response.id());
        assertTrue(response.choices().get(0).message().content().isPresent());
        String content = response.choices().get(0).message().content().get();
        assertTrue(content.toLowerCase().contains("paris"), "Response should mention Paris");

        // Verify spans were exported
        var spans = testHarness.awaitExportedSpans();
        assertEquals(1, spans.size());
        var span = spans.get(0);

        // Verify span name matches other SDKs
        assertEquals("Chat Completion", span.getName());

        // Verify essential span attributes
        assertEquals(
                "openai", span.getAttributes().get(AttributeKey.stringKey("gen_ai.provider.name")));
        assertEquals(
                "gpt-4o-mini",
                span.getAttributes().get(AttributeKey.stringKey("gen_ai.request.model")));
        assertNotNull(span.getAttributes().get(AttributeKey.stringKey("gen_ai.response.model")));
        assertEquals(
                "chat", span.getAttributes().get(AttributeKey.stringKey("gen_ai.operation.name")));
        assertNotNull(span.getAttributes().get(AttributeKey.stringKey("gen_ai.response.id")));

        // Verify usage metrics exist
        assertNotNull(span.getAttributes().get(AttributeKey.longKey("gen_ai.usage.input_tokens")));
        assertNotNull(span.getAttributes().get(AttributeKey.longKey("gen_ai.usage.output_tokens")));

        // Verify time to first token metric was captured
        Double timeToFirstToken =
                span.getAttributes()
                        .get(AttributeKey.doubleKey("braintrust.metrics.time_to_first_token"));
        assertNotNull(timeToFirstToken, "time_to_first_token should be set");
        assertTrue(timeToFirstToken >= 0.0, "time_to_first_token should be non-negative");

        // Verify Braintrust metadata
        assertEquals(
                "openai",
                span.getAttributes().get(AttributeKey.stringKey("braintrust.metadata.provider")));
        assertEquals(
                "gpt-4o-mini",
                span.getAttributes().get(AttributeKey.stringKey("braintrust.metadata.model")));

        // Verify output messages structure
        String outputJson =
                span.getAttributes().get(AttributeKey.stringKey("gen_ai.output.messages"));
        assertNotNull(outputJson);
        var outputMessages = JSON_MAPPER.readTree(outputJson);
        assertEquals(1, outputMessages.size());
        var outputMessage = outputMessages.get(0);
        assertEquals("assistant", outputMessage.get("role").asText());
        assertNotNull(outputMessage.get("finish_reason"));
    }

    @Test
    @SneakyThrows
    void testWrapOpenAiStreaming() {
        OpenAIClient openAIClient =
                OpenAIOkHttpClient.builder()
                        .baseUrl(testHarness.openAiBaseUrl())
                        .apiKey(testHarness.openAiApiKey())
                        .build();

        openAIClient = BraintrustOpenAI.wrapOpenAI(testHarness.openTelemetry(), openAIClient);

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
        assertFalse(fullResponse.isEmpty(), "Should have received streaming response");
        assertTrue(
                fullResponse.toString().toLowerCase().contains("paris"),
                "Response should mention Paris");

        // Verify spans
        var spans = testHarness.awaitExportedSpans();
        assertEquals(1, spans.size());
        var span = spans.get(0);

        assertEquals("Chat Completion", span.getName());
        assertEquals(
                "openai", span.getAttributes().get(AttributeKey.stringKey("gen_ai.provider.name")));
        assertNotNull(
                span.getAttributes()
                        .get(AttributeKey.doubleKey("braintrust.metrics.time_to_first_token")));
    }
}
