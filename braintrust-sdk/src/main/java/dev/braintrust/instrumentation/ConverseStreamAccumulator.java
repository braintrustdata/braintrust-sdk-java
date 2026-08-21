package dev.braintrust.instrumentation;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.braintrust.json.BraintrustJsonMapper;
import java.util.LinkedHashMap;
import java.util.Map;
import javax.annotation.Nullable;
import javax.annotation.concurrent.NotThreadSafe;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;

/**
 * Reconstructs a non-streaming Bedrock {@code Converse} response body from the event-stream frames
 * of a {@code ConverseStream} call, so a streaming span can be tagged by the same {@code
 * PROVIDER_NAME_BEDROCK} code path as a synchronous one.
 *
 * <p>Content blocks are rebuilt per {@code contentBlockIndex} and each keeps its real Bedrock shape
 * — {@code text}, {@code toolUse}, {@code reasoningContent} — rather than everything collapsing to
 * text. That shape match is the point: {@link InstrumentationSemConv} normalizes the assembled
 * message with the same helper it uses for a synchronous response, so streaming and non-streaming
 * traces render identically.
 *
 * <p>Frames arrive as {@code (eventType, payload)} pairs; feed every frame via {@link #accept} and
 * call {@link #build} once the stream completes. Unrecognized event types and malformed payloads
 * are skipped, so callers can forward everything without pre-filtering.
 *
 * @see <a
 *     href="https://docs.aws.amazon.com/bedrock/latest/APIReference/API_runtime_ConverseStream.html">Bedrock
 *     ConverseStream API reference</a>
 */
@Slf4j
@NotThreadSafe
public final class ConverseStreamAccumulator {

    private final ObjectMapper jsonMapper;

    private String role = "assistant";
    @Nullable private String stopReason;
    @Nullable private ObjectNode usage;

    /** Assembled content blocks in Bedrock's response shape, keyed by {@code contentBlockIndex}. */
    private final Map<Integer, ObjectNode> blocksByIndex = new LinkedHashMap<>();

    /**
     * A {@code toolUse} block's {@code input} streams as concatenated JSON fragments; buffer them
     * per index and parse once at {@link #build} time.
     */
    private final Map<Integer, StringBuilder> toolInputByIndex = new LinkedHashMap<>();

    /**
     * Uses the SDK's shared mapper. Preferred by instrumentation modules, which do not carry
     * Jackson on their own compile classpath.
     */
    public ConverseStreamAccumulator() {
        this(BraintrustJsonMapper.get());
    }

    public ConverseStreamAccumulator(ObjectMapper jsonMapper) {
        this.jsonMapper = jsonMapper;
    }

    /**
     * Merge one event-stream frame.
     *
     * @param eventType the frame's {@code :event-type} header, e.g. {@code contentBlockDelta}
     * @param payload the frame's JSON payload
     */
    public void accept(@Nullable String eventType, @Nullable String payload) {
        if (eventType == null || payload == null || payload.isBlank()) {
            return;
        }
        JsonNode event;
        try {
            event = jsonMapper.readTree(payload);
        } catch (JsonProcessingException e) {
            log.debug("Failed to parse ConverseStream {} payload: {}", eventType, payload, e);
            return;
        }
        if (event == null || !event.isObject()) {
            return;
        }
        switch (eventType) {
            case "messageStart" -> {
                if (event.hasNonNull("role")) {
                    role = event.get("role").asText();
                }
            }
            case "contentBlockStart" -> mergeContentBlockStart(event);
            case "contentBlockDelta" -> mergeContentBlockDelta(event);
            case "messageStop" -> {
                if (event.hasNonNull("stopReason")) {
                    stopReason = event.get("stopReason").asText();
                }
            }
            case "metadata" -> {
                // Pass usage through whole: whatever Bedrock reports (including cache counters)
                // reaches the tagger, rather than a hand-picked subset.
                if (event.has("usage") && event.get("usage").isObject()) {
                    usage = (ObjectNode) event.get("usage").deepCopy();
                }
            }
            default -> {
                // contentBlockStop, and any event type added by a later API version: nothing to
                // accumulate. Block completion is implied by the stream ending.
            }
        }
    }

    /**
     * {@code contentBlockStart} carries the identity of a non-text block — for {@code toolUse}, its
     * {@code toolUseId} and {@code name}, which no later delta repeats.
     */
    private void mergeContentBlockStart(JsonNode event) {
        JsonNode start = event.get("start");
        if (start == null || !start.isObject()) {
            return;
        }
        int index = blockIndex(event);
        JsonNode toolUse = start.get("toolUse");
        if (toolUse != null && toolUse.isObject()) {
            ObjectNode block = blockAt(index);
            ObjectNode target = childObject(block, "toolUse");
            copyIfPresent(toolUse, target, "toolUseId");
            copyIfPresent(toolUse, target, "name");
        }
    }

