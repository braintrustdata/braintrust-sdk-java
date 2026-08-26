package dev.braintrust.instrumentation.langchain.v1_14_0;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.braintrust.TestHarness;
import dev.braintrust.instrumentation.Instrumenter;
import dev.langchain4j.agent.tool.Tool;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.http.client.sse.ServerSentEvent;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.request.json.JsonObjectSchema;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.chat.response.StreamingChatResponseHandler;
import dev.langchain4j.model.openai.OpenAiChatModel;
import dev.langchain4j.model.openai.OpenAiResponsesChatModel;
import dev.langchain4j.model.openai.OpenAiResponsesStreamingChatModel;
import dev.langchain4j.model.openai.OpenAiStreamingChatModel;
import dev.langchain4j.service.AiServices;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.sdk.trace.data.SpanData;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import lombok.SneakyThrows;
import net.bytebuddy.agent.ByteBuddyAgent;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

public class BraintrustLangchainTest {

    private static final ObjectMapper JSON_MAPPER = new ObjectMapper();

    @BeforeAll
    public static void beforeAll() {
        var instrumentation = ByteBuddyAgent.install();
        Instrumenter.install(instrumentation, BraintrustLangchainTest.class.getClassLoader());
    }

    private TestHarness testHarness;

    @BeforeEach
    void beforeEach() {
        testHarness = TestHarness.setup();
    }

    @Test
    @SneakyThrows
    void testSyncChatCompletion() {
        ChatModel model =
                OpenAiChatModel.builder()
                        .apiKey(testHarness.openAiApiKey())
                        .baseUrl(testHarness.openAiBaseUrl())
                        .modelName("gpt-4o-mini")
                        .temperature(0.0)
                        .build();

        var message = UserMessage.from("What is the capital of France?");
        var response = model.chat(message);

        assertNotNull(response);
        assertNotNull(response.aiMessage().text());

        var spans = testHarness.awaitExportedSpans();
        assertEquals(1, spans.size(), "Expected one span for sync chat completion");
        var span = spans.get(0);

        assertEquals("Chat Completion", span.getName(), "Span name should be 'Chat Completion'");

        var attributes = span.getAttributes();
        var braintrustSpanAttributesJson =
                attributes.get(AttributeKey.stringKey("braintrust.span_attributes"));

        JsonNode spanAttributes = JSON_MAPPER.readTree(braintrustSpanAttributesJson);
        assertEquals("llm", spanAttributes.get("type").asText(), "Span type should be 'llm'");

        String metadataJson = attributes.get(AttributeKey.stringKey("braintrust.metadata"));
        assertNotNull(metadataJson, "Metadata should be present");
        JsonNode metadata = JSON_MAPPER.readTree(metadataJson);
        assertEquals("openai", metadata.get("provider").asText(), "Provider should be 'openai'");

        assertOpenAiIdsCaptured(span);
        assertEquals(
                "gpt-4o-mini", metadata.get("model").asText(), "Model should be 'gpt-4o-mini'");

        String metricsJson = attributes.get(AttributeKey.stringKey("braintrust.metrics"));
        assertNotNull(metricsJson, "Metrics should be present");
        JsonNode metrics = JSON_MAPPER.readTree(metricsJson);
        assertTrue(metrics.get("tokens").asLong() > 0, "Total tokens should be > 0");
        assertTrue(metrics.get("prompt_tokens").asLong() > 0, "Prompt tokens should be > 0");
        assertTrue(
                metrics.get("completion_tokens").asLong() > 0, "Completion tokens should be > 0");
        assertFalse(
                metrics.has("time_to_first_token"),
                "time_to_first_token should not be present for non-streaming");

        String inputJson = attributes.get(AttributeKey.stringKey("braintrust.input_json"));
        assertNotNull(inputJson, "Input should be present");
        JsonNode input = JSON_MAPPER.readTree(inputJson);
        assertTrue(input.isArray(), "Input should be an array");
        assertTrue(input.size() > 0, "Input array should not be empty");
        assertTrue(
                input.get(0).get("content").asText().contains("What is the capital of France"),
                "Input should contain the user message");

        String outputJson = attributes.get(AttributeKey.stringKey("braintrust.output_json"));
        assertNotNull(outputJson, "Output should be present");
        JsonNode output = JSON_MAPPER.readTree(outputJson);
        assertTrue(output.isArray(), "Output should be an array");
        assertTrue(output.size() > 0, "Output array should not be empty");
        assertNotNull(
                output.get(0).get("message").get("content"),
                "Output should contain assistant response content");

        // The serialized span output should reflect the full response the client received.
        assertSpanOutputReflects(response, span);
    }

