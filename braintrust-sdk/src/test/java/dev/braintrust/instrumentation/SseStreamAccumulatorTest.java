package dev.braintrust.instrumentation;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;
import java.util.List;
import lombok.SneakyThrows;
import org.junit.jupiter.api.Test;

/**
 * Hermetic tests for the endpoint-sniffing SSE accumulator: Responses API ({@code /v1/responses})
 * event streams reassemble into the terminal snapshot, and Chat Completions chunk streams keep
 * flowing through {@link SseResponseAccumulator}.
 */
class SseStreamAccumulatorTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    @SneakyThrows
    private static JsonNode reconstruct(String... chunks) {
        var acc = new SseStreamAccumulator(JSON);
        for (String chunk : chunks) {
            acc.merge(chunk);
        }
        return JSON.readTree(acc.build());
    }

    /** The event sequence OpenAI sends for a plain streamed {@code /v1/responses} text answer. */
    private static String[] textResponsesStream() {
        return new String[] {
            "{\"type\":\"response.created\",\"sequence_number\":0,\"response\":{\"id\":\"resp_1\",\"object\":\"response\",\"model\":\"gpt-4o-mini\",\"status\":\"in_progress\",\"output\":[],\"usage\":null}}",
            "{\"type\":\"response.in_progress\",\"sequence_number\":1,\"response\":{\"id\":\"resp_1\",\"object\":\"response\",\"model\":\"gpt-4o-mini\",\"status\":\"in_progress\",\"output\":[],\"usage\":null}}",
            "{\"type\":\"response.output_item.added\",\"sequence_number\":2,\"output_index\":0,\"item\":{\"id\":\"msg_1\",\"type\":\"message\",\"role\":\"assistant\",\"status\":\"in_progress\",\"content\":[]}}",
            "{\"type\":\"response.content_part.added\",\"sequence_number\":3,\"item_id\":\"msg_1\",\"output_index\":0,\"content_index\":0,\"part\":{\"type\":\"output_text\",\"text\":\"\",\"annotations\":[]}}",
            "{\"type\":\"response.output_text.delta\",\"sequence_number\":4,\"item_id\":\"msg_1\",\"output_index\":0,\"content_index\":0,\"delta\":\"Hello\"}",
            "{\"type\":\"response.output_text.delta\",\"sequence_number\":5,\"item_id\":\"msg_1\",\"output_index\":0,\"content_index\":0,\"delta\":\""
                + " world\"}",
            "{\"type\":\"response.output_text.done\",\"sequence_number\":6,\"item_id\":\"msg_1\",\"output_index\":0,\"content_index\":0,\"text\":\"Hello"
                + " world\"}",
            "{\"type\":\"response.content_part.done\",\"sequence_number\":7,\"item_id\":\"msg_1\",\"output_index\":0,\"content_index\":0,\"part\":{\"type\":\"output_text\",\"text\":\"Hello"
                + " world\",\"annotations\":[]}}",
            "{\"type\":\"response.output_item.done\",\"sequence_number\":8,\"output_index\":0,\"item\":{\"id\":\"msg_1\",\"type\":\"message\",\"role\":\"assistant\",\"status\":\"completed\",\"content\":[{\"type\":\"output_text\",\"text\":\"Hello"
                + " world\",\"annotations\":[]}]}}",
            "{\"type\":\"response.completed\",\"sequence_number\":9,\"response\":{\"id\":\"resp_1\",\"object\":\"response\",\"model\":\"gpt-4o-mini\",\"status\":\"completed\",\"output\":[{\"id\":\"msg_1\",\"type\":\"message\",\"role\":\"assistant\",\"status\":\"completed\",\"content\":[{\"type\":\"output_text\",\"text\":\"Hello"
                + " world\",\"annotations\":[]}]}],\"usage\":{\"input_tokens\":11,\"input_tokens_details\":{\"cached_tokens\":3},\"output_tokens\":5,\"output_tokens_details\":{\"reasoning_tokens\":2},\"total_tokens\":16}}}",
        };
    }

    @Test
    void reassemblesResponsesApiStreamFromTerminalSnapshot() {
        var response = reconstruct(textResponsesStream());

        // The Responses API reports output under "output", not "choices" — feeding these events to
        // the chat-completions accumulator instead would leave an empty "choices" array behind.
        assertFalse(response.has("choices"), "Responses output must not be shaped as choices");
        assertEquals("resp_1", response.get("id").asText());
        assertEquals("gpt-4o-mini", response.get("model").asText());
        assertEquals("completed", response.get("status").asText());

        JsonNode output = response.get("output");
        assertNotNull(output, "output must be present");
        assertEquals(1, output.size());
        JsonNode message = output.get(0);
        assertEquals("message", message.get("type").asText());
        assertEquals("Hello world", message.get("content").get(0).get("text").asText());

        // usage passes through so the semconv tool can map the Responses field names.
        JsonNode usage = response.get("usage");
        assertNotNull(usage, "usage must survive reconstruction");
        assertEquals(11, usage.get("input_tokens").asInt());
        assertEquals(5, usage.get("output_tokens").asInt());
        assertEquals(16, usage.get("total_tokens").asInt());
        assertEquals(2, usage.get("output_tokens_details").get("reasoning_tokens").asInt());
    }

    @Test
    void laterResponseSnapshotReplacesRatherThanMergesWithEarlierOne() {
        // response.created carries an empty output and a null usage; a naive field-wise merge would
        // either keep that empty array or concatenate the two snapshots' scalars.
        var response = reconstruct(textResponsesStream());

        assertEquals(1, response.get("output").size(), "created's empty output must not survive");
        assertEquals("resp_1", response.get("id").asText(), "ids must not be concatenated");
        assertEquals(
                "completed",
                response.get("status").asText(),
                "status must not be concatenated across snapshots");
    }

    @Test
    void preservesServerSideToolCallsInOutputOrder() {
        var response =
                reconstruct(
                        "{\"type\":\"response.created\",\"response\":{\"id\":\"resp_2\",\"output\":[]}}",
                        "{\"type\":\"response.output_item.added\",\"output_index\":0,\"item\":{\"id\":\"ws_1\",\"type\":\"web_search_call\",\"status\":\"in_progress\"}}",
                        "{\"type\":\"response.web_search_call.searching\",\"item_id\":\"ws_1\",\"output_index\":0}",
                        "{\"type\":\"response.completed\",\"response\":{\"id\":\"resp_2\",\"output\":[{\"id\":\"ws_1\",\"type\":\"web_search_call\",\"status\":\"completed\",\"action\":{\"type\":\"search\",\"query\":\"ai"
                            + " news\"}},{\"id\":\"msg_2\",\"type\":\"message\",\"role\":\"assistant\",\"content\":[{\"type\":\"output_text\",\"text\":\"News.\"}]}],\"usage\":{\"input_tokens\":300,\"output_tokens\":20,\"total_tokens\":320}}}");

        JsonNode output = response.get("output");
        assertEquals(2, output.size());
        // Order matters: the tool call precedes the assistant message, and the server-side tool
        // child spans are derived from these items.
        assertEquals("web_search_call", output.get(0).get("type").asText());
        assertEquals("search", output.get(0).get("action").get("type").asText());
        assertEquals("message", output.get(1).get("type").asText());
    }

    @Test
    void fallsBackToCompletedItemsWhenStreamEndsWithoutTerminalSnapshot() {
        // A stream cut off after its items completed but before response.completed: the items are
        // all we have, so report them rather than the empty output from response.created.
        var response =
                reconstruct(
                        "{\"type\":\"response.created\",\"response\":{\"id\":\"resp_3\",\"model\":\"gpt-4o-mini\",\"output\":[]}}",
                        "{\"type\":\"response.output_item.added\",\"output_index\":1,\"item\":{\"id\":\"msg_4\",\"type\":\"message\",\"content\":[]}}",
                        "{\"type\":\"response.output_item.done\",\"output_index\":0,\"item\":{\"id\":\"msg_3\",\"type\":\"message\",\"content\":[{\"type\":\"output_text\",\"text\":\"first\"}]}}",
                        "{\"type\":\"response.output_item.done\",\"output_index\":1,\"item\":{\"id\":\"msg_4\",\"type\":\"message\",\"content\":[{\"type\":\"output_text\",\"text\":\"second\"}]}}");

        assertEquals("resp_3", response.get("id").asText());
        JsonNode output = response.get("output");
        assertEquals(2, output.size(), "both completed items should be reported");
        // Emitted in output_index order even though index 1 was announced before index 0 finished,
        // and the .done item replaces the partial .added one.
        assertEquals("first", output.get(0).get("content").get(0).get("text").asText());
        assertEquals("second", output.get(1).get("content").get(0).get("text").asText());
    }

    @Test
    void stillReconstructsChatCompletionChunkStreams() {
        var response =
                reconstruct(
                        "{\"id\":\"cc\",\"object\":\"chat.completion.chunk\",\"model\":\"gpt-4o-mini\",\"choices\":[{\"index\":0,\"delta\":{\"role\":\"assistant\",\"content\":\"Par\"}}]}",
                        "{\"object\":\"chat.completion.chunk\",\"choices\":[{\"index\":0,\"delta\":{\"content\":\"is\"},\"finish_reason\":\"stop\"}]}",
                        "{\"object\":\"chat.completion.chunk\",\"choices\":[],\"usage\":{\"prompt_tokens\":9,\"completion_tokens\":2,\"total_tokens\":11}}",
                        "[DONE]");

        JsonNode choice = response.get("choices").get(0);
        assertEquals("Paris", choice.get("message").get("content").asText());
        assertEquals("stop", choice.get("finish_reason").asText());
        assertEquals(11, response.get("usage").get("total_tokens").asInt());
        assertFalse(response.has("output"), "chat completions must not grow a Responses output");
    }

    @Test
    void ignoresBlankNonJsonAndNonObjectPayloads() {
        var acc = new SseStreamAccumulator(JSON);
        acc.merge(null);
        acc.merge("");
        acc.merge("  ");
        acc.merge("[DONE]");
        acc.merge("not json");
        acc.merge("[1,2,3]");
        assertEquals("{\"choices\":[]}", acc.build(), "no events means nothing to reconstruct");
    }

    @Test
    void trailingErrorEventDoesNotClobberResponsesReconstruction() {
        // The Responses stream can end with a bare `error` event, which has no "response." type
        // prefix; it must not be routed into the chat-completions accumulator.
        var response =
                reconstruct(
                        "{\"type\":\"response.completed\",\"response\":{\"id\":\"resp_4\",\"output\":[{\"type\":\"message\",\"content\":[{\"type\":\"output_text\",\"text\":\"hi\"}]}]}}",
                        "{\"type\":\"error\",\"code\":\"server_error\",\"message\":\"boom\"}");

        assertEquals("resp_4", response.get("id").asText());
        assertEquals(1, response.get("output").size());
        assertFalse(response.has("choices"), "must not fall back to the chat-completions shape");
    }

    /**
     * End-to-end check that a reconstructed Responses stream tags a span the way the
     * instrumentation modules consume it: output from {@code output}, token metrics from the
     * Responses {@code usage} field names, and a child tool span per server-side tool call.
     */
    @Test
    @SneakyThrows
    void tagsSpanWithOutputMetricsAndToolChildSpansFromResponsesStream() {
        var acc = new SseStreamAccumulator(JSON);
        for (String chunk : textResponsesStream()) {
            acc.merge(chunk);
        }
        acc.merge(
                "{\"type\":\"response.completed\",\"response\":{\"id\":\"resp_1\",\"model\":\"gpt-4o-mini\",\"output\":[{\"id\":\"ws_1\",\"type\":\"web_search_call\",\"status\":\"completed\",\"action\":{\"type\":\"search\"}},{\"id\":\"msg_1\",\"type\":\"message\",\"role\":\"assistant\",\"content\":[{\"type\":\"output_text\",\"text\":\"Hello"
                    + " world\"}]}],\"usage\":{\"input_tokens\":11,\"output_tokens\":5,\"total_tokens\":16}}}");

        var exporter = InMemorySpanExporter.create();
        try (var tracerProvider =
                SdkTracerProvider.builder()
                        .addSpanProcessor(SimpleSpanProcessor.create(exporter))
                        .build()) {
            var tracer = tracerProvider.get("test");
            var span = tracer.spanBuilder("llm").startSpan();
            InstrumentationSemConv.tagLLMSpanResponse(
                    tracer,
                    span,
                    InstrumentationSemConv.PROVIDER_NAME_OPENAI,
                    acc.build(),
                    1_500_000_000L);
            span.end();

            List<SpanData> spans = exporter.getFinishedSpanItems();
            SpanData llmSpan =
                    spans.stream().filter(s -> s.getName().equals("llm")).findFirst().orElseThrow();

            JsonNode output =
                    JSON.readTree(
                            llmSpan.getAttributes()
                                    .get(AttributeKey.stringKey("braintrust.output_json")));
            assertEquals(2, output.size(), "span output should be the Responses output array");
            assertEquals("Hello world", output.get(1).get("content").get(0).get("text").asText());

            JsonNode metrics =
                    JSON.readTree(
                            llmSpan.getAttributes()
                                    .get(AttributeKey.stringKey("braintrust.metrics")));
            assertEquals(11, metrics.get("prompt_tokens").asInt());
            assertEquals(5, metrics.get("completion_tokens").asInt());
            assertEquals(16, metrics.get("tokens").asInt());
            assertEquals(1.5, metrics.get("time_to_first_token").asDouble(), 1e-9);

            assertTrue(
                    spans.stream().anyMatch(s -> s.getName().equals("web_search_call")),
                    "server-side tool calls in the streamed output should emit child tool spans");
        }
    }

    // ---------------------------------------------------------------------
    // carriesGeneratedOutput — drives time-to-first-token
    // ---------------------------------------------------------------------

    private static boolean isOutput(String chunk) {
        return SseStreamAccumulator.carriesGeneratedOutput(JSON, chunk);
    }

    /**
     * A Responses stream opens with lifecycle events before the model produces anything. Timing
     * TTFT from these measures response setup, which for a reasoning model can precede the first
     * real token by seconds.
     */
    @Test
    void responsesLifecycleEventsAreNotGeneratedOutput() {
        assertFalse(isOutput("{\"type\":\"response.created\",\"response\":{\"id\":\"r1\"}}"));
        assertFalse(isOutput("{\"type\":\"response.in_progress\",\"response\":{\"id\":\"r1\"}}"));
        assertFalse(isOutput("{\"type\":\"response.queued\",\"response\":{\"id\":\"r1\"}}"));
    }

    @Test
    void responsesOutputEventsAreGeneratedOutput() {
        assertTrue(isOutput("{\"type\":\"response.output_text.delta\",\"delta\":\"Par\"}"));
        assertTrue(
                isOutput("{\"type\":\"response.function_call_arguments.delta\",\"delta\":\"{\"}"));
        assertTrue(
                isOutput("{\"type\":\"response.reasoning_summary_text.delta\",\"delta\":\"h\"}"));
        assertTrue(
                isOutput(
                        "{\"type\":\"response.output_item.added\",\"output_index\":0,\"item\":{}}"));
        assertTrue(
                isOutput(
                        "{\"type\":\"response.content_part.added\",\"output_index\":0,\"part\":{}}"));
    }

    /** The opening chat-completions chunk carries only the assistant role, not a token yet. */
    @Test
    void chatCompletionsRoleOnlyChunkIsNotGeneratedOutput() {
        assertFalse(
                isOutput(
                        "{\"object\":\"chat.completion.chunk\",\"choices\":[{\"index\":0,"
                                + "\"delta\":{\"role\":\"assistant\",\"content\":\"\"}}]}"));
    }

    @Test
    void chatCompletionsContentAndToolCallChunksAreGeneratedOutput() {
        assertTrue(
                isOutput(
                        "{\"object\":\"chat.completion.chunk\",\"choices\":[{\"index\":0,"
                                + "\"delta\":{\"content\":\"Paris\"}}]}"));
        assertTrue(
                isOutput(
                        "{\"object\":\"chat.completion.chunk\",\"choices\":[{\"index\":0,"
                                + "\"delta\":{\"tool_calls\":[{\"index\":0,\"id\":\"t\"}]}}]}"));
        assertTrue(
                isOutput(
                        "{\"object\":\"chat.completion.chunk\",\"choices\":[{\"index\":0,"
                                + "\"delta\":{\"reasoning_content\":\"hmm\"}}]}"));
    }

    /** Sentinels, blanks, and malformed payloads must not be mistaken for output. */
    @Test
    void nonOutputPayloadsAreRejected() {
        assertFalse(isOutput(null));
        assertFalse(isOutput(""));
        assertFalse(isOutput("   "));
        assertFalse(isOutput("[DONE]"));
        assertFalse(isOutput("not json"));
        assertFalse(isOutput("[1,2,3]"));
        assertFalse(isOutput("{\"object\":\"chat.completion.chunk\",\"choices\":[]}"));
    }
}