    /**
     * {@code contentBlockDelta} carries one fragment of a block. The fragment's own key identifies
     * which kind of block it belongs to: {@code text} for plain output, {@code toolUse.input} for
     * tool arguments, {@code reasoningContent} for extended-thinking output.
     */
    private void mergeContentBlockDelta(JsonNode event) {
        JsonNode delta = event.get("delta");
        if (delta == null || !delta.isObject()) {
            return;
        }
        int index = blockIndex(event);

        if (delta.hasNonNull("text")) {
            appendText(blockAt(index), "text", delta.get("text").asText());
            return;
        }

        JsonNode toolUse = delta.get("toolUse");
        if (toolUse != null && toolUse.isObject() && toolUse.hasNonNull("input")) {
            // Ensure the block exists even if contentBlockStart was missed, so the arguments are
            // not dropped for want of a toolUseId.
            childObject(blockAt(index), "toolUse");
            toolInputByIndex
                    .computeIfAbsent(index, i -> new StringBuilder())
                    .append(toolUse.get("input").asText());
            return;
        }

        JsonNode reasoning = delta.get("reasoningContent");
        if (reasoning != null && reasoning.isObject()) {
            mergeReasoningDelta(blockAt(index), reasoning);
        }
    }

    /**
     * Reasoning deltas arrive flat ({@code delta.reasoningContent.text}) but a synchronous response
     * nests the same data one level deeper, under {@code reasoningContent.reasoningText}. Rebuild
     * the nested shape so both paths normalize identically. {@code redactedContent} is a sibling of
     * {@code reasoningText}, not part of it.
     */
    private void mergeReasoningDelta(ObjectNode block, JsonNode reasoning) {
        ObjectNode reasoningContent = childObject(block, "reasoningContent");
        if (reasoning.hasNonNull("redactedContent")) {
            appendText(
                    reasoningContent, "redactedContent", reasoning.get("redactedContent").asText());
        }
        if (reasoning.hasNonNull("text") || reasoning.hasNonNull("signature")) {
            ObjectNode reasoningText = childObject(reasoningContent, "reasoningText");
            if (reasoning.hasNonNull("text")) {
                appendText(reasoningText, "text", reasoning.get("text").asText());
            }
            if (reasoning.hasNonNull("signature")) {
                appendText(reasoningText, "signature", reasoning.get("signature").asText());
            }
        }
    }

    /**
     * Serialize the reconstructed response in {@code Converse} response shape. Leaves this
     * accumulator's state untouched, so a partial build mid-stream is also valid.
     */
    @SneakyThrows(JsonProcessingException.class)
    public String build() {
        ArrayNode content = jsonMapper.createArrayNode();
        blocksByIndex.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .forEach(entry -> content.add(finalizeBlock(entry.getKey(), entry.getValue())));

        ObjectNode message = jsonMapper.createObjectNode();
        message.put("role", role);
        message.set("content", content);

        ObjectNode output = jsonMapper.createObjectNode();
        output.set("message", message);

        ObjectNode root = jsonMapper.createObjectNode();
        root.set("output", output);
        if (stopReason != null) {
            root.put("stopReason", stopReason);
        }
        if (usage != null) {
            root.set("usage", usage);
        }
        return jsonMapper.writeValueAsString(root);
    }

    /** Attaches a tool block's buffered argument fragments as parsed JSON. */
    private ObjectNode finalizeBlock(int index, ObjectNode block) {
        StringBuilder toolInput = toolInputByIndex.get(index);
        if (toolInput == null || !block.has("toolUse")) {
            return block;
        }
        ObjectNode toolUse = (ObjectNode) block.get("toolUse");
        String json = toolInput.toString();
        if (json.isEmpty()) {
            toolUse.set("input", jsonMapper.createObjectNode());
            return block;
        }
        try {
            toolUse.set("input", jsonMapper.readTree(json));
        } catch (JsonProcessingException e) {
            // A truncated or otherwise unparseable fragment stream is still worth surfacing —
            // keep it as a string rather than dropping the arguments entirely.
            log.debug("Failed to parse accumulated toolUse input: {}", json, e);
            toolUse.put("input", json);
        }
        return block;
    }

    private static int blockIndex(JsonNode event) {
        return event.hasNonNull("contentBlockIndex") ? event.get("contentBlockIndex").asInt() : 0;
    }

    private ObjectNode blockAt(int index) {
        return blocksByIndex.computeIfAbsent(index, i -> jsonMapper.createObjectNode());
    }

    /** Returns {@code parent[field]} as an object, creating it when absent. */
    private ObjectNode childObject(ObjectNode parent, String field) {
        JsonNode existing = parent.get(field);
        if (existing != null && existing.isObject()) {
            return (ObjectNode) existing;
        }
        ObjectNode created = jsonMapper.createObjectNode();
        parent.set(field, created);
        return created;
    }

    private static void appendText(ObjectNode target, String field, String value) {
        String existing = target.hasNonNull(field) ? target.get(field).asText() : "";
        target.put(field, existing + value);
    }

    private static void copyIfPresent(JsonNode source, ObjectNode target, String field) {
        if (source.hasNonNull(field)) {
            target.set(field, source.get(field).deepCopy());
        }
    }
}