    /**
     * Exercises the OpenAI Responses API path (OpenAiResponsesChatModel -> /v1/responses), which is
     * only available on this module's langchain4j range (>= 1.14.0). Auto-instrumentation wraps the
     * responses model's HTTP client on build(); a single llm span should be produced and tagged.
     */
    @Test
    @SneakyThrows
    void testResponsesApi() {
        ChatModel model =
                OpenAiResponsesChatModel.builder()
                        .apiKey(testHarness.openAiApiKey())
                        .baseUrl(testHarness.openAiBaseUrl())
                        .modelName("gpt-4o-mini")
                        .build();

        var response = model.chat(UserMessage.from("What is the capital of France?"));
        assertNotNull(response);
        assertNotNull(response.aiMessage().text());

        var spans = testHarness.awaitExportedSpans();
        assertEquals(1, spans.size(), "Expected one span for a responses-API call");
        var span = spans.get(0);
        assertEquals("responses", span.getName(), "Span name should be 'responses'");

        var attributes = span.getAttributes();
        JsonNode spanAttributes =
                JSON_MAPPER.readTree(
                        attributes.get(AttributeKey.stringKey("braintrust.span_attributes")));
        assertEquals("llm", spanAttributes.get("type").asText(), "Span type should be 'llm'");

        JsonNode metadata =
                JSON_MAPPER.readTree(attributes.get(AttributeKey.stringKey("braintrust.metadata")));
        assertEquals("openai", metadata.get("provider").asText(), "Provider should be 'openai'");

        assertOpenAiIdsCaptured(span);

        String metricsJson = attributes.get(AttributeKey.stringKey("braintrust.metrics"));
        assertNotNull(metricsJson, "Metrics should be present");
        JsonNode metrics = JSON_MAPPER.readTree(metricsJson);
        assertTrue(metrics.get("tokens").asLong() > 0, "Total tokens should be > 0");

        assertNotNull(
                attributes.get(AttributeKey.stringKey("braintrust.input_json")),
                "Input should be present");
        assertNotNull(
                attributes.get(AttributeKey.stringKey("braintrust.output_json")),
                "Output should be present");
    }

