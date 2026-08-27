package dev.braintrust.instrumentation.anthropic.v2_2_0;

import com.anthropic.helpers.MessageAccumulator;
import com.anthropic.models.messages.RawMessageStreamEvent;
import dev.braintrust.json.BraintrustJsonMapper;
import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import javax.annotation.Nullable;
import lombok.extern.slf4j.Slf4j;

/**
 * Turns the raw bytes of an Anthropic response into a single JSON document the semconv layer can
 * tag.
 *
 * <p>All of the wire-format bookkeeping lives here — SSE-vs-plain-JSON detection and chunk
 * reassembly — so that {@code TracingHttpClient} is left holding only the span lifecycle and one
 * flat call into {@code InstrumentationSemConv}.
 */
@Slf4j
class ResponseReassembler {

    private ResponseReassembler() {}

    /**
     * A reassembled response body plus the timing that belongs with it.
     *
     * <p>{@code body} is null when there was nothing usable to reassemble — an empty response, or
     * one we couldn't parse. Callers should still tag the response in that case; the headers remain
     * worth recording.
     *
     * <p>{@code timeToFirstTokenNanos} is only populated for a stream, since a non-streaming
     * response has no first token to time.
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
            // Anthropic SSE starts with "event: message_start\ndata: ..." so we detect either
            // prefix. OpenAI SSE starts directly with "data:".
            boolean isSse =
                    firstLine != null
                            && (firstLine.startsWith("data:") || firstLine.startsWith("event:"));
            if (isSse) {
                return new Result(reassembleSse(bytes), timeToFirstTokenNanos);
            }
            // Non-streaming: plain Message JSON — pass it whole, no time_to_first_token
            return new Result(new String(bytes, StandardCharsets.UTF_8), null);
        } catch (Exception e) {
            log.error("Could not reassemble Anthropic response buffer", e);
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
     * Anthropic SSE wire format has named events:
     *
     * <pre>
     * event: message_start
     * data: {"type":"message_start","message":{...}}
     *
     * event: content_block_delta
     * data: {"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"Hi"}}
     * </pre>
     *
     * We only need the {@code data:} lines — the event name is redundant with the {@code type}
     * field inside the JSON. Feed each data payload to {@link MessageAccumulator} and serialize the
     * assembled {@link com.anthropic.models.messages.Message}.
     */
    @Nullable
    private static String reassembleSse(byte[] sseBytes) {
        try {
            var mapper = BraintrustJsonMapper.get();
            var reader =
                    new BufferedReader(
                            new InputStreamReader(
                                    new ByteArrayInputStream(sseBytes), StandardCharsets.UTF_8));
            var accumulator = MessageAccumulator.create();
            String line;
            while ((line = reader.readLine()) != null) {
                if (!line.startsWith("data:")) continue;
                String data = line.substring("data:".length()).strip();
                if (data.isEmpty()) continue;
                try {
                    accumulator.accumulate(mapper.readValue(data, RawMessageStreamEvent.class));
                } catch (Exception ignored) {
                    // skip unrecognized event types (e.g. ping)
                }
            }
            return BraintrustJsonMapper.toJson(accumulator.message());
        } catch (Exception e) {
            log.error("Could not parse Anthropic SSE buffer to tag streaming span output", e);
            return null;
        }
    }
}
