package dev.braintrust.instrumentation.awsbedrock.v2_30_0;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.CRC32;

/**
 * Minimal incremental decoder for the AWS event-stream framing used by Bedrock ConverseStream.
 *
 * <p>We own this instead of using the AWS decoder because AWS SDK 2.57.0 moved it from {@code
 * software.amazon.eventstream} to {@code software.amazon.awssdk.eventstream}; depending on either
 * package would break instrumentation for the other half of the supported range.
 *
 * <p>Frame layout (big-endian): {@code total length (4) | headers length (4) | prelude CRC32 (4) |
 * headers | payload | message CRC32 (4)}. Only string-typed headers are retained.
 */
final class EventStreamDecoder {
    record Message(Map<String, String> headers, byte[] payload) {}

    private static final int PRELUDE_LENGTH = 12;
    private static final int TRAILER_LENGTH = 4;
    private static final int MAX_MESSAGE_LENGTH = 16 * 1024 * 1024;

    private byte[] buf = new byte[8192];
    private int len = 0;
    private boolean failed = false;

    /**
     * Appends {@code bytes} and returns the messages they complete. Once malformed input is seen
     * the decoder throws and returns no further messages, since frame boundaries are lost.
     */
    List<Message> feed(byte[] bytes) {
        if (failed) {
            return List.of();
        }
        if (len + bytes.length > buf.length) {
            buf = Arrays.copyOf(buf, Math.max(len + bytes.length, buf.length * 2));
        }
        System.arraycopy(bytes, 0, buf, len, bytes.length);
        len += bytes.length;

        List<Message> messages = new ArrayList<>();
        ByteBuffer bb = ByteBuffer.wrap(buf);
        int pos = 0;
        try {
            while (len - pos >= PRELUDE_LENGTH) {
                int totalLength = bb.getInt(pos);
                int headersLength = bb.getInt(pos + 4);
                if (totalLength < PRELUDE_LENGTH + TRAILER_LENGTH
                        || totalLength > MAX_MESSAGE_LENGTH
                        || headersLength < 0
                        || headersLength > totalLength - PRELUDE_LENGTH - TRAILER_LENGTH) {
                    throw new IllegalStateException("Invalid event-stream prelude");
                }
                if (crc32(pos, 8) != bb.getInt(pos + 8)) {
                    throw new IllegalStateException("Event-stream prelude CRC mismatch");
                }
                if (len - pos < totalLength) {
                    break;
                }
                int trailerStart = pos + totalLength - TRAILER_LENGTH;
                if (crc32(pos, totalLength - TRAILER_LENGTH) != bb.getInt(trailerStart)) {
                    throw new IllegalStateException("Event-stream message CRC mismatch");
                }
                int headersStart = pos + PRELUDE_LENGTH;
                int payloadStart = headersStart + headersLength;
                messages.add(
                        new Message(
                                decodeHeaders(bb, headersStart, payloadStart),
                                Arrays.copyOfRange(buf, payloadStart, trailerStart)));
                pos += totalLength;
            }
        } catch (RuntimeException e) {
            failed = true;
            buf = new byte[0];
            len = 0;
            throw e;
        }
        System.arraycopy(buf, pos, buf, 0, len - pos);
        len -= pos;
        return messages;
    }

    private int crc32(int offset, int length) {
        CRC32 crc = new CRC32();
        crc.update(buf, offset, length);
        return (int) crc.getValue();
    }

    private Map<String, String> decodeHeaders(ByteBuffer bb, int start, int end) {
        Map<String, String> headers = new HashMap<>();
        int p = start;
        while (p < end) {
            int nameLength = buf[p++] & 0xFF;
            String name = new String(buf, p, nameLength, StandardCharsets.UTF_8);
            p += nameLength;
            int type = buf[p++];
            switch (type) {
                case 0, 1 -> {} // boolean true / false: no value bytes
                case 2 -> p += 1; // byte
                case 3 -> p += 2; // short
                case 4 -> p += 4; // int
                case 5, 8 -> p += 8; // long, timestamp
                case 9 -> p += 16; // uuid
                case 6, 7 -> { // byte array, string
                    int valueLength = bb.getShort(p) & 0xFFFF;
                    p += 2;
                    if (type == 7) {
                        headers.put(name, new String(buf, p, valueLength, StandardCharsets.UTF_8));
                    }
                    p += valueLength;
                }
                default ->
                        throw new IllegalStateException("Unknown event-stream header type " + type);
            }
        }
        if (p != end) {
            throw new IllegalStateException("Event-stream headers overran their declared length");
        }
        return headers;
    }
}
