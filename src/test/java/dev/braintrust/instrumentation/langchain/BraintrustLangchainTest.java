package dev.braintrust.instrumentation.langchain;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.braintrust.TestHarness;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.chat.response.StreamingChatResponseHandler;
import dev.langchain4j.model.openai.OpenAiChatModel;
import dev.langchain4j.model.openai.OpenAiStreamingChatModel;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.trace.StatusCode;
import java.util.List;
import java.util.concurrent.CompletableFuture;
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
        // Create LangChain4j streaming client with Braintrust instrumentation using VCR
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
        assertFalse(responseBuilder.toString().isEmpty(), "Response should not be empty");

        // Verify spans were exported
        var spans = testHarness.awaitExportedSpans(1);
        assertEquals(1, spans.size(), "Expected one span for streaming chat completion");
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
    void testToolWrapping() {
        // Create and wrap tools
        TestTools tools = new TestTools();
        TestTools wrappedTools = BraintrustLangchain.wrapTools(testHarness.openTelemetry(), tools);

        // Call wrapped tool method directly
        String result = wrappedTools.getWeather("Paris");
        assertNotNull(result);
        assertTrue(result.contains("Paris"));

        // Verify span was created
        var spans = testHarness.awaitExportedSpans();
        assertEquals(1, spans.size(), "Expected one span for tool execution");
        var span = spans.get(0);

        // Verify span name
        assertEquals("getWeather", span.getName());

        // Verify span type
        var attributes = span.getAttributes();
        String spanAttrsJson = attributes.get(AttributeKey.stringKey("braintrust.span_attributes"));
        JsonNode spanAttrs = JSON_MAPPER.readTree(spanAttrsJson);
        assertEquals("tool", spanAttrs.get("type").asText());
        assertEquals("getWeather", spanAttrs.get("name").asText());

        // Verify input (parameter names may be arg0, arg1, etc without -parameters flag)
        String inputJson = attributes.get(AttributeKey.stringKey("braintrust.input_json"));
        assertNotNull(inputJson);
        JsonNode input = JSON_MAPPER.readTree(inputJson);
        assertTrue(input.isObject(), "Input should be an object");
        assertTrue(input.size() > 0, "Input should have at least one parameter");
        // Check if parameter value is present (either as "location" or "arg0")
        String paramValue =
                input.has("location")
                        ? input.get("location").asText()
                        : input.elements().next().asText();
        assertEquals("Paris", paramValue);

        // Verify output
        String outputJson = attributes.get(AttributeKey.stringKey("braintrust.output_json"));
        assertNotNull(outputJson);
        JsonNode output = JSON_MAPPER.readTree(outputJson);
        assertTrue(output.asText().contains("Paris"));
        assertTrue(output.asText().contains("72"));

        // Verify metrics
        String metricsJson = attributes.get(AttributeKey.stringKey("braintrust.metrics"));
        JsonNode metrics = JSON_MAPPER.readTree(metricsJson);
        assertTrue(metrics.has("execution_time"));
        assertTrue(metrics.get("execution_time").asDouble() >= 0);
    }

    @Test
    @SneakyThrows
    void testToolWrappingWithException() {
        TestTools tools = new TestTools();
        TestTools wrappedTools = BraintrustLangchain.wrapTools(testHarness.openTelemetry(), tools);

        // Execute and expect exception
        assertThrows(
                RuntimeException.class,
                () -> {
                    wrappedTools.throwError();
                });

        // Verify span with error status
        var spans = testHarness.awaitExportedSpans();
        assertEquals(1, spans.size());
        var span = spans.get(0);

        assertEquals(StatusCode.ERROR, span.getStatus().getStatusCode());
        assertTrue(
                span.getEvents().stream().anyMatch(e -> e.getName().equals("exception")),
                "Span should have exception event");
    }

    @Test
    @SneakyThrows
    void testToolWrappingWithMultipleCalls() {
        TestTools tools = new TestTools();
        TestTools wrappedTools = BraintrustLangchain.wrapTools(testHarness.openTelemetry(), tools);

        // Call multiple tool methods
        wrappedTools.getWeather("Tokyo");
        int sum = wrappedTools.calculateSum(5, 7);
        assertEquals(12, sum);

        // Verify two spans created
        var spans = testHarness.awaitExportedSpans();
        assertEquals(2, spans.size(), "Expected two spans");

        // Verify first span (getWeather)
        var weatherSpan = spans.get(0);
        assertEquals("getWeather", weatherSpan.getName());

        // Verify second span (calculateSum)
        var sumSpan = spans.get(1);
        assertEquals("calculateSum", sumSpan.getName());
        String sumOutput =
                sumSpan.getAttributes().get(AttributeKey.stringKey("braintrust.output_json"));
        assertEquals("12", sumOutput);
    }

    @Test
    @SneakyThrows
    void testToolWrappingAllBraintrustAttributesPresent() {
        // This test verifies ALL required Braintrust attributes are present
        // to catch issues that would cause UI problems
        TestTools tools = new TestTools();
        TestTools wrappedTools = BraintrustLangchain.wrapTools(testHarness.openTelemetry(), tools);

        wrappedTools.getWeather("London");

        var spans = testHarness.awaitExportedSpans();
        var span = spans.get(0);
        var attributes = span.getAttributes();

        // CRITICAL: These attributes MUST be present for Braintrust UI to display properly
        assertNotNull(
                attributes.get(AttributeKey.stringKey("braintrust.span_attributes")),
                "braintrust.span_attributes is required for UI");
        assertNotNull(
                attributes.get(AttributeKey.stringKey("braintrust.input_json")),
                "braintrust.input_json is required for UI to show inputs");
        assertNotNull(
                attributes.get(AttributeKey.stringKey("braintrust.output_json")),
                "braintrust.output_json is required for UI to show outputs");
        assertNotNull(
                attributes.get(AttributeKey.stringKey("braintrust.metrics")),
                "braintrust.metrics is required for UI to show metrics");

        // Verify span_attributes has correct structure
        String spanAttrsJson = attributes.get(AttributeKey.stringKey("braintrust.span_attributes"));
        JsonNode spanAttrs = JSON_MAPPER.readTree(spanAttrsJson);
        assertTrue(spanAttrs.has("type"), "span_attributes must have 'type' field");
        assertTrue(spanAttrs.has("name"), "span_attributes must have 'name' field");
        assertEquals("tool", spanAttrs.get("type").asText(), "Tool spans must have type='tool'");
    }

    @Test
    @SneakyThrows
    void testToolWrappingIntegrationWithConversationHierarchy() {
        // This test simulates a realistic usage pattern like the example
        // and verifies ALL spans in the hierarchy have input/output for UI
        var tracer = testHarness.openTelemetry().getTracer("test");
        TestTools tools = new TestTools();
        TestTools wrappedTools = BraintrustLangchain.wrapTools(testHarness.openTelemetry(), tools);

        // Create conversation span (like example does)
        var conversationSpan = tracer.spanBuilder("conversation").startSpan();
        conversationSpan.setAttribute(
                "braintrust.span_attributes", "{\"type\":\"task\",\"name\":\"conversation\"}");
        conversationSpan.setAttribute(
                "braintrust.input_json", "{\"description\":\"test conversation\"}");

        try (var ignored = conversationSpan.makeCurrent()) {
            // Create turn span (like example does)
            var turnSpan = tracer.spanBuilder("turn_1").startSpan();
            turnSpan.setAttribute(
                    "braintrust.span_attributes", "{\"type\":\"task\",\"name\":\"turn_1\"}");
            turnSpan.setAttribute("braintrust.input_json", "{\"user_message\":\"test query\"}");

            try (var turnScope = turnSpan.makeCurrent()) {
                // Call tool within turn (tool wrapper should create tool span)
                String result = wrappedTools.getWeather("Paris");
                turnSpan.setAttribute(
                        "braintrust.output_json", "{\"assistant_message\":\"" + result + "\"}");
            } finally {
                turnSpan.end();
            }
        } finally {
            conversationSpan.setAttribute("braintrust.output_json", "{\"status\":\"completed\"}");
            conversationSpan.end();
        }

        // Verify all 3 spans exist and have required attributes
        var spans = testHarness.awaitExportedSpans();
        assertEquals(3, spans.size(), "Expected 3 spans: conversation, turn, and tool");

        // Find each span type
        var toolSpan =
                spans.stream().filter(s -> s.getName().equals("getWeather")).findFirst().get();
        var turnSpanData =
                spans.stream().filter(s -> s.getName().equals("turn_1")).findFirst().get();
        var convSpan =
                spans.stream().filter(s -> s.getName().equals("conversation")).findFirst().get();

        // CRITICAL: Every span must have input/output for UI to display properly
        for (var span : List.of(toolSpan, turnSpanData, convSpan)) {
            var attrs = span.getAttributes();
            assertNotNull(
                    attrs.get(AttributeKey.stringKey("braintrust.span_attributes")),
                    span.getName() + " missing braintrust.span_attributes");
            assertNotNull(
                    attrs.get(AttributeKey.stringKey("braintrust.input_json")),
                    span.getName() + " missing braintrust.input_json - UI won't show input!");
            assertNotNull(
                    attrs.get(AttributeKey.stringKey("braintrust.output_json")),
                    span.getName() + " missing braintrust.output_json - UI won't show output!");
        }

        // Verify span hierarchy (parent-child relationships)
        assertEquals(
                convSpan.getSpanId(),
                turnSpanData.getParentSpanId(),
                "Turn should be child of conversation");
        assertEquals(
                turnSpanData.getSpanId(),
                toolSpan.getParentSpanId(),
                "Tool should be child of turn");
    }
}
