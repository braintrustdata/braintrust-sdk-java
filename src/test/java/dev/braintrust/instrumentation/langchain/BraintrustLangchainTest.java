package dev.braintrust.instrumentation.langchain;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.braintrust.TestHarness;
import dev.langchain4j.agent.tool.Tool;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.chat.response.StreamingChatResponseHandler;
import dev.langchain4j.model.openai.OpenAiChatModel;
import dev.langchain4j.model.openai.OpenAiStreamingChatModel;
import dev.langchain4j.service.AiServices;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.sdk.trace.data.SpanData;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import lombok.SneakyThrows;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

public class BraintrustLangchainTest {

    private static final ObjectMapper JSON_MAPPER = new ObjectMapper();

    private TestHarness testHarness;

    @BeforeEach
    void beforeEach() {
        testHarness = TestHarness.setup();
    }

    @Test
    @SneakyThrows
    void typeToolJsonCorrect() {
        assertEquals(
                JSON_MAPPER.writeValueAsString(Map.of("type", "tool")),
                TracingToolExecutor.TYPE_TOOL_JSON);
    }

    @Test
    @SneakyThrows
    void testSyncChatCompletion() {
        ChatModel model =
                BraintrustLangchain.wrap(
                        testHarness.openTelemetry(),
                        OpenAiChatModel.builder()
                                .apiKey(testHarness.openAiApiKey())
                                .baseUrl(testHarness.openAiBaseUrl())
                                .modelName("gpt-4o-mini")
                                .temperature(0.0));

        // Execute chat request
        var message = UserMessage.from("What is the capital of France?");
        var response = model.chat(message);

        // Verify the response
        assertNotNull(response);
        assertNotNull(response.aiMessage().text());

        // Verify spans were exported
        var spans = testHarness.awaitExportedSpans();
        assertEquals(1, spans.size(), "Expected one span for sync chat completion");
        var span = spans.get(0);

        // Verify span name
        assertEquals("Chat Completion", span.getName(), "Span name should be 'Chat Completion'");

        // Verify span attributes
        var attributes = span.getAttributes();
        var braintrustSpanAttributesJson =
                attributes.get(AttributeKey.stringKey("braintrust.span_attributes"));

        // Verify span type
        JsonNode spanAttributes = JSON_MAPPER.readTree(braintrustSpanAttributesJson);
        assertEquals("llm", spanAttributes.get("type").asText(), "Span type should be 'llm'");

        // Verify metadata
        String metadataJson = attributes.get(AttributeKey.stringKey("braintrust.metadata"));
        assertNotNull(metadataJson, "Metadata should be present");
        JsonNode metadata = JSON_MAPPER.readTree(metadataJson);
        assertEquals("openai", metadata.get("provider").asText(), "Provider should be 'openai'");
        assertEquals(
                "gpt-4o-mini", metadata.get("model").asText(), "Model should be 'gpt-4o-mini'");

        // Verify metrics
        String metricsJson = attributes.get(AttributeKey.stringKey("braintrust.metrics"));
        assertNotNull(metricsJson, "Metrics should be present");
        JsonNode metrics = JSON_MAPPER.readTree(metricsJson);
        assertTrue(metrics.get("tokens").asLong() > 0, "Total tokens should be > 0");
        assertTrue(metrics.get("prompt_tokens").asLong() > 0, "Prompt tokens should be > 0");
        assertTrue(
                metrics.get("completion_tokens").asLong() > 0, "Completion tokens should be > 0");
        assertTrue(
                metrics.has("time_to_first_token"), "Metrics should contain time_to_first_token");
        assertTrue(
                metrics.get("time_to_first_token").isNumber(),
                "time_to_first_token should be a number");

        // Verify input
        String inputJson = attributes.get(AttributeKey.stringKey("braintrust.input_json"));
        assertNotNull(inputJson, "Input should be present");
        JsonNode input = JSON_MAPPER.readTree(inputJson);
        assertTrue(input.isArray(), "Input should be an array");
        assertTrue(input.size() > 0, "Input array should not be empty");
        assertTrue(
                input.get(0).get("content").asText().contains("What is the capital of France"),
                "Input should contain the user message");

        // Verify output
        String outputJson = attributes.get(AttributeKey.stringKey("braintrust.output_json"));
        assertNotNull(outputJson, "Output should be present");
        JsonNode output = JSON_MAPPER.readTree(outputJson);
        assertTrue(output.isArray(), "Output should be an array");
        assertTrue(output.size() > 0, "Output array should not be empty");
        assertNotNull(
                output.get(0).get("message").get("content"),
                "Output should contain assistant response content");
    }