    /**
     * Streaming over the Responses API (OpenAiResponsesStreamingChatModel -> /v1/responses with
     * {@code stream: true}). The SSE events here are {@code response.*} events, not {@code
     * chat.completion.chunk} objects, so the streamed body has to be reassembled from the terminal
     * {@code response.completed} snapshot for the span to carry any output or token metrics at all.
     */
    @Test
    @SneakyThrows
    void testStreamingResponsesApi() {
        var tracer = testHarness.openTelemetry().getTracer("test-tracer");

        StreamingChatModel model =
                OpenAiResponsesStreamingChatModel.builder()
                        .apiKey(testHarness.openAiApiKey())
                        .baseUrl(testHarness.openAiBaseUrl())
                        .modelName("gpt-4o-mini")
                        .temperature(0.0)
                        .build();

        var future = new CompletableFuture<ChatResponse>();
        var streamedText = new StringBuilder();
        var callbackCount = new AtomicInteger(0);

        model.chat(
                "What is the capital of France?",
                new StreamingChatResponseHandler() {
                    @Override
                    public void onPartialResponse(String token) {
                        Span childSpan =
                                tracer.spanBuilder(
                                                "callback-span-" + callbackCount.incrementAndGet())
                                        .startSpan();
                        childSpan.end();
                        streamedText.append(token);
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

        var response = future.get();
        assertNotNull(response);
        assertFalse(streamedText.toString().isEmpty(), "Streamed text should not be empty");

        int expectedMinSpans = 1 + callbackCount.get();
        var spans = testHarness.awaitExportedSpans(expectedMinSpans);

        var llmSpan =
                spans.stream()
                        .filter(s -> s.getName().equals("responses"))
                        .findFirst()
                        .orElseThrow(
                                () -> new AssertionError("Should have a 'responses' llm span"));
        var callbackSpans =
                spans.stream().filter(s -> s.getName().startsWith("callback-span-")).toList();
        assertEquals(
                callbackCount.get(),
                callbackSpans.size(),
                "Should have one callback span per onPartialResponse invocation");
        for (var callbackSpan : callbackSpans) {
            assertEquals(
                    llmSpan.getSpanId(),
                    callbackSpan.getParentSpanId(),
                    "Callback span '"
                            + callbackSpan.getName()
                            + "' should be parented under the llm span");
        }

        var attributes = llmSpan.getAttributes();
        JsonNode spanAttributes =
                JSON_MAPPER.readTree(
                        attributes.get(AttributeKey.stringKey("braintrust.span_attributes")));
        assertEquals("llm", spanAttributes.get("type").asText(), "Span type should be 'llm'");

        JsonNode metadata =
                JSON_MAPPER.readTree(attributes.get(AttributeKey.stringKey("braintrust.metadata")));
        assertEquals("openai", metadata.get("provider").asText(), "Provider should be 'openai'");

        assertOpenAiIdsCaptured(llmSpan);

        String metricsJson = attributes.get(AttributeKey.stringKey("braintrust.metrics"));
        assertNotNull(metricsJson, "Metrics should be present");
        JsonNode metrics = JSON_MAPPER.readTree(metricsJson);
        assertTrue(
                metrics.get("time_to_first_token").asDouble() > 0,
                "time_to_first_token should be set for a streaming call");
        assertTrue(metrics.get("tokens").asLong() > 0, "Total tokens should be > 0");
        assertTrue(metrics.get("prompt_tokens").asLong() > 0, "Prompt tokens should be > 0");
        assertTrue(
                metrics.get("completion_tokens").asLong() > 0, "Completion tokens should be > 0");

        assertNotNull(
                attributes.get(AttributeKey.stringKey("braintrust.input_json")),
                "Input should be present");

        // The Responses API reports output as an "output" array of items, and the streamed span
        // output must carry the same assistant text the caller saw.
        String outputJson = attributes.get(AttributeKey.stringKey("braintrust.output_json"));
        assertNotNull(outputJson, "Output should be present");
        JsonNode output = JSON_MAPPER.readTree(outputJson);
        assertTrue(output.isArray(), "Output should be an array");
        assertFalse(output.isEmpty(), "Output array should not be empty");
        assertTrue(
                outputJson.contains(streamedText.toString()),
                "Span output should contain the streamed assistant text, got: " + outputJson);
    }

    /**
     * OpenAI's hosted web search over a <em>streamed</em> Responses call. The web_search_call item
     * only ever appears in the reassembled response body, so this also guards that streamed output
     * still drives the server-side tool child spans.
     */
    @Test
    @SneakyThrows
    void testStreamingResponsesApiWithWebSearch() {
        StreamingChatModel model =
                OpenAiResponsesStreamingChatModel.builder()
                        .apiKey(testHarness.openAiApiKey())
                        .baseUrl(testHarness.openAiBaseUrl())
                        // gpt-4o-mini accepts the tool but never searches
                        .modelName("gpt-4o")
                        .temperature(0.0)
                        .serverTools(List.of(Map.of("type", "web_search_preview")))
                        .build();

        var future = new CompletableFuture<ChatResponse>();
        model.chat(
                "Do a web search for news about Moderna. What are they up to lately?",
                new StreamingChatResponseHandler() {
                    @Override
                    public void onPartialResponse(String token) {}

                    @Override
                    public void onCompleteResponse(ChatResponse response) {
                        future.complete(response);
                    }

                    @Override
                    public void onError(Throwable error) {
                        future.completeExceptionally(error);
                    }
                });
        assertNotNull(future.get());

        var spans = testHarness.awaitExportedSpans(2);
        var llmSpanIds =
                spans.stream()
                        .filter(s -> s.getName().equals("responses"))
                        .map(SpanData::getSpanId)
                        .toList();
        assertFalse(llmSpanIds.isEmpty(), "should have at least one 'responses' llm span");

        var webSearchSpan =
                spans.stream()
                        .filter(s -> s.getName().equals("web_search_call"))
                        .findFirst()
                        .orElseThrow(
                                () ->
                                        new AssertionError(
                                                "no 'web_search_call' span; the streamed output was"
                                                        + " not reassembled, or the model did not"
                                                        + " run a server-side search"));
        JsonNode metadata =
                JSON_MAPPER.readTree(
                        webSearchSpan
                                .getAttributes()
                                .get(AttributeKey.stringKey("braintrust.metadata")));
        assertEquals(
                "web_search_call",
                metadata.get("tool_type").asText(),
                "web search span should record its tool_type");
        assertTrue(
                llmSpanIds.contains(webSearchSpan.getParentSpanId()),
                "web search span should be a child of the streaming llm span that reported it");
    }

    @Test
    @SneakyThrows
    void testStreamingChatCompletion() {
        var tracer = testHarness.openTelemetry().getTracer("test-tracer");

        // Auto-instrumentation intercepts OpenAiStreamingChatModel.Builder.build()
        StreamingChatModel model =
                OpenAiStreamingChatModel.builder()
                        .apiKey(testHarness.openAiApiKey())
                        .baseUrl(testHarness.openAiBaseUrl())
                        .modelName("gpt-4o-mini")
                        .temperature(0.0)
                        .build();

        var future = new CompletableFuture<ChatResponse>();
        var responseBuilder = new StringBuilder();
        var callbackCount = new AtomicInteger(0);

        model.chat(
                "What is the capital of France?",
                new StreamingChatResponseHandler() {
                    @Override
                    public void onPartialResponse(String token) {
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

        var response = future.get();

        assertNotNull(response);
        assertFalse(responseBuilder.toString().isEmpty(), "Response should not be empty");

        int expectedMinSpans = 1 + callbackCount.get();
        var spans = testHarness.awaitExportedSpans(expectedMinSpans);
        assertTrue(
                spans.size() >= expectedMinSpans,
                "Expected at least " + expectedMinSpans + " spans, got " + spans.size());

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

        String llmSpanId = llmSpan.getSpanId();
        for (var callbackSpan : callbackSpans) {
            assertEquals(
                    llmSpanId,
                    callbackSpan.getParentSpanId(),
                    "Callback span '"
                            + callbackSpan.getName()
                            + "' should be parented under LLM span");
        }

        var attributes = llmSpan.getAttributes();

        var braintrustSpanAttributesJson =
                attributes.get(AttributeKey.stringKey("braintrust.span_attributes"));

        JsonNode spanAttributes = JSON_MAPPER.readTree(braintrustSpanAttributesJson);
        assertEquals("llm", spanAttributes.get("type").asText(), "Span type should be 'llm'");

        String metadataJson = attributes.get(AttributeKey.stringKey("braintrust.metadata"));
        assertNotNull(metadataJson, "Metadata should be present");
        JsonNode metadata = JSON_MAPPER.readTree(metadataJson);
        assertEquals("openai", metadata.get("provider").asText(), "Provider should be 'openai'");

        assertOpenAiIdsCaptured(llmSpan);
        assertEquals(
                "gpt-4o-mini", metadata.get("model").asText(), "Model should be 'gpt-4o-mini'");

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

        String inputJson = attributes.get(AttributeKey.stringKey("braintrust.input_json"));
        assertNotNull(inputJson, "Input should be present");
        JsonNode input = JSON_MAPPER.readTree(inputJson);
        assertTrue(input.isArray(), "Input should be an array");
        assertTrue(input.size() > 0, "Input array should not be empty");
        assertTrue(
                input.get(0).get("content").asText().contains("What is the capital of France"),
                "Input should contain the user message");

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

        // The reconstructed streaming span output should reflect the full response the client
        // received — the instrumentation must feed every SSE event to the accumulator.
        assertSpanOutputReflects(response, llmSpan);
    }

    @Test
    @SneakyThrows
    void testStreamingChatCompletionWithTools() {
        // Auto-instrumentation intercepts OpenAiStreamingChatModel.Builder.build()
        StreamingChatModel model =
                OpenAiStreamingChatModel.builder()
                        .apiKey(testHarness.openAiApiKey())
                        .baseUrl(testHarness.openAiBaseUrl())
                        .modelName("gpt-4o")
                        .temperature(0.0)
                        .build();

        var weatherTool =
                ToolSpecification.builder()
                        .name("get_weather")
                        .description("Get the current weather for a location")
                        .parameters(
                                JsonObjectSchema.builder()
                                        .addStringProperty(
                                                "location",
                                                "The city and state, e.g. San" + " Francisco, CA")
                                        .required("location")
                                        .build())
                        .build();

        var chatRequest =
                ChatRequest.builder()
                        .messages(UserMessage.from("What is the weather in Paris, France?"))
                        .toolSpecifications(weatherTool)
                        .build();

        var future = new CompletableFuture<ChatResponse>();
        model.chat(
                chatRequest,
                new StreamingChatResponseHandler() {
                    @Override
                    public void onPartialResponse(String token) {}

                    @Override
                    public void onCompleteResponse(ChatResponse response) {
                        future.complete(response);
                    }

                    @Override
                    public void onError(Throwable error) {
                        future.completeExceptionally(error);
                    }
                });
        var response = future.get();

        // The stream must carry tool-call deltas (merged by index) all the way to the span — the
        // original bug dropped tool_calls entirely from streaming reconstruction.
        assertTrue(
                response.aiMessage().hasToolExecutionRequests(),
                "Model should have requested a tool call");

        var llmSpan =
                testHarness.awaitExportedSpans(1).stream()
                        .filter(s -> s.getName().equals("Chat Completion"))
                        .findFirst()
                        .orElseThrow(() -> new AssertionError("no 'Chat Completion' llm span"));

        assertSpanOutputReflects(response, llmSpan);
    }

    /**
     * Asserts that the llm span's serialized output ({@code braintrust.output_json}) reflects the
     * full response the langchain client received — comparing the reconstructed assistant message
     * against the client's parsed {@link ChatResponse} (content, thinking, and tool calls) rather
     * than hand-asserting individual fields per test. langchain decodes the same stream
     * independently of our accumulator, so agreement is a meaningful end-to-end check.
     */
    @SneakyThrows
    private void assertSpanOutputReflects(ChatResponse clientResponse, SpanData llmSpan) {
        String outputJson =
                llmSpan.getAttributes().get(AttributeKey.stringKey("braintrust.output_json"));
        assertNotNull(outputJson, "Span should have braintrust.output_json");
        JsonNode message = JSON_MAPPER.readTree(outputJson).get(0).get("message");
        assertNotNull(message, "Span output should contain a choice message");

        var aiMessage = clientResponse.aiMessage();

        if (aiMessage.text() != null) {
            assertEquals(
                    aiMessage.text(),
                    message.path("content").asText(),
                    "Span output content should match the client's assistant text");
        }
        if (aiMessage.thinking() != null) {
            assertEquals(
                    aiMessage.thinking(),
                    message.path("reasoning_content").asText(),
                    "Span output reasoning_content should match the client's thinking");
        }
        if (aiMessage.hasToolExecutionRequests()) {
            JsonNode toolCalls = message.get("tool_calls");
            assertNotNull(toolCalls, "Span output should contain tool_calls");
            var requests = aiMessage.toolExecutionRequests();
            assertEquals(
                    requests.size(), toolCalls.size(), "tool_calls count should match the client");
            for (int i = 0; i < requests.size(); i++) {
                var request = requests.get(i);
                JsonNode function = toolCalls.get(i).get("function");
                assertEquals(
                        request.name(), function.get("name").asText(), "tool name should match");
                assertEquals(
                        JSON_MAPPER.readTree(request.arguments()),
                        JSON_MAPPER.readTree(function.get("arguments").asText()),
                        "tool arguments should match");
                if (request.id() != null) {
                    assertEquals(
                            request.id(),
                            toolCalls.get(i).get("id").asText(),
                            "tool id should match");
                }
            }
        }
    }

    @Test
    @SneakyThrows
    void testAiServicesWithTools() {
        // Auto-instrumentation intercepts both OpenAiChatModel.Builder.build() and
        // AiServices.build()
        Assistant assistant =
                AiServices.builder(Assistant.class)
                        .chatModel(
                                OpenAiChatModel.builder()
                                        .apiKey(testHarness.openAiApiKey())
                                        .baseUrl(testHarness.openAiBaseUrl())
                                        .modelName("gpt-4o-mini")
                                        .temperature(0.0)
                                        .build())
                        .tools(new WeatherTools())
                        .executeToolsConcurrently()
                        .build();

        var response = assistant.chat("is it hotter in Paris or New York right now?");

        assertNotNull(response);

        var spans = testHarness.awaitExportedSpans(3);
        assertTrue(spans.size() >= 3, "Expected at least 3 spans for AI Services with tools");

        int numServiceMethodSpans = 0;
        int numLLMSpans = 0;
        int numToolCallSpans = 0;

        for (var span : spans) {
            String spanName = span.getName();
            var attributes = span.getAttributes();

            if (spanName.equals("Assistant.chat")) {
                numServiceMethodSpans++;
            } else if (spanName.equals("Chat Completion")) {
                numLLMSpans++;
                var spanAttributesJson =
                        attributes.get(AttributeKey.stringKey("braintrust.span_attributes"));
                assertNotNull(spanAttributesJson, "LLM span should have span_attributes");
                JsonNode spanAttributes = JSON_MAPPER.readTree(spanAttributesJson);
                assertEquals(
                        "llm", spanAttributes.get("type").asText(), "Span type should be 'llm'");
            } else if (spanName.equals("getWeather")) {
                numToolCallSpans++;
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

    /**
     * AI Services driven by the Responses API (/v1/responses) — the path the
     * langchain-ai-services-responses example takes. Wrapping the AiServices builder manually must
     * instrument the responses model too (it is not an OpenAiChatModel subtype), so the trace gets
     * llm spans in addition to the service-method and tool spans.
     */
    @Test
    @SneakyThrows
    void testAiServicesWithToolsOverResponsesApi() {
        Assistant assistant =
                BraintrustLangchain.wrap(
                        testHarness.openTelemetry(),
                        AiServices.builder(Assistant.class)
                                .chatModel(
                                        OpenAiResponsesChatModel.builder()
                                                .apiKey(testHarness.openAiApiKey())
                                                .baseUrl(testHarness.openAiBaseUrl())
                                                .modelName("gpt-4o-mini")
                                                .temperature(0.0)
                                                .build())
                                .tools(new WeatherTools())
                                .executeToolsConcurrently());

        var response = assistant.chat("is it hotter in Paris or New York right now?");
        assertNotNull(response);

        var spans = testHarness.awaitExportedSpans(3);

        int numServiceMethodSpans = 0;
        int numLLMSpans = 0;
        int numToolCallSpans = 0;
        for (var span : spans) {
            var attributes = span.getAttributes();
            switch (span.getName()) {
                case "Assistant.chat" -> numServiceMethodSpans++;
                case "responses" -> {
                    numLLMSpans++;
                    JsonNode spanAttributes =
                            JSON_MAPPER.readTree(
                                    attributes.get(
                                            AttributeKey.stringKey("braintrust.span_attributes")));
                    assertEquals(
                            "llm",
                            spanAttributes.get("type").asText(),
                            "Span type should be 'llm'");
                }
                case "getWeather" -> {
                    numToolCallSpans++;
                    JsonNode spanAttributes =
                            JSON_MAPPER.readTree(
                                    attributes.get(
                                            AttributeKey.stringKey("braintrust.span_attributes")));
                    assertEquals(
                            "tool",
                            spanAttributes.get("type").asText(),
                            "Span type should be 'tool'");
                }
                default -> {}
            }
        }
        assertEquals(1, numServiceMethodSpans, "should be exactly one service call");
        assertTrue(numLLMSpans >= 2, "should be at least two llm spans, got " + numLLMSpans);
        assertTrue(
                numToolCallSpans >= 2,
                "should be at least two tool call spans, got " + numToolCallSpans);
    }

    /**
     * Openai's hosted web search runs server side, so it never surfaces as a langchain tool
     * execution — braintrust derives a {@code web_search_call} tool span from the response payload
     * instead. Mirrors the langchain-ai-services example's responses agent: the server tool is
     * passed as a raw {@code serverTools} map and rides in the same request {@code tools} array as
     * the {@code @Tool} functions.
     */
    @Test
    @SneakyThrows
    void testAiServicesWebSearchOverResponsesApi() {
        var assistant =
                BraintrustLangchain.wrap(
                        testHarness.openTelemetry(),
                        AiServices.builder(Assistant.class)
                                .chatModel(
                                        OpenAiResponsesChatModel.builder()
                                                .apiKey(testHarness.openAiApiKey())
                                                .baseUrl(testHarness.openAiBaseUrl())
                                                // gpt-4o-mini accepts the tool but never searches
                                                .modelName("gpt-4o")
                                                .temperature(0.0)
                                                .serverTools(
                                                        List.of(
                                                                Map.of(
                                                                        "type",
                                                                        "web_search_preview")))
                                                .build())
                                .tools(new WeatherTools()));

        var response =
                assistant.chat(
                        "Do a web search for news about Moderna. What are they up to lately?");
        assertNotNull(response);

        var spans = testHarness.awaitExportedSpans(3);
        var llmSpanIds =
                spans.stream()
                        .filter(s -> s.getName().equals("responses"))
                        .map(SpanData::getSpanId)
                        .toList();
        assertFalse(llmSpanIds.isEmpty(), "should have at least one 'responses' llm span");

        var webSearchSpan =
                spans.stream()
                        .filter(s -> s.getName().equals("web_search_call"))
                        .findFirst()
                        .orElseThrow(
                                () ->
                                        new AssertionError(
                                                "no 'web_search_call' span; the model did not run a"
                                                        + " server-side search"));

        var attributes = webSearchSpan.getAttributes();
        JsonNode spanAttributes =
                JSON_MAPPER.readTree(
                        attributes.get(AttributeKey.stringKey("braintrust.span_attributes")));
        assertEquals(
                "tool", spanAttributes.get("type").asText(), "web search span type should be tool");
        JsonNode metadata =
                JSON_MAPPER.readTree(attributes.get(AttributeKey.stringKey("braintrust.metadata")));
        assertEquals(
                "web_search_call",
                metadata.get("tool_type").asText(),
                "web search span should record its tool_type");
        assertTrue(
                llmSpanIds.contains(webSearchSpan.getParentSpanId()),
                "web search span should be a child of the llm span that reported it");
    }

    /**
     * Guards the manual wrap path used by the examples (no java agent): {@code
     * BraintrustLangchain.wrap(otel, aiServices)} must recognize a responses model, which is not an
     * {@code OpenAiChatModel} subtype. Auto-instrumentation is installed for this test class and
     * already wrapped the model on build(), so the wrap is undone first to isolate the AiServices
     * dispatch.
     */
    @Test
    @SneakyThrows
    void testAiServicesWrapsResponsesModel() {
        var model =
                OpenAiResponsesChatModel.builder()
                        .apiKey(testHarness.openAiApiKey())
                        .baseUrl(testHarness.openAiBaseUrl())
                        .modelName("gpt-4o-mini")
                        .build();
        unwrapHttpClient(model);
        assertFalse(
                httpClientOf(model) instanceof WrappedHttpClient,
                "precondition: model should start uninstrumented");

        var assistant =
                BraintrustLangchain.wrap(
                        testHarness.openTelemetry(),
                        AiServices.builder(Assistant.class).chatModel(model));

        assertNotNull(assistant, "wrap should return an instrumented service");
        assertTrue(
                httpClientOf(model) instanceof WrappedHttpClient,
                "AiServices wrap should instrument the responses model's http client");
    }

    /** Reads the {@code client.httpClient} a langchain model issues requests through. */
    @SneakyThrows
    private static Object httpClientOf(Object model) {
        return readField(readField(model, "client"), "httpClient");
    }

    /** Restores a model's original http client, reversing {@link WrappedHttpClient} wrapping. */
    @SneakyThrows
    private static void unwrapHttpClient(Object model) {
        Object client = readField(model, "client");
        Object httpClient = readField(client, "httpClient");
        if (!(httpClient instanceof WrappedHttpClient)) {
            return;
        }
        var field = client.getClass().getDeclaredField("httpClient");
        field.setAccessible(true);
        field.set(client, readField(httpClient, "underlying"));
    }

    @SneakyThrows
    private static Object readField(Object obj, String name) {
        var field = obj.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return field.get(obj);
    }

    /**
     * A builder's tool-executor map lives on the AiServiceContext and outlives build(), and is
     * shared with every service already built from that builder. Re-wrapping entries that are
     * already wrapped would nest a second TracingToolExecutor on each build, so one tool invocation
     * would emit duplicate — and monotonically increasing — tool spans, including through the
     * service returned by the first build.
     */
    @Test
    @SneakyThrows
    void repeatedBuildsDoNotNestToolTracing() {
        var builder =
                AiServices.builder(Assistant.class)
                        .chatModel(
                                OpenAiChatModel.builder()
                                        .apiKey(testHarness.openAiApiKey())
                                        .baseUrl(testHarness.openAiBaseUrl())
                                        .modelName("gpt-4o-mini")
                                        .build())
                        .tools(new WeatherTools())
                        .executeToolsConcurrently();

        // Auto-instrumentation wraps on every build(); no request is issued by building.
        builder.build();
        builder.build();
        builder.build();

        Object toolService = walkField(walkField(builder, "context"), "toolService");
        @SuppressWarnings("unchecked")
        var executors =
                (Map<String, ?>)
                        toolService.getClass().getMethod("toolExecutors").invoke(toolService);
        assertFalse(executors.isEmpty(), "precondition: tools should be registered");
        executors.forEach(
                (name, executor) -> {
                    assertInstanceOf(
                            TracingToolExecutor.class, executor, name + " should be instrumented");
                    assertFalse(
                            walkField(executor, "delegate") instanceof TracingToolExecutor,
                            name + " should be wrapped once, not once per build()");
                });

        Object executor = toolService.getClass().getMethod("executor").invoke(toolService);
        assertInstanceOf(
                OtelContextPassingExecutor.class, executor, "executor should pass otel context");
        assertFalse(
                walkField(executor, "underlying") instanceof OtelContextPassingExecutor,
                "concurrent-tool executor should be wrapped once, not once per build()");
    }

    /** Reads a private field declared anywhere in the object's class hierarchy. */
    @SneakyThrows
    private static Object walkField(Object obj, String name) {
        for (Class<?> c = obj.getClass(); c != null; c = c.getSuperclass()) {
            try {
                var field = c.getDeclaredField(name);
                field.setAccessible(true);
                return field.get(obj);
            } catch (NoSuchFieldException searchSuperclass) {
                // declared further up the hierarchy
            }
        }
        throw new NoSuchFieldException(name);
    }

    /**
     * OpenAI reports a failed generation as an ordinary event on a stream that then closes cleanly.
     * LangChain4j hands that event to the caller's own response handler and lets the transport
     * finish normally, so this listener's onError is never reached — without retaining the in-band
     * failure the span would be finalized as a success.
     */
    @Test
    @SneakyThrows
    void inBandStreamFailureMarksTheSpanAsAnError() {
        var tracer = testHarness.openTelemetry().getTracer("test-tracer");
        var span = tracer.spanBuilder("responses").startSpan();
        var listener =
                new WrappedHttpClient.WrappedServerSentEventListener(
                        throwable -> {}, span, "openai", tracer);

        listener.onEvent(
                new ServerSentEvent(
                        null,
                        "{\"type\":\"response.created\",\"response\":{\"id\":\"resp_1\","
                                + "\"model\":\"gpt-4o-mini\",\"status\":\"in_progress\"}}"));
        listener.onEvent(
                new ServerSentEvent(
                        null,
                        "{\"type\":\"response.failed\",\"response\":{\"id\":\"resp_1\","
                                + "\"model\":\"gpt-4o-mini\",\"status\":\"failed\","
                                + "\"error\":{\"code\":\"server_error\",\"message\":\"The server"
                                + " had an error while processing your request.\"}}}"));
        // langchain4j closes the stream normally after delivering the failure.
        listener.onClose();

        var exported = testHarness.awaitExportedSpans(1).get(0);
        assertEquals(
                StatusCode.ERROR,
                exported.getStatus().getStatusCode(),
                "a failed generation must not be recorded as a successful span");
        assertEquals(
                "The server had an error while processing your request.",
                exported.getStatus().getDescription());
    }

    /** The mirror case: a stream that completes normally leaves the span status alone. */
    @Test
    @SneakyThrows
    void healthyStreamLeavesTheSpanStatusUnset() {
        var tracer = testHarness.openTelemetry().getTracer("test-tracer");
        var span = tracer.spanBuilder("responses").startSpan();
        var listener =
                new WrappedHttpClient.WrappedServerSentEventListener(
                        throwable -> {}, span, "openai", tracer);

        listener.onEvent(
                new ServerSentEvent(
                        null,
                        "{\"type\":\"response.output_text.delta\",\"item_id\":\"msg_1\","
                                + "\"output_index\":0,\"delta\":\"Paris\"}"));
        listener.onEvent(
                new ServerSentEvent(
                        null,
                        "{\"type\":\"response.completed\",\"response\":{\"id\":\"resp_1\","
                                + "\"model\":\"gpt-4o-mini\",\"status\":\"completed\","
                                + "\"output\":[{\"id\":\"msg_1\",\"type\":\"message\","
                                + "\"role\":\"assistant\",\"content\":[{\"type\":\"output_text\","
                                + "\"text\":\"Paris\"}]}],\"usage\":{\"input_tokens\":5,"
                                + "\"output_tokens\":1,\"total_tokens\":6}}}"));
        listener.onClose();

        var exported = testHarness.awaitExportedSpans(1).get(0);
        assertEquals(StatusCode.UNSET, exported.getStatus().getStatusCode());
    }

    /**
     * A Responses stream that fails before generating anything still emits lifecycle events, so a
     * first-payload fallback would publish time-to-response-metadata as a token latency. There was
     * no first token, so there must be no metric.
     */
    @Test
    @SneakyThrows
    void streamThatProducesNoOutputReportsNoTimeToFirstToken() {
        var tracer = testHarness.openTelemetry().getTracer("test-tracer");
        var span = tracer.spanBuilder("responses").startSpan();
        var listener =
                new WrappedHttpClient.WrappedServerSentEventListener(
                        throwable -> {}, span, "openai", tracer);

        listener.onEvent(
                new ServerSentEvent(
                        null,
                        "{\"type\":\"response.created\",\"response\":{\"id\":\"resp_1\","
                                + "\"model\":\"gpt-4o-mini\",\"status\":\"in_progress\"}}"));
        listener.onEvent(
                new ServerSentEvent(
                        null,
                        "{\"type\":\"response.failed\",\"response\":{\"id\":\"resp_1\","
                                + "\"model\":\"gpt-4o-mini\",\"status\":\"failed\","
                                + "\"error\":{\"message\":\"boom\"}}}"));
        listener.onClose();

        var metricsJson =
                testHarness
                        .awaitExportedSpans(1)
                        .get(0)
                        .getAttributes()
                        .get(AttributeKey.stringKey("braintrust.metrics"));
        // Asserted unconditionally: absent metrics and present-but-without-TTFT are both correct,
        // but an `if (metricsJson != null)` guard would let the case pass without asserting.
        boolean reportedTtft =
                metricsJson != null && JSON_MAPPER.readTree(metricsJson).has("time_to_first_token");
        assertFalse(
                reportedTtft, "a stream that generated nothing must not report a token latency");
    }

    /**
     * The fallback the recognition gate must not break: for a stream shape the accumulator does not
     * understand, approximating TTFT from the first payload beats dropping a metric the spec
     * requires on streaming spans.
     */
    @Test
    @SneakyThrows
    void unrecognizedStreamShapeStillReportsTimeToFirstToken() {
        var tracer = testHarness.openTelemetry().getTracer("test-tracer");
        var span = tracer.spanBuilder("responses").startSpan();
        var listener =
                new WrappedHttpClient.WrappedServerSentEventListener(
                        throwable -> {}, span, "openai", tracer);

        listener.onEvent(new ServerSentEvent(null, "{\"some_future_wire_shape\":\"hello\"}"));
        listener.onClose();

        var metricsJson =
                testHarness
                        .awaitExportedSpans(1)
                        .get(0)
                        .getAttributes()
                        .get(AttributeKey.stringKey("braintrust.metrics"));
        assertNotNull(metricsJson, "an unrecognized stream should still be timed");
        assertTrue(
                JSON_MAPPER.readTree(metricsJson).get("time_to_first_token").asDouble() >= 0.0,
                "unrecognized shapes fall back to the first payload timestamp");
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

    /**
     * langchain4j surfaces response headers in two unrelated places — {@code execute()} for a
     * blocking call and {@code onOpen()} for an SSE stream — so both are asserted, over both
     * endpoints. The header value is checked for presence but not shape: OpenAI returns both {@code
     * req_*} and bare UUIDs for it.
     */
    private static void assertOpenAiIdsCaptured(SpanData span) {
        var attributes = span.getAttributes();

        String requestId = attributes.get(AttributeKey.stringKey("x-request-id"));
        assertNotNull(requestId, "x-request-id header must be captured");
        assertFalse(requestId.isBlank(), "x-request-id must not be blank");

        String responseId = attributes.get(AttributeKey.stringKey("response_id"));
        assertNotNull(responseId, "response_id must be captured from the response body");
        assertTrue(
                responseId.startsWith("resp_") || responseId.startsWith("chatcmpl-"),
                "unexpected response_id: " + responseId);
    }
}
