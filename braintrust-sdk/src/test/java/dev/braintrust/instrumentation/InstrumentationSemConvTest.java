package dev.braintrust.instrumentation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import dev.braintrust.json.BraintrustJsonMapper;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;
import java.util.List;
import java.util.stream.Collectors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Covers provider response tagging and {@link InstrumentationSemConv#addServerSideChildSpans}. */
class InstrumentationSemConvTest {

    private static final AttributeKey<String> SPAN_ATTRIBUTES =
            AttributeKey.stringKey("braintrust.span_attributes");
    private static final AttributeKey<String> INPUT_JSON =
            AttributeKey.stringKey("braintrust.input_json");
    private static final AttributeKey<String> OUTPUT_JSON =
            AttributeKey.stringKey("braintrust.output_json");
    private static final AttributeKey<String> METADATA =
            AttributeKey.stringKey("braintrust.metadata");
    private static final AttributeKey<String> METRICS =
            AttributeKey.stringKey("braintrust.metrics");

    private InMemorySpanExporter exporter;
    private SdkTracerProvider tracerProvider;
    private Tracer tracer;

    @BeforeEach
    void setUp() {
        exporter = InMemorySpanExporter.create();
        tracerProvider =
                SdkTracerProvider.builder()
                        .addSpanProcessor(SimpleSpanProcessor.create(exporter))
                        .build();
        tracer = tracerProvider.get("test");
    }

    @AfterEach
    void tearDown() {
        tracerProvider.close();
    }

    /** Runs the emitter with an LLM parent span and returns only the emitted child spans. */
    private List<SpanData> emitOpenAI(String responseBody) {
        return emit(InstrumentationSemConv.PROVIDER_NAME_OPENAI, responseBody);
    }

    private List<SpanData> emit(String provider, String responseBody) {
        Span parent = tracer.spanBuilder("llm").startSpan();
        try (var ignored = parent.makeCurrent()) {
            InstrumentationSemConv.addServerSideChildSpans(
                    tracer, parent, provider, json(responseBody));
        } finally {
            parent.end();
        }
        return exporter.getFinishedSpanItems().stream()
                .filter(s -> !s.getName().equals("llm"))
                .collect(Collectors.toList());
    }

    private static SpanData byName(List<SpanData> spans, String name) {
        return spans.stream().filter(s -> s.getName().equals(name)).findFirst().orElseThrow();
    }

    private static JsonNode json(String s) {
        return BraintrustJsonMapper.fromJson(s, JsonNode.class);
    }

    private SpanData tagOpenAIResponse(String responseBody) {
        Span span = tracer.spanBuilder("llm").startSpan();
        try {
            InstrumentationSemConv.tagLLMSpanResponse(
                    tracer, span, InstrumentationSemConv.PROVIDER_NAME_OPENAI, responseBody);
        } finally {
            span.end();
        }
        return byName(exporter.getFinishedSpanItems(), "llm");
    }

    @Test
    void tagsOpenAIAudioTranscriptionOutput() {
        SpanData span = tagOpenAIResponse("{\"text\":\"Hello from the recording.\"}");

        assertEquals(
                json("{\"text\":\"Hello from the recording.\"}"),
                json(span.getAttributes().get(OUTPUT_JSON)));
    }

    @Test
    void tagsOpenAIVerboseAudioTranscriptionOutput() {
        String body =
                """
                {
                  "text": "Hello from the recording.",
                  "language": "english",
                  "duration": 1.25,
                  "segments": [{"id": 0, "text": "Hello from the recording."}],
                  "words": [{"word": "Hello", "start": 0.0, "end": 0.4}]
                }
                """;

        SpanData span = tagOpenAIResponse(body);

        assertEquals(json(body), json(span.getAttributes().get(OUTPUT_JSON)));
    }

    @Test
    void emitsWebSearchCallToolSpanParentedToLlm() {
        String body =
                """
                {
                  "id": "resp_1",
                  "output": [
                    {
                      "id": "ws_1",
                      "type": "web_search_call",
                      "status": "completed",
                      "action": {"type": "search", "query": "braintrust observability"}
                    },
                    {
                      "id": "msg_1",
                      "type": "message",
                      "content": [{"type": "output_text", "text": "here is the answer"}]
                    }
                  ],
                  "usage": {"input_tokens": 10, "output_tokens": 20}
                }
                """;

        List<SpanData> tools = emitOpenAI(body);
        // Only the web_search_call becomes a tool span; the message item is ignored.
        assertEquals(1, tools.size());

        SpanData ws = byName(tools, "web_search_call");
        // Parented to the LLM span.
        SpanData llm = byName(exporter.getFinishedSpanItems(), "llm");
        assertEquals(llm.getSpanId(), ws.getParentSpanId());

        assertEquals("{\"type\":\"tool\"}", ws.getAttributes().get(SPAN_ATTRIBUTES));

        // action is the sole input key -> object with the action content.
        JsonNode input = json(ws.getAttributes().get(INPUT_JSON));
        assertEquals("search", input.path("action").path("type").asText());
        assertEquals("braintrust observability", input.path("action").path("query").asText());

        // metadata carries tool_type / tool_id / status.
        JsonNode metadata = json(ws.getAttributes().get(METADATA));
        assertEquals("web_search_call", metadata.path("tool_type").asText());
        assertEquals("ws_1", metadata.path("tool_id").asText());
        assertEquals("completed", metadata.path("status").asText());

        // action is an input key and id/type/error are excluded; the remaining `status` field
        // flows into output (matching the Python SDK).
        JsonNode output = json(ws.getAttributes().get(OUTPUT_JSON));
        assertEquals("completed", output.path("status").asText());

        // Zero-duration marker — providers don't report per-tool timing, so we stamp start and end
        // at the same instant rather than fabricate a duration.
        assertEquals(ws.getStartEpochNanos(), ws.getEndEpochNanos());
    }

    @Test
    void clientSideCallsAreNotSpanned() {
        // function_call and computer_call are executed by the caller, not the vendor — no spans.
        String body =
                """
                {
                  "output": [
                    {
                      "id": "fc_1",
                      "type": "function_call",
                      "call_id": "call_1",
                      "name": "get_weather",
                      "arguments": "{\\"city\\": \\"sf\\"}"
                    },
                    {
                      "id": "cc_1",
                      "type": "computer_call",
                      "action": {"type": "screenshot"}
                    }
                  ]
                }
                """;
        assertTrue(emitOpenAI(body).isEmpty());
    }

    @Test
    void imageGenerationCallIsNotSpanned() {
        // image_generation_call is server-side but excluded: its result is a large base64 image
        // blob that would flow unredacted into the span output, and it has no useful input.
        String body =
                """
                {
                  "output": [
                    {
                      "id": "ig_1",
                      "type": "image_generation_call",
                      "status": "completed",
                      "result": "iVBORw0KGgoAAAANSUhEUgAAA-base64-image-data"
                    }
                  ]
                }
                """;
        assertTrue(emitOpenAI(body).isEmpty());
    }

    @Test
    void errorItemSetsErrorStatusAndSkipsOutput() {
        String body =
                """
                {
                  "output": [
                    {
                      "id": "ws_2",
                      "type": "web_search_call",
                      "status": "failed",
                      "action": {"query": "x"},
                      "error": "rate_limited"
                    }
                  ]
                }
                """;

        List<SpanData> tools = emitOpenAI(body);
        assertEquals(1, tools.size());
        SpanData ws = tools.get(0);
        assertEquals(StatusCode.ERROR, ws.getStatus().getStatusCode());
        assertNull(ws.getAttributes().get(OUTPUT_JSON));
    }

    @Test
    void mcpCallUsesServerLabelDottedNameAndUnwrapsArguments() {
        String body =
                """
                {
                  "output": [
                    {
                      "id": "mcp_1",
                      "type": "mcp_call",
                      "server_label": "deepwiki",
                      "name": "ask_question",
                      "arguments": "{\\"q\\": \\"hi\\"}",
                      "output": "an answer"
                    }
                  ]
                }
                """;

        List<SpanData> tools = emitOpenAI(body);
        assertEquals(1, tools.size());
        SpanData mcp = byName(tools, "deepwiki.ask_question");
        // arguments (sole input key) unwrapped to the bare, parsed value.
        JsonNode input = json(mcp.getAttributes().get(INPUT_JSON));
        assertEquals("hi", input.path("q").asText());
        // output present (mcp_call is server-side) and JSON-parsed where applicable.
        assertEquals(
                "an answer", json(mcp.getAttributes().get(OUTPUT_JSON)).path("output").asText());
    }

    @Test
    void nonToolResponseEmitsNothing() {
        // A Chat Completions body (no `output` array) yields no child spans.
        String body =
                """
                {"choices": [{"message": {"content": "hi"}}], "usage": {"total_tokens": 5}}
                """;
        assertTrue(emitOpenAI(body).isEmpty());
    }

    // -------------------------------------------------------------------------
    // Anthropic server-side tool spans
    // -------------------------------------------------------------------------

    /** Runs the Anthropic emitter with an LLM parent span and returns only the child spans. */
    private List<SpanData> emitAnthropic(String responseBody) {
        return emit(InstrumentationSemConv.PROVIDER_NAME_ANTHROPIC, responseBody);
    }

    @Test
    void pairsServerToolUseWithResultAndRedactsEncryptedContent() {
        String body =
                """
                {
                  "content": [
                    {"type": "server_tool_use", "id": "srv_1", "name": "web_search",
                     "input": {"query": "braintrust"}},
                    {"type": "web_search_tool_result", "tool_use_id": "srv_1",
                     "content": [{"type": "web_search_result", "title": "BT",
                                  "encrypted_content": "SECRET-BLOB"}]},
                    {"type": "text", "text": "here is the answer"}
                  ]
                }
                """;

        List<SpanData> tools = emitAnthropic(body);
        assertEquals(1, tools.size());
        SpanData ws = byName(tools, "web_search");

        // Parented to the LLM span.
        SpanData llm = byName(exporter.getFinishedSpanItems(), "llm");
        assertEquals(llm.getSpanId(), ws.getParentSpanId());
        assertEquals("{\"type\":\"tool\"}", ws.getAttributes().get(SPAN_ATTRIBUTES));

        // input = the call's `input`.
        assertEquals("braintrust", json(ws.getAttributes().get(INPUT_JSON)).path("query").asText());

        // metadata carries the pairing identity.
        JsonNode md = json(ws.getAttributes().get(METADATA));
        assertEquals("srv_1", md.path("tool_use_id").asText());
        assertEquals("server_tool_use", md.path("tool_call_type").asText());
        assertEquals("web_search_tool_result", md.path("tool_result_type").asText());

        // output = the result content, with encrypted_content redacted.
        JsonNode output = json(ws.getAttributes().get(OUTPUT_JSON));
        assertEquals("BT", output.get(0).path("title").asText());
        assertEquals("<redacted>", output.get(0).path("encrypted_content").asText());
    }

    @Test
    void serverToolResultErrorSetsErrorStatus() {
        String body =
                """
                {
                  "content": [
                    {"type": "server_tool_use", "id": "srv_2", "name": "web_search",
                     "input": {"query": "x"}},
                    {"type": "web_search_tool_result", "tool_use_id": "srv_2",
                     "content": {"type": "web_search_tool_result_error",
                                 "error_code": "max_uses_exceeded"}}
                  ]
                }
                """;

        List<SpanData> tools = emitAnthropic(body);
        assertEquals(1, tools.size());
        assertEquals(StatusCode.ERROR, tools.get(0).getStatus().getStatusCode());
    }

    @Test
    void unmatchedServerToolUseStillEmitsSpanWithoutOutput() {
        // A call with no matching *_tool_result — input + metadata, no output.
        String body =
                """
                {
                  "content": [
                    {"type": "server_tool_use", "id": "srv_3", "name": "web_search",
                     "input": {"query": "y"}}
                  ]
                }
                """;

        List<SpanData> tools = emitAnthropic(body);
        assertEquals(1, tools.size());
        SpanData ws = tools.get(0);
        assertEquals("web_search", ws.getName());
        assertNull(ws.getAttributes().get(OUTPUT_JSON));
        assertEquals("y", json(ws.getAttributes().get(INPUT_JSON)).path("query").asText());
    }

    @Test
    void clientToolUseAndPlainTextEmitNothing() {
        // Client-side tool_use blocks and text are not server-side tools.
        String body =
                """
                {
                  "content": [
                    {"type": "tool_use", "id": "t1", "name": "get_weather", "input": {"city": "sf"}},
                    {"type": "text", "text": "hi"}
                  ]
                }
                """;
        assertTrue(emitAnthropic(body).isEmpty());
    }

    /**
     * Tags a request span for {@code provider} and returns the resulting {@code
     * braintrust.input_json} attribute (null when none was set).
     */
    private String inputJsonForRequest(String provider, String requestBody) {
        Span span = tracer.spanBuilder("llm").startSpan();
        InstrumentationSemConv.tagLLMSpanRequest(
                span,
                provider,
                "https://api.anthropic.com",
                List.of("v1", "messages"),
                "POST",
                requestBody);
        span.end();
        return exporter.getFinishedSpanItems().get(0).getAttributes().get(INPUT_JSON);
    }

    @Test
    void anthropicStringSystemPromptBecomesSystemRoleEntry() {
        String body =
                """
                {
                  "model": "claude-sonnet-4-5",
                  "system": "be terse",
                  "messages": [{"role": "user", "content": "hi"}]
                }
                """;

        JsonNode input =
                json(inputJsonForRequest(InstrumentationSemConv.PROVIDER_NAME_ANTHROPIC, body));
        assertEquals(2, input.size());
        assertEquals("system", input.get(1).path("role").asText());
        assertEquals("be terse", input.get(1).path("content").asText());
    }

    /**
     * Array-form {@code system} — the shape required to attach {@code cache_control} — must survive
     * intact. It previously tripped an {@code asText().isEmpty()} guard (containers stringify to
     * {@code ""}) and was dropped from the span input entirely.
     */
    @Test
    void anthropicArraySystemPromptIsPreservedWithCacheControl() {
        String body =
                """
                {
                  "model": "claude-sonnet-4-5",
                  "system": [
                    {"type": "text", "text": "you are a helpful assistant"},
                    {"type": "text", "text": "<corpus>", "cache_control": {"type": "ephemeral"}}
                  ],
                  "messages": [{"role": "user", "content": "hi"}]
                }
                """;

        JsonNode input =
                json(inputJsonForRequest(InstrumentationSemConv.PROVIDER_NAME_ANTHROPIC, body));
        assertEquals(2, input.size());
        JsonNode system = input.get(1);
        assertEquals("system", system.path("role").asText());
        JsonNode content = system.path("content");
        assertTrue(content.isArray(), "array-form system prompt should stay an array");
        assertEquals(2, content.size());
        assertEquals("you are a helpful assistant", content.get(0).path("text").asText());
        assertEquals("ephemeral", content.get(1).path("cache_control").path("type").asText());
    }

    @Test
    void anthropicAbsentOrEmptySystemPromptAddsNoEntry() {
        String noSystem =
                """
                {"model": "claude-sonnet-4-5", "messages": [{"role": "user", "content": "hi"}]}
                """;
        assertEquals(
                1,
                json(inputJsonForRequest(InstrumentationSemConv.PROVIDER_NAME_ANTHROPIC, noSystem))
                        .size());

        // An empty string, empty array, or explicit null carries no prompt.
        for (String system : List.of("\"\"", "[]", "null")) {
            String body =
                    "{\"model\": \"claude-sonnet-4-5\", \"system\": "
                            + system
                            + ", \"messages\": [{\"role\": \"user\", \"content\": \"hi\"}]}";
            exporter.reset();
            assertEquals(
                    1,
                    json(inputJsonForRequest(InstrumentationSemConv.PROVIDER_NAME_ANTHROPIC, body))
                            .size(),
                    "system=" + system + " should not add an entry");
        }
    }

    /**
     * Tags a Bedrock request span and returns its {@code braintrust.metadata} attribute. Bedrock
     * passes the model explicitly (it lives in the URL, not the body).
     */
    private String bedrockRequestMetadata(String requestBody) {
        Span span = tracer.spanBuilder("llm").startSpan();
        InstrumentationSemConv.tagLLMSpanRequest(
                span,
                InstrumentationSemConv.PROVIDER_NAME_BEDROCK,
                "https://bedrock-runtime.us-east-1.amazonaws.com",
                List.of("model", "us.anthropic.claude-haiku-4-5-20251001-v1:0", "converse"),
                "POST",
                requestBody,
                "us.anthropic.claude-haiku-4-5-20251001-v1:0");
        span.end();
        return exporter.getFinishedSpanItems().get(0).getAttributes().get(METADATA);
    }

    /** Tags a Bedrock response span and returns its {@code braintrust.metrics} attribute. */
    private String bedrockResponseMetrics(String responseBody) {
        Span span = tracer.spanBuilder("llm").startSpan();
        InstrumentationSemConv.tagLLMSpanResponse(
                tracer, span, InstrumentationSemConv.PROVIDER_NAME_BEDROCK, responseBody, null);
        span.end();
        return exporter.getFinishedSpanItems().get(0).getAttributes().get(METRICS);
    }

    /**
     * toolConfig carries the tools offered to the model and the tool-choice policy; both are
     * flattened onto metadata under the cross-provider key names.
     */
    @Test
    void bedrockToolConfigIsFlattenedIntoMetadata() {
        String body =
                """
                {
                  "inferenceConfig": {"maxTokens": 500},
                  "toolConfig": {
                    "tools": [
                      {"toolSpec": {
                         "name": "get_weather",
                         "description": "Current weather",
                         "inputSchema": {"json": {"type": "object"}}
                      }}
                    ],
                    "toolChoice": {"auto": {}}
                  },
                  "messages": [{"role": "user", "content": [{"text": "weather in Paris?"}]}]
                }
                """;

        JsonNode metadata = json(bedrockRequestMetadata(body));
        assertEquals(500, metadata.path("max_tokens").asInt(), "existing fields still captured");
        JsonNode tools = metadata.path("tools");
        assertTrue(tools.isArray(), "tools should be captured as an array");
        assertEquals(1, tools.size());
        // Bedrock's own toolSpec shape is preserved rather than rewritten.
        assertEquals("get_weather", tools.get(0).path("toolSpec").path("name").asText());
        assertTrue(metadata.path("tool_choice").has("auto"));
    }

    @Test
    void bedrockRequestWithoutToolConfigOmitsToolKeys() {
        String body =
                """
                {"messages": [{"role": "user", "content": [{"text": "hi"}]}]}
                """;

        JsonNode metadata = json(bedrockRequestMetadata(body));
        assertTrue(metadata.path("tools").isMissingNode());
        assertTrue(metadata.path("tool_choice").isMissingNode());
        assertEquals("bedrock", metadata.path("provider").asText());
    }

    /**
     * Bedrock reports cache usage as cacheReadInputTokens / cacheWriteInputTokens; these must land
     * on the same metric names the other providers use.
     */
    @Test
    void bedrockCacheTokensMapToCrossProviderMetricNames() {
        String body =
                """
                {
                  "output": {"message": {"role": "assistant", "content": [{"text": "hi"}]}},
                  "usage": {
                    "inputTokens": 1200,
                    "outputTokens": 350,
                    "totalTokens": 1550,
                    "cacheReadInputTokens": 800,
                    "cacheWriteInputTokens": 400,
                    "cacheDetails": [{"inputTokens": 800, "ttl": "5m"}]
                  }
                }
                """;

        JsonNode metrics = json(bedrockResponseMetrics(body));
        assertEquals(1200, metrics.path("prompt_tokens").asInt());
        assertEquals(350, metrics.path("completion_tokens").asInt());
        assertEquals(1550, metrics.path("tokens").asInt());
        assertEquals(800, metrics.path("prompt_cached_tokens").asInt());
        assertEquals(400, metrics.path("prompt_cache_creation_tokens").asInt());
        // cacheDetails is an array — never emitted as a metric.
        assertTrue(metrics.path("cacheDetails").isMissingNode());
    }

    /** A cold cache reports zeros, which must still be emitted rather than skipped. */
    @Test
    void bedrockZeroCacheTokensAreStillEmitted() {
        String body =
                """
                {
                  "output": {"message": {"role": "assistant", "content": [{"text": "hi"}]}},
                  "usage": {"inputTokens": 10, "outputTokens": 2, "totalTokens": 12,
                            "cacheReadInputTokens": 0, "cacheWriteInputTokens": 0}
                }
                """;

        JsonNode metrics = json(bedrockResponseMetrics(body));
        // Assert presence explicitly: path(...).asInt() is 0 for a missing node too, so an
        // equals-zero check alone would pass even if the metric were dropped.
        assertTrue(metrics.has("prompt_cached_tokens"), "cold-cache read count should be emitted");
        assertTrue(
                metrics.has("prompt_cache_creation_tokens"),
                "cold-cache write count should be emitted");
        assertEquals(0, metrics.get("prompt_cached_tokens").asInt());
        assertEquals(0, metrics.get("prompt_cache_creation_tokens").asInt());
    }

    /** Without prompt caching the cache metrics are absent, not zero-filled. */
    @Test
    void bedrockResponseWithoutCacheUsageOmitsCacheMetrics() {
        String body =
                """
                {
                  "output": {"message": {"role": "assistant", "content": [{"text": "hi"}]}},
                  "usage": {"inputTokens": 10, "outputTokens": 2, "totalTokens": 12}
                }
                """;

        JsonNode metrics = json(bedrockResponseMetrics(body));
        assertEquals(10, metrics.path("prompt_tokens").asInt());
        assertTrue(metrics.path("prompt_cached_tokens").isMissingNode());
        assertTrue(metrics.path("prompt_cache_creation_tokens").isMissingNode());
    }

    /** Tags a request span for {@code provider} and returns its {@code braintrust.metadata}. */
    private String requestMetadata(String provider, String endpoint, String requestBody) {
        Span span = tracer.spanBuilder("llm").startSpan();
        InstrumentationSemConv.tagLLMSpanRequest(
                span,
                provider,
                "https://api.example.com",
                List.of("v1", endpoint),
                "POST",
                requestBody);
        span.end();
        return exporter.getFinishedSpanItems().get(0).getAttributes().get(METADATA);
    }

    @Test
    void openAiGenerationParametersLandInMetadata() {
        String body =
                """
                {
                  "model": "gpt-4o",
                  "temperature": 0.7,
                  "max_tokens": 500,
                  "top_p": 0.95,
                  "frequency_penalty": 0.1,
                  "presence_penalty": 0.2,
                  "stop": ["\\n\\n"],
                  "reasoning_effort": "high",
                  "response_format": {"type": "json_object"},
                  "tools": [{"type": "function", "function": {"name": "get_weather"}}],
                  "messages": [{"role": "user", "content": "hi"}]
                }
                """;

        JsonNode metadata =
                json(
                        requestMetadata(
                                InstrumentationSemConv.PROVIDER_NAME_OPENAI,
                                "chat/completions",
                                body));

        assertEquals("gpt-4o", metadata.path("model").asText());
        assertEquals(0.7, metadata.path("temperature").asDouble());
        assertEquals(500, metadata.path("max_tokens").asInt());
        assertEquals(0.95, metadata.path("top_p").asDouble());
        assertEquals(0.1, metadata.path("frequency_penalty").asDouble());
        assertEquals(0.2, metadata.path("presence_penalty").asDouble());
        assertEquals("high", metadata.path("reasoning_effort").asText());
        assertEquals("json_object", metadata.path("response_format").path("type").asText());
        assertEquals(
                "get_weather",
                metadata.path("tools").get(0).path("function").path("name").asText());
        assertTrue(metadata.path("stop").isArray());
    }

    /** Anthropic's {@code thinking} config is the parameter most worth not losing. */
    @Test
    void anthropicGenerationParametersIncludingThinkingLandInMetadata() {
        String body =
                """
                {
                  "model": "claude-sonnet-4-5",
                  "max_tokens": 4096,
                  "temperature": 1.0,
                  "top_k": 40,
                  "stop_sequences": ["END"],
                  "thinking": {"type": "enabled", "budget_tokens": 2048},
                  "metadata": {"user_id": "u_1"},
                  "messages": [{"role": "user", "content": "hi"}]
                }
                """;

        JsonNode metadata =
                json(
                        requestMetadata(
                                InstrumentationSemConv.PROVIDER_NAME_ANTHROPIC, "messages", body));

        assertEquals(4096, metadata.path("max_tokens").asInt());
        assertEquals(1.0, metadata.path("temperature").asDouble());
        assertEquals(40, metadata.path("top_k").asInt());
        assertEquals("enabled", metadata.path("thinking").path("type").asText());
        assertEquals(2048, metadata.path("thinking").path("budget_tokens").asInt());
        assertEquals("u_1", metadata.path("metadata").path("user_id").asText());
        assertTrue(metadata.path("stop_sequences").isArray());
    }

    /**
     * Conversation content must not be duplicated into metadata — it is already the span input, and
     * copying it would double a potentially large payload.
     */
    @Test
    void contentFieldsAreNotCopiedIntoMetadata() {
        String openAiBody =
                """
                {
                  "model": "gpt-4o",
                  "instructions": "be terse",
                  "input": [{"role": "user", "content": "hi"}],
                  "temperature": 0.5
                }
                """;
        JsonNode openAi =
                json(
                        requestMetadata(
                                InstrumentationSemConv.PROVIDER_NAME_OPENAI,
                                "responses",
                                openAiBody));
        assertTrue(openAi.path("input").isMissingNode(), "input is content, not a parameter");
        assertTrue(
                openAi.path("instructions").isMissingNode(),
                "instructions belongs in span input, not metadata");
        assertEquals(0.5, openAi.path("temperature").asDouble(), "params still captured");

        exporter.reset();

        String anthropicBody =
                """
                {
                  "model": "claude-sonnet-4-5",
                  "system": [{"type": "text", "text": "be terse"}],
                  "messages": [{"role": "user", "content": "hi"}],
                  "max_tokens": 16
                }
                """;
        JsonNode anthropic =
                json(
                        requestMetadata(
                                InstrumentationSemConv.PROVIDER_NAME_ANTHROPIC,
                                "messages",
                                anthropicBody));
        assertTrue(anthropic.path("messages").isMissingNode());
        assertTrue(anthropic.path("system").isMissingNode());
        assertEquals(16, anthropic.path("max_tokens").asInt());
    }

    /** A body field cannot overwrite the routing metadata the tagger sets itself. */
    @Test
    void bodyFieldsCannotClobberReservedMetadataKeys() {
        String body =
                """
                {
                  "model": "gpt-4o",
                  "provider": "not-openai",
                  "request_method": "TRACE",
                  "request_path": "/evil",
                  "messages": [{"role": "user", "content": "hi"}]
                }
                """;

        JsonNode metadata =
                json(
                        requestMetadata(
                                InstrumentationSemConv.PROVIDER_NAME_OPENAI,
                                "chat/completions",
                                body));

        assertEquals("openai", metadata.path("provider").asText());
        assertEquals("POST", metadata.path("request_method").asText());
        assertEquals("v1/chat/completions", metadata.path("request_path").asText());
    }

    /** Explicit nulls carry no information and are skipped rather than emitted as JSON null. */
    @Test
    void nullValuedParametersAreSkipped() {
        String body =
                """
                {"model": "gpt-4o", "temperature": null, "max_tokens": 10,
                 "messages": [{"role": "user", "content": "hi"}]}
                """;

        JsonNode metadata =
                json(
                        requestMetadata(
                                InstrumentationSemConv.PROVIDER_NAME_OPENAI,
                                "chat/completions",
                                body));
        assertTrue(metadata.path("temperature").isMissingNode());
        assertEquals(10, metadata.path("max_tokens").asInt());
    }
}
