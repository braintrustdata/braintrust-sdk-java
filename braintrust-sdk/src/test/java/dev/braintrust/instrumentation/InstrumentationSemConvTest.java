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

/**
 * Covers {@link InstrumentationSemConv#addServerSideChildSpans}: only vendor-executed (server-side)
 * tool calls in an OpenAI Responses body become child spans nested under the LLM span. Client-side
 * calls ({@code function_call}, {@code computer_call}) are excluded.
 */
class InstrumentationSemConvTest {

    private static final AttributeKey<String> SPAN_ATTRIBUTES =
            AttributeKey.stringKey("braintrust.span_attributes");
    private static final AttributeKey<String> INPUT_JSON =
            AttributeKey.stringKey("braintrust.input_json");
    private static final AttributeKey<String> OUTPUT_JSON =
            AttributeKey.stringKey("braintrust.output_json");
    private static final AttributeKey<String> METADATA =
            AttributeKey.stringKey("braintrust.metadata");

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
}
