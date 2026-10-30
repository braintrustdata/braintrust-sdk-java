package dev.braintrust.instrumentation.awsbedrock.v2_30_0;

import static org.junit.jupiter.api.Assertions.*;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import software.amazon.eventstream.HeaderValue;
import software.amazon.eventstream.Message;

/** Frames are produced by the AWS SDK's own encoder so the decoder is checked against the spec. */
class EventStreamDecoderTest {
    private static byte[] frame(String eventType, String payload) throws Exception {
        Map<String, HeaderValue> headers = new LinkedHashMap<>();
        // Non-string header types, so the decoder must skip their values correctly. byte and short
        // are omitted: eventstream 1.0.1's encoder writes no value bytes for them (spec: 1 and 2).
        headers.put("bool-true", HeaderValue.fromBoolean(true));
        headers.put("bool-false", HeaderValue.fromBoolean(false));
        headers.put("int", HeaderValue.fromInteger(7));
        headers.put("long", HeaderValue.fromLong(7L));
        headers.put("bytes", HeaderValue.fromByteArray(new byte[] {1, 2, 3}));
        headers.put("timestamp", HeaderValue.fromTimestamp(Instant.EPOCH));
        headers.put("uuid", HeaderValue.fromUuid(UUID.randomUUID()));
        headers.put(":event-type", HeaderValue.fromString(eventType));
        var out = new ByteArrayOutputStream();
        new Message(headers, payload.getBytes(StandardCharsets.UTF_8)).encode(out);
        return out.toByteArray();
    }

    private static byte[] concat(byte[]... parts) {
        var out = new ByteArrayOutputStream();
        for (byte[] part : parts) out.writeBytes(part);
        return out.toByteArray();
    }

    @Test
    void decodesMultipleFramesInOneChunk() throws Exception {
        var decoder = new EventStreamDecoder();
        var messages =
                decoder.feed(
                        concat(frame("messageStart", "{\"a\":1}"), frame("messageStop", "{}")));

        assertEquals(2, messages.size());
        assertEquals("messageStart", messages.get(0).headers().get(":event-type"));
        assertEquals("{\"a\":1}", new String(messages.get(0).payload(), StandardCharsets.UTF_8));
        assertEquals("messageStop", messages.get(1).headers().get(":event-type"));
        assertFalse(messages.get(0).headers().containsKey("int"), "only string headers are kept");
    }

    @Test
    void decodesFramesSplitAcrossChunks() throws Exception {
        byte[] bytes =
                concat(frame("contentBlockDelta", "{\"text\":\"hi\"}"), frame("messageStop", "{}"));
        var decoder = new EventStreamDecoder();
        List<EventStreamDecoder.Message> messages = new ArrayList<>();
        for (byte b : bytes) {
            messages.addAll(decoder.feed(new byte[] {b}));
        }

        assertEquals(2, messages.size());
        assertEquals("contentBlockDelta", messages.get(0).headers().get(":event-type"));
        assertEquals(
                "{\"text\":\"hi\"}", new String(messages.get(0).payload(), StandardCharsets.UTF_8));
    }

    @Test
    void decodesFramesLargerThanInitialBuffer() throws Exception {
        String payload = "x".repeat(50_000);
        var messages = new EventStreamDecoder().feed(frame("contentBlockDelta", payload));

        assertEquals(1, messages.size());
        assertEquals(payload, new String(messages.get(0).payload(), StandardCharsets.UTF_8));
    }

    @Test
    void stopsDecodingAfterCorruptFrame() throws Exception {
        byte[] corrupt = frame("messageStart", "{}");
        corrupt[corrupt.length - 1] ^= 1; // break the message CRC
        var decoder = new EventStreamDecoder();

        assertThrows(IllegalStateException.class, () -> decoder.feed(corrupt));
        assertEquals(List.of(), decoder.feed(frame("messageStop", "{}")));
    }

    @Test
    void rejectsCorruptPrelude() throws Exception {
        byte[] corrupt = Arrays.copyOf(frame("messageStart", "{}"), 12);
        corrupt[0] ^= 1;

        assertThrows(IllegalStateException.class, () -> new EventStreamDecoder().feed(corrupt));
    }
}
