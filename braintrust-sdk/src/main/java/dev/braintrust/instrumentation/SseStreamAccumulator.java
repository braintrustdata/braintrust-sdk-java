package dev.braintrust.instrumentation;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import javax.annotation.Nullable;
import javax.annotation.concurrent.NotThreadSafe;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;

/**
 * Reconstructs a full response body from an OpenAI SSE stream, whichever of the two wire shapes the
 * stream uses:
 *
 * <ul>
 *   <li><b>Chat Completions</b> ({@code /v1/chat/completions}) — {@code chat.completion.chunk}
 *       objects whose fragments must be concatenated. Delegated to {@link SseResponseAccumulator}.
 *   <li><b>Responses</b> ({@code /v1/responses}) — {@code response.*} events. Handled here.
 * </ul>
 *
 * <p>The shape is detected from the events themselves rather than from the request, so a caller
 * that sees only the response stream (e.g. a wrapped HTTP client) can forward every {@code data:}
 * payload without knowing which endpoint was called. Use this instead of {@link
 * SseResponseAccumulator} directly wherever both endpoints are reachable — feeding Responses events
 * to the chat-completions accumulator yields a body with no {@code choices}, no {@code usage} and
 * no {@code output}, which tags an empty span output and drops all token metrics.
 */
@Slf4j
@NotThreadSafe
public final class SseStreamAccumulator {
    private static final String RESPONSES_EVENT_PREFIX = "response.";

    /** SSE {@code event:} name used for an in-band failure frame. */
    private static final String FAILURE_EVENT_NAME = "error";

    private static final Set<String> FAILURE_EVENT_TYPES =
            Set.of("error", "response.failed", "response.error");

    private final ObjectMapper jsonMapper;
    private final SseResponseAccumulator chatCompletions;
    // Latest complete snapshot of the response object, from the most recent event that carried one.
    @Nullable private ObjectNode responsesSnapshot;
    // Output items seen individually, keyed by their "output_index" (see #mergeResponsesEvent).
    private final Map<Integer, ObjectNode> responsesItemsByIndex = new LinkedHashMap<>();
    private boolean sawResponsesEvent;

    public SseStreamAccumulator(ObjectMapper jsonMapper) {
        this.jsonMapper = jsonMapper;
        this.chatCompletions = new SseResponseAccumulator(jsonMapper);
    }

    /**
     * Merge one SSE {@code data:} payload into the reconstructed response. Blank payloads, the
     * {@code [DONE]} sentinel, non-JSON, and non-object chunks are ignored, so callers can forward
     * every event without pre-filtering.
     */
    public void merge(String jsonChunk) {
        if (jsonChunk == null) return;
        String data = jsonChunk.strip();
        if (data.isEmpty() || "[DONE]".equals(data)) return;

        JsonNode chunk;
        try {
            chunk = jsonMapper.readTree(data);
        } catch (JsonProcessingException e) {
            log.debug("Failed to parse SSE chunk: {}", data, e);
            return;
        }
        if (chunk == null || !chunk.isObject()) return;

        // Once a Responses event has been seen the stream is a Responses stream; keep routing
        // everything there so trailing non-"response.*" events (e.g. a terminal `error` event)
        // can't leak into the chat-completions reconstruction.
        if (sawResponsesEvent || isResponsesEvent(chunk)) {
            sawResponsesEvent = true;
            mergeResponsesEvent(chunk);
        } else {
            chatCompletions.merge(data);
        }
    }

    /**
     * Serialize the reconstructed response. Safe to call once the stream is complete; leaves this
     * accumulator's state untouched, so a partial build mid-stream is also valid.
     */
    @SneakyThrows(JsonProcessingException.class)
    public String build() {
        if (!sawResponsesEvent) {
            return chatCompletions.build();
        }
        ObjectNode root =
                responsesSnapshot == null
                        ? jsonMapper.createObjectNode()
                        : responsesSnapshot.deepCopy();
        JsonNode output = root.get("output");
        if ((output == null || output.isEmpty()) && !responsesItemsByIndex.isEmpty()) {
            var items = jsonMapper.createArrayNode();
            responsesItemsByIndex.entrySet().stream()
                    .sorted(Map.Entry.comparingByKey())
                    .forEach(entry -> items.add(entry.getValue()));
            root.set("output", items);
        }
        return jsonMapper.writeValueAsString(root);
    }