    @Test
    @SneakyThrows
    void testStreamingChatCompletion() {
        var tracer = testHarness.openTelemetry().getTracer("test-tracer");

        StreamingChatModel model =
                BraintrustLangchain.wrap(
                        testHarness.openTelemetry(),
                        OpenAiStreamingChatModel.builder()
                                .apiKey(testHarness.openAiApiKey())
                                .baseUrl(testHarness.openAiBaseUrl())
                                .modelName("gpt-4o-mini")
                                .temperature(0.0));

        // Execute streaming chat request
        var future = new CompletableFuture<ChatResponse>();
        var responseBuilder = new StringBuilder();
        var callbackCount = new AtomicInteger(0);

        model.chat(
                "What is the capital of France?",
                new StreamingChatResponseHandler() {
                    @Override
                    public void onPartialResponse(String token) {
                        // Create a child span during the callback to verify parenting
                        Span childSpan =
                                tracer.spanBuilder(
                                                "callback-span-" + callbackCount.incrementAndGet())
                                        .startSpan();
                        childSpan.end();
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
        assertFalse(responseBuilder.toString().isEmpty(), "Response should not be empty");

        // We expect at least 2 spans: 1 LLM span + at least 1 callback span
        int expectedMinSpans = 1 + callbackCount.get();
        var spans = testHarness.awaitExportedSpans(expectedMinSpans);
        assertTrue(
                spans.size() >= expectedMinSpans,
                "Expected at least " + expectedMinSpans + " spans, got " + spans.size());

        // Find the LLM span and callback spans
        SpanData llmSpan = null;
        List<SpanData> callbackSpans = new java.util.ArrayList<>();

        for (var span : spans) {
            if (span.getName().equals("Chat Completion")) {
                llmSpan = span;
            } else if (span.getName().startsWith("callback-span-")) {
                callbackSpans.add(span);
            }
        }

        assertNotNull(llmSpan, "Should have an LLM span named 'Chat Completion'");
        assertEquals(
                callbackCount.get(),
                callbackSpans.size(),
                "Should have one callback span per onPartialResponse invocation");

        // Verify all callback spans are parented under the LLM span
        String llmSpanId = llmSpan.getSpanId();
        for (var callbackSpan : callbackSpans) {
            assertEquals(
                    llmSpanId,
                    callbackSpan.getParentSpanId(),
                    "Callback span '"
                            + callbackSpan.getName()
                            + "' should be parented under LLM span");
        }

        // Verify LLM span attributes
        var attributes = llmSpan.getAttributes();

        var braintrustSpanAttributesJson =
                attributes.get(AttributeKey.stringKey("braintrust.span_attributes"));

        // Verify span type
        JsonNode spanAttributes = JSON_MAPPER.readTree(braintrustSpanAttributesJson);
        assertEquals("llm", spanAttributes.get("type").asText(), "Span type should be 'llm'");

        // Verify metadata
        String metadataJson = attributes.get(AttributeKey.stringKey("braintrust.metadata"));
        assertNotNull(metadataJson, "Metadata should be present");
        JsonNode metadata = JSON_MAPPER.readTree(metadataJson);
        assertEquals("openai", metadata.get("provider").asText(), "Provider should be 'openai'");
        assertEquals(
                "gpt-4o-mini", metadata.get("model").asText(), "Model should be 'gpt-4o-mini'");

        // Verify metrics for streaming
        String metricsJson = attributes.get(AttributeKey.stringKey("braintrust.metrics"));
        assertNotNull(metricsJson, "Metrics should be present");
        JsonNode metrics = JSON_MAPPER.readTree(metricsJson);
        assertTrue(metrics.get("tokens").asLong() > 0, "Total tokens should be > 0");
        assertTrue(metrics.get("prompt_tokens").asLong() > 0, "Prompt tokens should be > 0");
        assertTrue(
                metrics.get("completion_tokens").asLong() > 0, "Completion tokens should be > 0");
        assertTrue(
                metrics.has("time_to_first_token"),
                "Metrics should contain time_to_first_token for streaming");
        assertTrue(
                metrics.get("time_to_first_token").isNumber(),
                "time_to_first_token should be a number");

        // Verify input
        String inputJson = attributes.get(AttributeKey.stringKey("braintrust.input_json"));
        assertNotNull(inputJson, "Input should be present");
        JsonNode input = JSON_MAPPER.readTree(inputJson);
        assertTrue(input.isArray(), "Input should be an array");
        assertTrue(input.size() > 0, "Input array should not be empty");
        assertTrue(
                input.get(0).get("content").asText().contains("What is the capital of France"),
                "Input should contain the user message");

        // Verify output (streaming reconstructs the output)
        String outputJson = attributes.get(AttributeKey.stringKey("braintrust.output_json"));
        assertNotNull(outputJson, "Output should be present");
        JsonNode output = JSON_MAPPER.readTree(outputJson);
        assertTrue(output.isArray(), "Output should be an array");
        assertTrue(output.size() > 0, "Output array should not be empty");
        JsonNode choice = output.get(0);
        assertNotNull(
                choice.get("message").get("content"),
                "Output should contain the complete streamed response");
        assertNotNull(choice.get("finish_reason"), "Output should have finish_reason");
    }

    @Test
    @SneakyThrows
    void testAiServicesWithTools() {
        Assistant assistant =
                BraintrustLangchain.wrap(
                        testHarness.openTelemetry(),
                        AiServices.builder(Assistant.class)
                                .chatModel(
                                        OpenAiChatModel.builder()
                                                .apiKey(testHarness.openAiApiKey())
                                                .baseUrl(testHarness.openAiBaseUrl())
                                                .modelName("gpt-4o-mini")
                                                .temperature(0.0)
                                                .build())
                                .tools(new WeatherTools())
                                .executeToolsConcurrently());

        // This should trigger two (concurrent) tool calls
        var response = assistant.chat("is it hotter in Paris or New York right now?");

        // Verify the response
        assertNotNull(response);

        // Verify spans were exported - should have at least:
        // - one AI service method span ("chat")
        // - at least two LLM calls (one to request the tool calls, and another to analyze)
        // - at least two tool call spans
        var spans = testHarness.awaitExportedSpans(3);
        assertTrue(spans.size() >= 3, "Expected at least 3 spans for AI Services with tools");

        // Verify we have the expected span types
        int numServiceMethodSpans = 0;
        int numLLMSpans = 0;
        int numToolCallSpans = 0;

        for (var span : spans) {
            String spanName = span.getName();
            var attributes = span.getAttributes();

            if (spanName.equals("chat")) {
                numServiceMethodSpans++;
            } else if (spanName.equals("Chat Completion")) {
                numLLMSpans++;
                // Verify LLM span has proper attributes
                var spanAttributesJson =
                        attributes.get(AttributeKey.stringKey("braintrust.span_attributes"));
                assertNotNull(spanAttributesJson, "LLM span should have span_attributes");
                JsonNode spanAttributes = JSON_MAPPER.readTree(spanAttributesJson);
                assertEquals(
                        "llm", spanAttributes.get("type").asText(), "Span type should be 'llm'");
            } else if (spanName.equals("getWeather")) {
                numToolCallSpans++;
                // Verify tool span has proper attributes
                var spanAttributesJson =
                        attributes.get(AttributeKey.stringKey("braintrust.span_attributes"));
                assertNotNull(spanAttributesJson, "Tool span should have span_attributes");
                JsonNode spanAttributes = JSON_MAPPER.readTree(spanAttributesJson);
                assertEquals(
                        "tool", spanAttributes.get("type").asText(), "Span type should be 'tool'");
            }
        }
        assertEquals(1, numServiceMethodSpans, "should be exactly one service call");
        assertTrue(numLLMSpans >= 2, "should be at least two llm spans");
        assertTrue(numToolCallSpans >= 2, "should be at least two tool call spans");
    }

    /** AI Service interface for the assistant */
    interface Assistant {
        String chat(String userMessage);
    }

    /** Example tool class with weather-related methods */
    public static class WeatherTools {
        @Tool("Get current weather for a location")
        public String getWeather(String location) {
            return String.format("The weather in %s is sunny with 72°F temperature.", location);
        }

        @Tool("Get weather forecast for next N days")
        public String getForecast(String location, int days) {
            return String.format(
                    "The %d-day forecast for %s: Mostly sunny with temperatures between 65-75°F.",
                    days, location);
        }
    }
}
