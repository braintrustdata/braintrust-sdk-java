package dev.braintrust.instrumentation;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.LinkedHashMap;
import java.util.Map;
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

    /**
     * Whether an SSE {@code data:} payload carries generated model output, as opposed to stream
     * lifecycle metadata.
     *
     * <p>Callers use this to time first-token latency. A Responses stream opens with {@code
     * response.created} and {@code response.in_progress} before the model has produced anything, so
     * timing the first payload of any kind measures time-to-response-metadata instead — badly
     * understated for a reasoning model, where generation can begin seconds after the stream opens.
     * A chat-completions stream similarly opens with a chunk whose delta carries only the assistant
     * role and an empty content string.
     *
     * <p>Errs toward {@code false}: callers are expected to keep a first-payload fallback so a
     * shape this does not recognize degrades to a slightly early measurement rather than none.
     */
    public static boolean carriesGeneratedOutput(ObjectMapper jsonMapper, String jsonChunk) {
        if (jsonChunk == null) {
            return false;
        }
        String data = jsonChunk.strip();
        if (data.isEmpty() || "[DONE]".equals(data)) {
            return false;
        }
        JsonNode chunk;
        try {
            chunk = jsonMapper.readTree(data);
        } catch (JsonProcessingException e) {
            return false;
        }
        if (chunk == null || !chunk.isObject()) {
            return false;
        }

        JsonNode type = chunk.get("type");
        if (type != null && type.isTextual()) {
            // Responses API. Deltas carry generated text, tool arguments, or reasoning; the item
            // and content-part ".added" events mark the moment an output item starts being
            // produced. Everything else on the stream is lifecycle or terminal bookkeeping.
            String eventType = type.asText();
            return eventType.endsWith(".delta") || eventType.endsWith(".added");
        }

        // Chat completions. Require a field that actually holds generated content: the opening
        // chunk's delta is {"role":"assistant","content":""}, which is not yet a token.
        for (JsonNode choice : chunk.path("choices")) {
            JsonNode delta = choice.path("delta");
            JsonNode content = delta.get("content");
            if (content != null && content.isTextual() && !content.asText().isEmpty()) {
                return true;
            }
            if (delta.hasNonNull("tool_calls")
                    || delta.hasNonNull("function_call")
                    || delta.hasNonNull("refusal")
                    || delta.hasNonNull("reasoning_content")
                    || delta.hasNonNull("reasoning")) {
                return true;
            }
        }
        return false;
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