    /** What an SSE {@code data:} payload turned out to be, for first-token timing. */
    public enum PayloadKind {
        /** Carries generated model output: a token, tool arguments, reasoning, or a new item. */
        OUTPUT,
        /** A shape this class understands, but one that carries no generated output yet. */
        NO_OUTPUT,
        /** A shape this class does not understand, so nothing can be concluded about it. */
        UNRECOGNIZED
    }

    /**
     * Classify one SSE {@code data:} payload for the purpose of timing first-token latency.
     *
     * <p>Timing the first payload of <em>any</em> kind measures time-to-response-metadata rather
     * than time-to-first-token: a Responses stream opens with {@code response.created} and {@code
     * response.in_progress} before the model has produced anything, and for a reasoning model
     * generation can begin seconds later. A chat-completions stream likewise opens with a chunk
     * whose delta carries only the assistant role and an empty content string. Both are {@link
     * PayloadKind#NO_OUTPUT}.
     *
     * <p>The three-way result matters: a caller must be able to tell "recognized, but nothing
     * generated" from "shape I don't understand". Only the latter justifies falling back to the
     * first payload's timestamp — for the former, the honest answer is that there was no first
     * token, so no metric should be reported at all.
     */
    public static PayloadKind classify(ObjectMapper jsonMapper, @Nullable String jsonChunk) {
        if (jsonChunk == null) {
            return PayloadKind.UNRECOGNIZED;
        }
        String data = jsonChunk.strip();
        if (data.isEmpty() || "[DONE]".equals(data)) {
            return PayloadKind.UNRECOGNIZED;
        }
        JsonNode chunk;
        try {
            chunk = jsonMapper.readTree(data);
        } catch (JsonProcessingException e) {
            return PayloadKind.UNRECOGNIZED;
        }
        if (chunk == null || !chunk.isObject()) {
            return PayloadKind.UNRECOGNIZED;
        }

        JsonNode type = chunk.get("type");
        if (type != null && type.isTextual()) {
            // Responses API. Deltas carry generated text, tool arguments, or reasoning; the item
            // and content-part ".added" events mark the moment an output item starts being
            // produced. Everything else on the stream is lifecycle or terminal bookkeeping.
            String eventType = type.asText();
            return eventType.endsWith(".delta") || eventType.endsWith(".added")
                    ? PayloadKind.OUTPUT
                    : PayloadKind.NO_OUTPUT;
        }

        // Chat completions. Require a field that actually holds generated content: the opening
        // chunk's delta is {"role":"assistant","content":""}, which is not yet a token.
        JsonNode choices = chunk.get("choices");
        if (choices == null || !choices.isArray()) {
            return PayloadKind.UNRECOGNIZED;
        }
        for (JsonNode choice : choices) {
            JsonNode delta = choice.path("delta");
            JsonNode content = delta.get("content");
            if (content != null && content.isTextual() && !content.asText().isEmpty()) {
                return PayloadKind.OUTPUT;
            }
            if (delta.hasNonNull("tool_calls")
                    || delta.hasNonNull("function_call")
                    || delta.hasNonNull("refusal")
                    || delta.hasNonNull("reasoning_content")
                    || delta.hasNonNull("reasoning")) {
                return PayloadKind.OUTPUT;
            }
        }
        return PayloadKind.NO_OUTPUT;
    }

