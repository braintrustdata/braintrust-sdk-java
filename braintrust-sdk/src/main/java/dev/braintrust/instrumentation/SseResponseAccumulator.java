package dev.braintrust.instrumentation;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import javax.annotation.concurrent.NotThreadSafe;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;

/** Reconstructs a full OpenAI-style chat completion response from SSE chunks. */
@Slf4j
@NotThreadSafe
public final class SseResponseAccumulator {
    private final ObjectMapper jsonMapper;
    private final ObjectNode responseRoot;
    // Accumulated choices keyed by their "index" so multi-choice (n>1) streams merge correctly.
    private final Map<Integer, ObjectNode> choicesByIndex = new LinkedHashMap<>();
    // Scalar identifiers that a stream sends once per entity (never as fragments). Merging these
    // last-write-wins rather than concatenating guards against a provider re-sending one across
    // deltas and corrupting it (e.g. an "id" becoming "call_Xcall_X"). Streamed text fields
    // (content, reasoning_content, tool-call arguments) are deliberately absent so they accumulate.
    private static final Set<String> NEVER_CONCAT =
            Set.of("id", "type", "role", "name", "index", "finish_reason", "model", "object");

    public SseResponseAccumulator(ObjectMapper jsonMapper) {
        this.jsonMapper = jsonMapper;
        this.responseRoot = jsonMapper.createObjectNode();
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

        var fields = chunk.fields();
        while (fields.hasNext()) {
            var entry = fields.next();
            String name = entry.getKey();
            JsonNode value = entry.getValue();
            if ("choices".equals(name)) {
                if (value.isArray()) {
                    value.forEach(this::mergeChoice);
                }
            } else if (!value.isNull()) {
                responseRoot.set(name, value);
            }
        }
    }

    /**
     * Serialize the reconstructed response, assembling accumulated choices in index order. Safe to
     * call once the stream is complete.
     */
    @SneakyThrows(JsonProcessingException.class)
    public String build() {
        var choicesArray = jsonMapper.createArrayNode();
        // Emit in index order — choicesByIndex is insertion-ordered, so an out-of-order (n>1)
        // stream could otherwise serialize choices out of index order.
        choicesByIndex.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .forEach(entry -> choicesArray.add(entry.getValue()));
        responseRoot.set("choices", choicesArray);
        return jsonMapper.writeValueAsString(responseRoot);
    }

    private void mergeChoice(JsonNode choiceChunk) {
        if (!choiceChunk.isObject()) return;
        int index = choiceChunk.has("index") ? choiceChunk.get("index").asInt() : 0;
        ObjectNode choice =
                choicesByIndex.computeIfAbsent(
                        index,
                        i -> {
                            var node = jsonMapper.createObjectNode();
                            node.put("index", i);
                            return node;
                        });
        // Streaming nests the message under "delta"; the reconstructed non-streaming shape the UI
        // expects uses "message". Rename, then deepMerge the whole choice so every field merges
        // with the right semantics — including logprobs, whose per-token entries stream one-per-
        // chunk and must accumulate rather than last-write-win. finish_reason stays last-write-wins
        // (it's in NEVER_CONCAT and arrives once).
        ObjectNode normalized = choiceChunk.deepCopy();
        if (normalized.has("delta")) {
            normalized.set("message", normalized.remove("delta"));
        }
        deepMerge(choice, normalized);
    }

    /**
     * Recursively merges {@code source} into {@code target}. Textual leaves are concatenated (so
     * streamed content / reasoning / tool-call arguments accumulate), except {@link #NEVER_CONCAT}
     * scalar identifiers which are last-write-wins. Nested objects are merged key-by-key, and
     * arrays whose elements carry an {@code index} (e.g. {@code tool_calls}) are merged by that
     * index. Other scalars are last-write-wins.
     */
    private static void deepMerge(ObjectNode target, JsonNode source) {
        if (!source.isObject()) return;
        var fields = source.fields();
        while (fields.hasNext()) {
            var entry = fields.next();
            String name = entry.getKey();
            JsonNode value = entry.getValue();
            JsonNode existing = target.get(name);
            if (value.isTextual()
                    && existing != null
                    && existing.isTextual()
                    && !NEVER_CONCAT.contains(name)) {
                target.put(name, existing.asText() + value.asText());
            } else if (value.isObject()) {
                if (existing != null && existing.isObject()) {
                    deepMerge((ObjectNode) existing, value);
                } else {
                    target.set(name, value.deepCopy());
                }
            } else if (value.isArray()) {
                mergeArray(target, name, value);
            } else if (!value.isNull()) {
                target.set(name, value);
            }
        }
    }

    private static void mergeArray(ObjectNode target, String name, JsonNode sourceArray) {
        if (!(target.get(name) instanceof ArrayNode targetArray)) {
            target.set(name, sourceArray.deepCopy());
            return;
        }
        for (JsonNode element : sourceArray) {
            ObjectNode match =
                    element.isObject() && element.has("index")
                            ? findByIndex(targetArray, element.get("index").asInt())
                            : null;
            if (match != null) {
                deepMerge(match, element);
            } else {
                targetArray.add(element.deepCopy());
            }
        }
    }

    private static ObjectNode findByIndex(ArrayNode array, int index) {
        for (JsonNode candidate : array) {
            if (candidate.isObject()
                    && candidate.has("index")
                    && candidate.get("index").asInt() == index) {
                return (ObjectNode) candidate;
            }
        }
        return null;
    }
}
