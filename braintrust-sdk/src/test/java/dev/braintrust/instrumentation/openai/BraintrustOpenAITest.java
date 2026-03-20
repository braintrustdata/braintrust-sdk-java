package dev.braintrust.instrumentation.openai;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.openai.client.OpenAIClient;
import com.openai.client.okhttp.OpenAIOkHttpClient;
import com.openai.models.ChatModel;
import com.openai.models.Reasoning;
import com.openai.models.ReasoningEffort;
import com.openai.models.chat.completions.*;
import com.openai.models.responses.EasyInputMessage;
import com.openai.models.responses.ResponseCreateParams;
import com.openai.models.responses.ResponseInputItem;
import dev.braintrust.TestHarness;
import io.opentelemetry.api.common.AttributeKey;
import java.util.List;
import java.util.concurrent.TimeUnit;
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

        // Verify span_attributes JSON
        String spanAttributesJson =
                span.getAttributes().get(AttributeKey.stringKey("braintrust.span_attributes"));
        assertNotNull(spanAttributesJson);
        JsonNode spanAttributes = JSON_MAPPER.readTree(spanAttributesJson);
        assertEquals("llm", spanAttributes.get("type").asText());

        // Verify braintrust.metadata JSON
        String metadataJson =
                span.getAttributes().get(AttributeKey.stringKey("braintrust.metadata"));
        assertNotNull(metadataJson);
        JsonNode metadata = JSON_MAPPER.readTree(metadataJson);
        assertEquals("openai", metadata.get("provider").asText());
        assertTrue(
                metadata.get("model").asText().startsWith("gpt-4o-mini"),
                "model should start with gpt-4o-mini");

        // Verify braintrust.metrics JSON (tokens)
        String metricsJson = span.getAttributes().get(AttributeKey.stringKey("braintrust.metrics"));
        assertNotNull(metricsJson);
        JsonNode metrics = JSON_MAPPER.readTree(metricsJson);
        assertTrue(metrics.has("prompt_tokens"), "prompt_tokens should be present");
        assertTrue(
                metrics.get("prompt_tokens").asInt() >= 0, "prompt_tokens should be non-negative");
        assertTrue(metrics.has("completion_tokens"), "completion_tokens should be present");
        assertTrue(
                metrics.get("completion_tokens").asInt() >= 0,
                "completion_tokens should be non-negative");
        assertTrue(metrics.has("tokens"), "tokens should be present");
        assertTrue(metrics.get("tokens").asInt() >= 0, "tokens should be non-negative");
        assertFalse(
                metrics.has("time_to_first_token"),
                "time_to_first_token should not be present for non-streaming");

        // Verify output (choices array)
        String outputJson =
                span.getAttributes().get(AttributeKey.stringKey("braintrust.output_json"));
        assertNotNull(outputJson);
        var outputChoices = JSON_MAPPER.readTree(outputJson);
        assertEquals(1, outputChoices.size());
        var choice = outputChoices.get(0);
        assertEquals("assistant", choice.get("message").get("role").asText());
        assertNotNull(choice.get("finish_reason"));
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

        // Verify braintrust.metadata has provider=openai
        String metadataJson =
                span.getAttributes().get(AttributeKey.stringKey("braintrust.metadata"));
        assertNotNull(metadataJson);
        JsonNode metadata = JSON_MAPPER.readTree(metadataJson);
        assertEquals("openai", metadata.get("provider").asText());

        // Verify time_to_first_token is in braintrust.metrics JSON
        String metricsJson = span.getAttributes().get(AttributeKey.stringKey("braintrust.metrics"));
        assertNotNull(metricsJson);
        JsonNode metrics = JSON_MAPPER.readTree(metricsJson);
        assertTrue(metrics.has("time_to_first_token"), "time_to_first_token should be present");
        assertTrue(
                metrics.get("time_to_first_token").asDouble() >= 0.0,
                "time_to_first_token should be non-negative");
    }

    @Test
    @SneakyThrows
    void testWrapOpenAiResponses() {
        OpenAIClient openAIClient =
                OpenAIOkHttpClient.builder()
                        .baseUrl(testHarness.openAiBaseUrl())
                        .apiKey(testHarness.openAiApiKey())
                        .build();

        openAIClient = BraintrustOpenAI.wrapOpenAI(testHarness.openTelemetry(), openAIClient);

        var inputMsg =
                EasyInputMessage.builder()
                        .role(EasyInputMessage.Role.USER)
                        .content("What is the capital of France? Reply in one word.")
                        .build();

        var request =
                ResponseCreateParams.builder()
                        .model("o4-mini")
                        .reasoning(
                                Reasoning.builder()
                                        .effort(ReasoningEffort.LOW)
                                        .summary(Reasoning.Summary.AUTO)
                                        .build())
                        .inputOfResponse(List.of(ResponseInputItem.ofEasyInputMessage(inputMsg)))
                        .build();

        var response = openAIClient.responses().create(request);

        assertNotNull(response);
        assertNotNull(response.id());
        assertFalse(response.output().isEmpty(), "Response should have output items");

        var spans = testHarness.awaitExportedSpans();
        assertEquals(1, spans.size());
        var span = spans.get(0);

        // Span name for /v1/responses endpoint
        assertEquals("responses", span.getName());

        // span_attributes type=llm
        String spanAttributesJson =
                span.getAttributes().get(AttributeKey.stringKey("braintrust.span_attributes"));
        assertNotNull(spanAttributesJson);
        JsonNode spanAttributes = JSON_MAPPER.readTree(spanAttributesJson);
        assertEquals("llm", spanAttributes.get("type").asText());

        // metadata: provider and model
        String metadataJson =
                span.getAttributes().get(AttributeKey.stringKey("braintrust.metadata"));
        assertNotNull(metadataJson);
        JsonNode metadata = JSON_MAPPER.readTree(metadataJson);
        assertEquals("openai", metadata.get("provider").asText());
        assertTrue(metadata.get("model").asText().startsWith("o4-mini"));

        // input_json: captured from "input" array
        String inputJson =
                span.getAttributes().get(AttributeKey.stringKey("braintrust.input_json"));
        assertNotNull(inputJson, "braintrust.input_json should be set");
        JsonNode inputItems = JSON_MAPPER.readTree(inputJson);
        assertTrue(inputItems.isArray() && inputItems.size() > 0);
        assertEquals("user", inputItems.get(0).get("role").asText());

        // output_json: captured from "output" array
        String outputJson =
                span.getAttributes().get(AttributeKey.stringKey("braintrust.output_json"));
        assertNotNull(outputJson, "braintrust.output_json should be set");
        JsonNode outputItems = JSON_MAPPER.readTree(outputJson);
        assertTrue(outputItems.isArray() && outputItems.size() > 0);

        // metrics: tokens from Responses API usage fields
        String metricsJson = span.getAttributes().get(AttributeKey.stringKey("braintrust.metrics"));
        assertNotNull(metricsJson);
        JsonNode metrics = JSON_MAPPER.readTree(metricsJson);
        assertTrue(metrics.has("prompt_tokens"), "prompt_tokens should be present");
        assertTrue(metrics.get("prompt_tokens").asInt() >= 0);
        assertTrue(metrics.has("completion_tokens"), "completion_tokens should be present");
        assertTrue(metrics.get("completion_tokens").asInt() >= 0);
        assertTrue(metrics.has("tokens"), "tokens should be present");
        assertTrue(metrics.get("tokens").asInt() >= 0);
        assertTrue(
                metrics.has("completion_reasoning_tokens"),
                "completion_reasoning_tokens should be present");
        assertTrue(metrics.get("completion_reasoning_tokens").asInt() >= 0);
    }

    @Test
    @SneakyThrows
    void testWrapOpenAiAsync() {
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
                        .build();

        var response = openAIClient.async().chat().completions().create(request).get();

        assertNotNull(response);
        assertNotNull(response.id());
        assertTrue(response.choices().get(0).message().content().isPresent());
        String content = response.choices().get(0).message().content().get();
        assertTrue(content.toLowerCase().contains("paris"), "Response should mention Paris");

        var spans = testHarness.awaitExportedSpans();
        assertEquals(1, spans.size());
        var span = spans.get(0);

        assertEquals("Chat Completion", span.getName());

        String spanAttributesJson =
                span.getAttributes().get(AttributeKey.stringKey("braintrust.span_attributes"));
        assertNotNull(spanAttributesJson);
        JsonNode spanAttributes = JSON_MAPPER.readTree(spanAttributesJson);
        assertEquals("llm", spanAttributes.get("type").asText());

        String metadataJson =
                span.getAttributes().get(AttributeKey.stringKey("braintrust.metadata"));
        assertNotNull(metadataJson);
        JsonNode metadata = JSON_MAPPER.readTree(metadataJson);
        assertEquals("openai", metadata.get("provider").asText());

        String outputJson =
                span.getAttributes().get(AttributeKey.stringKey("braintrust.output_json"));
        assertNotNull(outputJson);
        var outputChoices = JSON_MAPPER.readTree(outputJson);
        assertEquals(1, outputChoices.size());
        assertEquals("assistant", outputChoices.get(0).get("message").get("role").asText());

        String metricsJson = span.getAttributes().get(AttributeKey.stringKey("braintrust.metrics"));
        assertNotNull(metricsJson);
        JsonNode metrics = JSON_MAPPER.readTree(metricsJson);
        assertTrue(metrics.has("prompt_tokens"));
        assertTrue(metrics.has("completion_tokens"));
        assertTrue(metrics.has("tokens"));
        assertFalse(
                metrics.has("time_to_first_token"),
                "time_to_first_token should not be present for non-streaming");
    }

    @Test
    @SneakyThrows
    void testWrapOpenAiAsyncStreaming() {
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

        var fullResponse = new StringBuilder();
        var stream = openAIClient.async().chat().completions().createStreaming(request);
        stream.subscribe(
                chunk -> {
                    if (!chunk.choices().isEmpty()) {
                        chunk.choices().get(0).delta().content().ifPresent(fullResponse::append);
                    }
                });
        stream.onCompleteFuture().get(30, TimeUnit.SECONDS);

        assertFalse(fullResponse.toString().isEmpty());
        assertTrue(fullResponse.toString().toLowerCase().contains("paris"));

        var spans = testHarness.awaitExportedSpans();
        assertEquals(1, spans.size());
        var span = spans.get(0);

        assertEquals("Chat Completion", span.getName());

        String metadataJson =
                span.getAttributes().get(AttributeKey.stringKey("braintrust.metadata"));
        assertNotNull(metadataJson);
        JsonNode metadata = JSON_MAPPER.readTree(metadataJson);
        assertEquals("openai", metadata.get("provider").asText());

        assertNotNull(span.getAttributes().get(AttributeKey.stringKey("braintrust.input_json")));

        String outputJson =
                span.getAttributes().get(AttributeKey.stringKey("braintrust.output_json"));
        assertNotNull(outputJson);
        var outputChoices = JSON_MAPPER.readTree(outputJson);
        assertEquals(1, outputChoices.size());
        assertEquals("assistant", outputChoices.get(0).get("message").get("role").asText());

        String metricsJson = span.getAttributes().get(AttributeKey.stringKey("braintrust.metrics"));
        assertNotNull(metricsJson);
        JsonNode metrics = JSON_MAPPER.readTree(metricsJson);
        assertTrue(metrics.has("prompt_tokens"));
        assertTrue(metrics.has("completion_tokens"));
        assertTrue(metrics.has("tokens"));
        assertTrue(
                metrics.has("time_to_first_token"),
                "time_to_first_token should be present for streaming");
        assertTrue(metrics.get("time_to_first_token").asDouble() >= 0.0);
    }
}