    /**
     * The failure an SSE payload reports, or {@code null} when the payload reports none.
     *
     * <p>A stream can fail <em>in band</em>, after the HTTP request has already succeeded: the
     * Responses API sends {@code response.failed} or a bare {@code error} event, and Chat
     * Completions sends a frame named {@code error}. In both cases the transport then closes
     * normally, so a caller that only treats transport exceptions as errors finalizes a failed
     * generation as a successful span. Callers should retain the first non-null result and set the
     * span status from it.
     *
     * <p>{@code response.incomplete} is deliberately <em>not</em> a failure: it reports a response
     * truncated by a token limit or content filter, which still carries usable output.
     *
     * @param eventName the SSE {@code event:} name, if the transport exposes one
     * @param data the SSE {@code data:} payload
     */
    @Nullable
    public static String streamFailure(
            ObjectMapper jsonMapper, @Nullable String eventName, @Nullable String data) {
        boolean namedError = FAILURE_EVENT_NAME.equals(eventName);
        String payload = data == null ? "" : data.strip();
        if (payload.isEmpty() || "[DONE]".equals(payload)) {
            return namedError ? "stream reported an error with no detail" : null;
        }

        JsonNode chunk = null;
        try {
            chunk = jsonMapper.readTree(payload);
        } catch (JsonProcessingException e) {
            // An unparseable payload is still a failure when the event says so.
            log.debug("Failed to parse SSE chunk: {}", payload, e);
        }
        if (chunk == null || !chunk.isObject()) {
            return namedError ? payload : null;
        }

        JsonNode type = chunk.get("type");
        String eventType = type != null && type.isTextual() ? type.asText() : "";
        boolean failed =
                namedError
                        || FAILURE_EVENT_TYPES.contains(eventType)
                        // A frame carrying an error and no event type at all: a provider error
                        // injected into a chat-completions stream.
                        || (eventType.isEmpty() && chunk.hasNonNull("error"));
        return failed ? failureMessage(chunk, payload) : null;
    }

    private static String failureMessage(JsonNode chunk, String rawPayload) {
        JsonNode error = chunk.get("error");
        if (error == null || error.isNull()) {
            error = chunk.path("response").get("error");
        }
        if (error != null && !error.isNull()) {
            JsonNode message = error.get("message");
            if (message != null && message.isTextual() && !message.asText().isEmpty()) {
                return message.asText();
            }
            return error.isValueNode() ? error.asText() : error.toString();
        }
        // The Responses `error` event carries its message at the top level, not nested.
        JsonNode message = chunk.get("message");
        if (message != null && message.isTextual() && !message.asText().isEmpty()) {
            return message.asText();
        }
        return rawPayload;
    }

    private static boolean isResponsesEvent(JsonNode chunk) {
        JsonNode type = chunk.get("type");
        return type != null && type.isTextual() && type.asText().startsWith(RESPONSES_EVENT_PREFIX);
    }

    /**
     * Merges one {@code response.*} event. Unlike chat-completions chunks, Responses events are not
     * fragments to be stitched: {@code response.created} / {@code .in_progress} / {@code
     * .completed} / {@code .incomplete} / {@code .failed} each carry a <em>complete</em> snapshot
     * of the response object, so the newest snapshot simply replaces the previous one and the
     * terminal event supplies the authoritative {@code output} and {@code usage}.
     *
     * <p>{@code response.output_item.added} / {@code .done} events are also recorded per {@code
     * output_index} so that a stream which closes without a terminal snapshot still reports the
     * items it produced. Text/argument delta events need no handling: whatever they build up is
     * repeated whole in the item's {@code .done} event.
     */
    private void mergeResponsesEvent(JsonNode event) {
        JsonNode snapshot = event.get("response");
        if (snapshot != null && snapshot.isObject()) {
            responsesSnapshot = snapshot.deepCopy();
            return;
        }
        JsonNode item = event.get("item");
        JsonNode outputIndex = event.get("output_index");
        if (item != null && item.isObject() && outputIndex != null && outputIndex.isNumber()) {
            responsesItemsByIndex.put(outputIndex.asInt(), item.deepCopy());
        }
    }
}
