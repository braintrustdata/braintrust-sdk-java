package dev.braintrust.instrumentation.openai.v2_15_0;

import com.fasterxml.jackson.databind.json.JsonMapper;
import com.openai.core.ObjectMappers;
import com.openai.helpers.ChatCompletionAccumulator;
import com.openai.helpers.ResponseAccumulator;
import com.openai.models.chat.completions.ChatCompletionChunk;
import com.openai.models.responses.ResponseStreamEvent;
import dev.braintrust.json.BraintrustJsonMapper;
import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import javax.annotation.Nullable;
import lombok.extern.slf4j.Slf4j;

/**
 * Turns the raw bytes of an OpenAI response into a single JSON document the semconv layer can tag.
 *
 * <p>All of the wire-format bookkeeping lives here — SSE-vs-plain-JSON detection, per-endpoint
 * accumulator selection, chunk reassembly — so that {@code TracingHttpClient} is left holding only
 * the span lifecycle and one flat call into {@code InstrumentationSemConv}.
 */
@Slf4j
class ResponseReassembler {
    private static final JsonMapper JSON_MAPPER = ObjectMappers.jsonMapper();

    private ResponseReassembler() {}

    /**
     * A reassembled response body plus the timing that belongs with it.
     *
     * <p>{@code body} is null when there was nothing usable to reassemble — an empty response, or
     * an SSE stream whose shape we don't recognize. Callers should still tag the response in that
     * case; the headers remain worth recording.
     *
     * <p>{@code timeToFirstTokenNanos} is only populated for a stream, since a non-streaming
     * response has no first token to time and a zero would land in latency aggregates as if it were
     * a real measurement.
     */
    record Result(@Nullable String body, @Nullable Long timeToFirstTokenNanos) {
        static final Result EMPTY = new Result(null, null);
    }

    /** Detects the wire format and reassembles accordingly. Never throws. */
    static Result reassemble(byte[] bytes, long timeToFirstTokenNanos) {
        if (bytes.length == 0) {
            return Result.EMPTY;
        }
        try {
            String firstLine = firstNonEmptyLine(bytes);
            boolean isSse =
                    firstLine != null
                            && (firstLine.startsWith("data:") || firstLine.startsWith("event:"));
            if (isSse) {
                return new Result(reassembleSse(bytes), timeToFirstTokenNanos);
            }
            return new Result(new String(bytes, StandardCharsets.UTF_8), null);
        } catch (Exception e) {
            log.error("Could not reassemble OpenAI response buffer", e);
            return Result.EMPTY;
        }
    }

    @Nullable
    private static String firstNonEmptyLine(byte[] bytes) {
        int start = 0;
        for (int i = 0; i <= bytes.length; i++) {
            if (i == bytes.length || bytes[i] == '\n') {
                String line = new String(bytes, start, i - start, StandardCharsets.UTF_8).strip();
                if (!line.isEmpty()) return line;
                start = i + 1;
            }
        }
        return null;
    }

    /**
     * Parses SSE wire bytes and feeds each {@code data:} chunk through the accumulator matching the
     * stream's shape, returning the reassembled response JSON — or null if the shape was not
     * recognized.
     */
    @Nullable
    private static String reassembleSse(byte[] sseBytes) {
        try {
            var reader =
                    new BufferedReader(
                            new InputStreamReader(
                                    new ByteArrayInputStream(sseBytes), StandardCharsets.UTF_8));
            String line;
            String responseJson = null;
            while ((line = reader.readLine()) != null) {
                if (!line.startsWith("data:")) continue;
                var firstEventJson = line.substring("data:".length()).strip();
                // after the first data chunk is found, read the rest of the stream with the proper
                // accumulator type
                var jsonTree = JSON_MAPPER.readTree(firstEventJson);
                if (jsonTree.has("type") && jsonTree.get("type").asText().startsWith("response")) {
                    // response API SSEvents
                    ResponseAccumulator accumulator = ResponseAccumulator.create();
                    accumulator.accumulate(
                            JSON_MAPPER.readValue(firstEventJson, ResponseStreamEvent.class));
                    while ((line = reader.readLine()) != null) {
                        if (!line.startsWith("data:")) continue;
                        String data = line.substring("data:".length()).strip();
                        if (data.isEmpty() || data.equals("[DONE]")) continue;
                        ResponseStreamEvent rse =
                                JSON_MAPPER.readValue(data, ResponseStreamEvent.class);
                        accumulator.accumulate(rse);
                    }
                    responseJson = JSON_MAPPER.writeValueAsString(accumulator.response());
                } else if (jsonTree.has("object")
                        && jsonTree.get("object").asText().equals("chat.completion.chunk")) {
                    // completions API SSEvents
                    var accumulator = ChatCompletionAccumulator.create();
                    accumulator.accumulate(
                            JSON_MAPPER.readValue(firstEventJson, ChatCompletionChunk.class));
                    while ((line = reader.readLine()) != null) {
                        if (!line.startsWith("data:")) continue;
                        String data = line.substring("data:".length()).strip();
                        if (data.isEmpty() || data.equals("[DONE]")) continue;
                        ChatCompletionChunk chunk =
                                BraintrustJsonMapper.get()
                                        .readValue(data, ChatCompletionChunk.class);
                        accumulator.accumulate(chunk);
                    }
                    responseJson = JSON_MAPPER.writeValueAsString(accumulator.chatCompletion());
                } else {
                    log.warn("unknown SSE object {}", firstEventJson);
                }
                break;
            }
            return responseJson;
        } catch (Exception e) {
            log.error("Could not parse SSE buffer to tag streaming span output", e);
            return null;
        }
    }
}
