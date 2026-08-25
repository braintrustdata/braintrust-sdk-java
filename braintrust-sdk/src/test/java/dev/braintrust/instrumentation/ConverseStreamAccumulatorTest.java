package dev.braintrust.instrumentation;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import lombok.SneakyThrows;
import org.junit.jupiter.api.Test;

/**
 * Hermetic tests for Bedrock {@code ConverseStream} reassembly. The streaming path previously
 * accumulated only {@code delta.text} and emitted a single synthetic text block, so a streamed tool
 * call or reasoning block reached the span as empty text. These assert the real content-block
 * shapes survive, without needing AWS credentials or a recorded cassette.
 */
class ConverseStreamAccumulatorTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    /** A single {@code (eventType, payload)} event-stream frame. */
    private record Frame(String type, String payload) {}

    private static Frame frame(String type, String payload) {
        return new Frame(type, payload);
    }

    @SneakyThrows
    private static JsonNode reassemble(Frame... frames) {
        var acc = new ConverseStreamAccumulator(JSON);
        for (Frame f : frames) {
            acc.accept(f.type(), f.payload());
        }
        return JSON.readTree(acc.build());
    }

    private static JsonNode content(JsonNode response) {
        return response.path("output").path("message").path("content");
    }

    /**
     * The shape the {@code bedrock/converse_stream} cross-SDK spec pins: one text block under
     * {@code output.message.content}. Guards the common path against regression from the rewrite.
     */
    @Test
    void plainTextStreamProducesSingleTextBlock() {
        JsonNode response =
                reassemble(
                        frame("messageStart", "{\"role\":\"assistant\"}"),
                        frame(
                                "contentBlockDelta",
                                "{\"contentBlockIndex\":0,\"delta\":{\"text\":\"Par\"}}"),
                        frame(
                                "contentBlockDelta",
                                "{\"contentBlockIndex\":0,\"delta\":{\"text\":\"is\"}}"),
                        frame("contentBlockStop", "{\"contentBlockIndex\":0}"),
                        frame("messageStop", "{\"stopReason\":\"end_turn\"}"),
                        frame(
                                "metadata",
                                "{\"usage\":{\"inputTokens\":12,\"outputTokens\":3,\"totalTokens\":15}}"));

        assertEquals("assistant", response.path("output").path("message").path("role").asText());
        assertEquals(1, content(response).size());
        assertEquals("Paris", content(response).get(0).path("text").asText());
        assertEquals("end_turn", response.path("stopReason").asText());
        assertEquals(15, response.path("usage").path("totalTokens").asInt());
    }

    /**
     * The #85 regression: {@code contentBlockStart} carries the tool's id and name (no later delta
     * repeats them) and {@code delta.toolUse.input} streams the arguments as JSON fragments.
     */
    @Test
    void toolUseStreamPreservesIdNameAndParsedInput() {
        JsonNode response =
                reassemble(
                        frame(
                                "contentBlockStart",
                                "{\"contentBlockIndex\":0,\"start\":{\"toolUse\":"
                                        + "{\"toolUseId\":\"tu_1\",\"name\":\"get_weather\"}}}"),
                        frame(
                                "contentBlockDelta",
                                "{\"contentBlockIndex\":0,\"delta\":{\"toolUse\":"
                                        + "{\"input\":\"{\\\"city\\\":\"}}}"),
                        frame(
                                "contentBlockDelta",
                                "{\"contentBlockIndex\":0,\"delta\":{\"toolUse\":"
                                        + "{\"input\":\"\\\"sf\\\"}\"}}}"),
                        frame("messageStop", "{\"stopReason\":\"tool_use\"}"));

        assertEquals(1, content(response).size());
        JsonNode toolUse = content(response).get(0).path("toolUse");
        assertEquals("tu_1", toolUse.path("toolUseId").asText());
        assertEquals("get_weather", toolUse.path("name").asText());
        assertTrue(toolUse.path("input").isObject(), "fragments should parse back into an object");
        assertEquals("sf", toolUse.path("input").path("city").asText());
        assertEquals("tool_use", response.path("stopReason").asText());
    }

    /** Text and a tool call in one message must both survive, in content-block index order. */
    @Test
    void textAndToolUseBlocksBothSurviveInIndexOrder() {
        JsonNode response =
                reassemble(
                        // Deliberately fed out of order to prove ordering is by index, not arrival.
                        frame(
                                "contentBlockStart",
                                "{\"contentBlockIndex\":1,\"start\":{\"toolUse\":"
                                        + "{\"toolUseId\":\"tu_2\",\"name\":\"lookup\"}}}"),
                        frame(
                                "contentBlockDelta",
                                "{\"contentBlockIndex\":0,\"delta\":{\"text\":\"checking\"}}"),
                        frame(
                                "contentBlockDelta",
                                "{\"contentBlockIndex\":1,\"delta\":{\"toolUse\":{\"input\":\"{}\"}}}"));

        assertEquals(2, content(response).size());
        assertEquals("checking", content(response).get(0).path("text").asText());
        assertEquals("lookup", content(response).get(1).path("toolUse").path("name").asText());
    }

    /**
     * Reasoning deltas arrive flat but a synchronous response nests them under {@code
     * reasoningText}; the accumulator rebuilds the nested shape so both paths normalize alike.
     */
    @Test
    void reasoningStreamRebuildsNestedReasoningTextShape() {
        JsonNode response =
                reassemble(
                        frame(
                                "contentBlockDelta",
                                "{\"contentBlockIndex\":0,\"delta\":{\"reasoningContent\":"
                                        + "{\"text\":\"Let me \"}}}"),
                        frame(
                                "contentBlockDelta",
                                "{\"contentBlockIndex\":0,\"delta\":{\"reasoningContent\":"
                                        + "{\"text\":\"think.\"}}}"),
                        frame(
                                "contentBlockDelta",
                                "{\"contentBlockIndex\":0,\"delta\":{\"reasoningContent\":"
                                        + "{\"signature\":\"sig123\"}}}"),
                        frame(
                                "contentBlockDelta",
                                "{\"contentBlockIndex\":1,\"delta\":{\"text\":\"Paris\"}}"));

        assertEquals(2, content(response).size());
        JsonNode reasoningText =
                content(response).get(0).path("reasoningContent").path("reasoningText");
        assertEquals("Let me think.", reasoningText.path("text").asText());
        assertEquals("sig123", reasoningText.path("signature").asText());
        assertEquals("Paris", content(response).get(1).path("text").asText());
    }

    /** {@code redactedContent} is a sibling of {@code reasoningText}, not nested inside it. */
    @Test
    void redactedReasoningStaysSiblingOfReasoningText() {
        JsonNode response =
                reassemble(
                        frame(
                                "contentBlockDelta",
                                "{\"contentBlockIndex\":0,\"delta\":{\"reasoningContent\":"
                                        + "{\"redactedContent\":\"YmFzZTY0\"}}}"));

        JsonNode reasoningContent = content(response).get(0).path("reasoningContent");
        assertEquals("YmFzZTY0", reasoningContent.path("redactedContent").asText());
        assertTrue(reasoningContent.path("reasoningText").isMissingNode());
    }

    /** Tool arguments truncated mid-stream are surfaced as a string rather than dropped. */
    @Test
    void unparseableToolInputIsKeptAsString() {
        JsonNode response =
                reassemble(
                        frame(
                                "contentBlockStart",
                                "{\"contentBlockIndex\":0,\"start\":{\"toolUse\":"
                                        + "{\"toolUseId\":\"tu_3\",\"name\":\"f\"}}}"),
                        frame(
                                "contentBlockDelta",
                                "{\"contentBlockIndex\":0,\"delta\":{\"toolUse\":"
                                        + "{\"input\":\"{\\\"a\\\":\"}}}"));

        JsonNode input = content(response).get(0).path("toolUse").path("input");
        assertTrue(input.isTextual(), "truncated fragments should survive as a string");
        assertEquals("{\"a\":", input.asText());
    }

    /** A tool call whose start frame was missed still keeps its arguments. */
    @Test
    void toolUseDeltaWithoutStartFrameStillKeepsInput() {
        JsonNode response =
                reassemble(
                        frame(
                                "contentBlockDelta",
                                "{\"contentBlockIndex\":0,\"delta\":{\"toolUse\":"
                                        + "{\"input\":\"{\\\"x\\\":1}\"}}}"));

        JsonNode toolUse = content(response).get(0).path("toolUse");
        assertEquals(1, toolUse.path("input").path("x").asInt());
        assertTrue(toolUse.path("name").isMissingNode());
    }

    @Test
    void malformedAndUnknownFramesAreIgnored() {
        JsonNode response =
                reassemble(
                        frame("contentBlockDelta", "not json at all"),
                        frame("contentBlockDelta", "[1,2,3]"),
                        frame("somethingNewInAFutureApiVersion", "{\"whatever\":true}"),
                        frame(null, "{\"text\":\"x\"}"),
                        frame("contentBlockDelta", null),
                        frame(
                                "contentBlockDelta",
                                "{\"contentBlockIndex\":0,\"delta\":{\"text\":\"ok\"}}"));

        assertEquals(1, content(response).size());
        assertEquals("ok", content(response).get(0).path("text").asText());
    }

    /** An empty stream yields a well-formed body with no content, not a fabricated text block. */
    @Test
    void emptyStreamProducesNoContentBlocks() {
        JsonNode response = reassemble();
        assertTrue(content(response).isArray());
        assertEquals(0, content(response).size());
        assertTrue(response.path("usage").isMissingNode(), "usage must not be fabricated");
    }

    /**
     * End-to-end: the reassembled body goes through the real Bedrock response tagger, which must
     * annotate every block with the {@code type} the UI schema requires — including {@code
     * reasoningContent}, the gap issue #150 describes.
     */
    @Test
    @SneakyThrows
    void taggedStreamingOutputCarriesTypeOnEveryBlockShape() {
        var acc = new ConverseStreamAccumulator(JSON);
        acc.accept(
                "contentBlockDelta",
                "{\"contentBlockIndex\":0,\"delta\":{\"reasoningContent\":{\"text\":\"hmm\"}}}");
        acc.accept("contentBlockDelta", "{\"contentBlockIndex\":1,\"delta\":{\"text\":\"hi\"}}");
        acc.accept(
                "contentBlockStart",
                "{\"contentBlockIndex\":2,\"start\":{\"toolUse\":{\"toolUseId\":\"t\",\"name\":\"f\"}}}");
        acc.accept(
                "contentBlockDelta",
                "{\"contentBlockIndex\":2,\"delta\":{\"toolUse\":{\"input\":\"{}\"}}}");

        var exporter = io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter.create();
        try (var tracerProvider =
                io.opentelemetry.sdk.trace.SdkTracerProvider.builder()
                        .addSpanProcessor(
                                io.opentelemetry.sdk.trace.export.SimpleSpanProcessor.create(
                                        exporter))
                        .build()) {
            var tracer = tracerProvider.get("test");
            var span = tracer.spanBuilder("llm").startSpan();
            InstrumentationSemConv.tagLLMSpanResponse(
                    tracer, span, InstrumentationSemConv.PROVIDER_NAME_BEDROCK, acc.build(), null);
            span.end();

            String outputJson =
                    exporter.getFinishedSpanItems()
                            .get(0)
                            .getAttributes()
                            .get(
                                    io.opentelemetry.api.common.AttributeKey.stringKey(
                                            "braintrust.output_json"));
            JsonNode blocks = JSON.readTree(outputJson).get(0).path("content");
            assertEquals(
                    List.of("thinking", "text", "tool_use"),
                    List.of(
                            blocks.get(0).path("type").asText(),
                            blocks.get(1).path("type").asText(),
                            blocks.get(2).path("type").asText()));
        }
    }
}
