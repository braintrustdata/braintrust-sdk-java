package dev.braintrust.instrumentation;

import static dev.braintrust.json.BraintrustJsonMapper.toJson;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.braintrust.json.BraintrustJsonMapper;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Context;
import java.time.Instant;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class InstrumentationSemConv {
    public static final String PROVIDER_NAME_OPENAI = "openai";
    public static final String PROVIDER_NAME_ANTHROPIC = "anthropic";
    public static final String PROVIDER_NAME_BEDROCK = "bedrock";
    public static final String PROVIDER_NAME_OTHER = "generic-ai-provider";
    public static final String UNSET_LLM_SPAN_NAME = "llm";

    // -------------------------------------------------------------------------
    // Public API — provider-dispatching entry points
    // -------------------------------------------------------------------------

    @SneakyThrows
    public static void tagLLMSpanRequest(
            Span span,
            @Nonnull String providerName,
            @Nonnull String baseUrl,
            @Nonnull List<String> pathSegments,
            @Nonnull String method,
            @Nullable String requestBody) {
        tagLLMSpanRequest(span, providerName, baseUrl, pathSegments, method, requestBody, null);
    }

    /**
     * Tag a span with LLM request metadata.
     *
     * @param modelId explicit model identifier — used by providers (e.g. Bedrock) where the model
     *     is not present in the request body. When {@code null} the model is extracted from the
     *     request body if possible.
     */
    @SneakyThrows
    public static void tagLLMSpanRequest(
            Span span,
            @Nonnull String providerName,
            @Nonnull String baseUrl,
            @Nonnull List<String> pathSegments,
            @Nonnull String method,
            @Nullable String requestBody,
            @Nullable String modelId) {
        switch (providerName) {
            case PROVIDER_NAME_OPENAI ->
                    tagOpenAIRequest(
                            span, providerName, baseUrl, pathSegments, method, requestBody);
            case PROVIDER_NAME_ANTHROPIC ->
                    tagAnthropicRequest(
                            span, providerName, baseUrl, pathSegments, method, requestBody);
            case PROVIDER_NAME_BEDROCK ->
                    tagBedrockRequest(
                            span,
                            providerName,
                            baseUrl,
                            pathSegments,
                            method,
                            requestBody,
                            modelId);
            default ->
                    tagOpenAIRequest(
                            span, providerName, baseUrl, pathSegments, method, requestBody);
        }
    }

    public static void tagLLMSpanResponse(
            @Nonnull Tracer tracer,
            Span span,
            @Nonnull String providerName,
            @Nonnull String responseBody) {
        tagLLMSpanResponse(tracer, span, providerName, responseBody, null);
    }

    /**
     * Tag a span with the LLM response and emit child spans for any server-side tool calls the
     * provider reported inline. The response body is parsed once here and the parsed tree is reused
     * for both, so callers should route all response tagging through this method rather than
     * parsing themselves.
     */
    @SneakyThrows
    public static void tagLLMSpanResponse(
            @Nonnull Tracer tracer,
            Span span,
            @Nonnull String providerName,
            @Nonnull String responseBody,
            @Nullable Long timeToFirstTokenNanoseconds) {
        JsonNode responseJson = BraintrustJsonMapper.get().readTree(responseBody);
        switch (providerName) {
            case PROVIDER_NAME_OPENAI ->
                    tagOpenAIResponse(span, responseJson, timeToFirstTokenNanoseconds);
            case PROVIDER_NAME_ANTHROPIC ->
                    tagAnthropicResponse(
                            span, responseBody, responseJson, timeToFirstTokenNanoseconds);
            case PROVIDER_NAME_BEDROCK ->
                    tagBedrockResponse(span, responseJson, timeToFirstTokenNanoseconds);
            default -> tagOpenAIResponse(span, responseJson, timeToFirstTokenNanoseconds);
        }
        addServerSideChildSpans(tracer, span, providerName, responseJson);
    }

    public static void tagLLMSpanResponse(Span span, @Nonnull Throwable responseError) {
        span.setStatus(StatusCode.ERROR, responseError.getMessage());
        span.recordException(responseError);
    }

    /**
     * Emit child {@code type:"tool"} spans for built-in tool calls the vendor executed <em>server
     * side</em> (web search, file search, code interpreter, image generation, remote MCP) that the
     * provider reports inline with the LLM response. These are otherwise invisible on the trace —
     * unlike client-side tool calls (a plain {@code function_call}/{@code computer_call}), which
     * the caller executes and which get instrumented where they run — so they are surfaced as
     * children of the LLM span.
     *
     * <p>Providers don't report per-tool timing, so each child will start some time within the llm
     * span with a duration of zero
     *
     * <p>Safe to call for any response — non-matching payloads simply yield no spans.
     *
     * <p>Package-private: callers reach this via {@link #tagLLMSpanResponse}, which parses the
     * response body once and passes the tree here. Kept accessible for same-package unit tests.
     *
     * @param tracer used to create the child spans; cannot be derived from {@code llmSpan}
     * @param llmSpan the parent LLM span the children are nested under
     */
    static void addServerSideChildSpans(
            @Nonnull Tracer tracer,
            @Nonnull Span llmSpan,
            @Nonnull String providerName,
            @Nonnull JsonNode responseJson) {
        Context parentContext = Context.current().with(llmSpan);
        switch (providerName) {
            case PROVIDER_NAME_OPENAI ->
                    addOpenAIServerSideChildSpans(tracer, parentContext, responseJson);
            case PROVIDER_NAME_ANTHROPIC ->
                    addAnthropicServerSideChildSpans(tracer, parentContext, responseJson);
            default -> {
                // Unknown provider: no server-side tool schema to parse, so emit nothing rather
                // than guessing at a response shape.
            }
        }
    }

    // -------------------------------------------------------------------------
    // OpenAI provider implementation
    // -------------------------------------------------------------------------

    @SneakyThrows
    private static void tagOpenAIRequest(
            Span span,
            String providerName,
            String baseUrl,
            List<String> pathSegments,
            String method,
            @Nullable String requestBody) {
        span.updateName(getSpanName(providerName, pathSegments));
        span.setAttribute("braintrust.span_attributes", toJson(Map.of("type", "llm")));

        Map<String, Object> metadata = new HashMap<>();
        metadata.put("provider", providerName);
        metadata.put("request_path", String.join("/", pathSegments));
        metadata.put("request_base_uri", baseUrl);
        metadata.put("request_method", method);

        if (requestBody != null) {
            JsonNode requestJson = BraintrustJsonMapper.get().readTree(requestBody);
            if (requestJson.has("model")) {
                metadata.put("model", requestJson.get("model").asText());
            }
            putGenerationParameters(metadata, requestJson);
            // Chat completions API uses "messages"; Responses API uses "input"
            if (requestJson.has("messages")) {
                span.setAttribute("braintrust.input_json", toJson(requestJson.get("messages")));
            } else if (requestJson.has("input") && requestJson.get("input").isArray()) {
                span.setAttribute("braintrust.input_json", toJson(requestJson.get("input")));
            }
        }

        span.setAttribute("braintrust.metadata", toJson(metadata));
    }

    @SneakyThrows
    private static void tagOpenAIResponse(
            Span span, JsonNode responseJson, @Nullable Long timeToFirstTokenNanoseconds) {
        // Output — chat completions API uses "choices"; Responses API uses "output"; audio
        // transcriptions and translations return a text-keyed object.
        if (responseJson.has("choices")) {
            span.setAttribute("braintrust.output_json", toJson(responseJson.get("choices")));
        } else if (responseJson.has("output")) {
            span.setAttribute("braintrust.output_json", toJson(responseJson.get("output")));
        } else if (responseJson.has("text")) {
            span.setAttribute("braintrust.output_json", toJson(responseJson));
        }

        Map<String, Object> metrics = new HashMap<>();
        if (timeToFirstTokenNanoseconds != null) {
            metrics.put("time_to_first_token", timeToFirstTokenNanoseconds / 1_000_000_000.0);
        }

        if (responseJson.has("usage")) {
            JsonNode usage = responseJson.get("usage");
            // Chat completions API field names
            if (usage.has("prompt_tokens"))
                metrics.put("prompt_tokens", usage.get("prompt_tokens"));
            if (usage.has("completion_tokens"))
                metrics.put("completion_tokens", usage.get("completion_tokens"));
            if (usage.has("total_tokens")) metrics.put("tokens", usage.get("total_tokens"));
            if (usage.has("prompt_tokens_details")) {
                JsonNode details = usage.get("prompt_tokens_details");
                if (details.has("cached_tokens")) {
                    metrics.put("prompt_cached_tokens", details.get("cached_tokens"));
                }
            }
            // Reasoning tokens (Chat Completions API)
            if (usage.has("completion_tokens_details")) {
                JsonNode details = usage.get("completion_tokens_details");
                if (details.has("reasoning_tokens")) {
                    metrics.put("completion_reasoning_tokens", details.get("reasoning_tokens"));
                }
            }
            // Responses API field names
            if (usage.has("input_tokens")) metrics.put("prompt_tokens", usage.get("input_tokens"));
            if (usage.has("output_tokens"))
                metrics.put("completion_tokens", usage.get("output_tokens"));
            if (usage.has("input_tokens") && usage.has("output_tokens")) {
                metrics.put(
                        "tokens",
                        usage.get("input_tokens").asLong() + usage.get("output_tokens").asLong());
            }
            if (usage.has("input_tokens_details")) {
                JsonNode details = usage.get("input_tokens_details");
                if (details.has("cached_tokens")) {
                    metrics.put("prompt_cached_tokens", details.get("cached_tokens"));
                }
            }
            // Reasoning tokens (Responses API)
            if (usage.has("output_tokens_details")) {
                JsonNode details = usage.get("output_tokens_details");
                if (details.has("reasoning_tokens")) {
                    metrics.put("completion_reasoning_tokens", details.get("reasoning_tokens"));
                }
            }
        }

        if (!metrics.isEmpty()) {
            span.setAttribute("braintrust.metrics", toJson(metrics));
        }
    }

    private static final String TYPE_TOOL_JSON = "{\"type\":\"tool\"}";

    /**
     * OpenAI Responses {@code output} item types the vendor executes <em>server side</em>, mapped
     * to the item fields that make up the tool span's input. Client-side calls ({@code
     * function_call}, {@code computer_call}) are intentionally excluded — they run in the caller
     * and are instrumented there. {@code image_generation_call} is also excluded: its result
     * carries a large base64 image blob that would flow unredacted into the span output, and it has
     * no useful input fields to surface.
     */
    private static final Map<String, List<String>> OPENAI_SERVER_SIDE_ITEM_INPUT_KEYS =
            Map.of(
                    "web_search_call", List.of("action"),
                    "file_search_call", List.of("queries"),
                    "code_interpreter_call", List.of("code", "container_id"),
                    "mcp_call", List.of("arguments"));

    private static void addOpenAIServerSideChildSpans(
            Tracer tracer, Context parentContext, JsonNode responseJson) {
        try {
            JsonNode output = responseJson.get("output");
            if (output == null || !output.isArray()) {
                return;
            }
            for (JsonNode item : output) {
                if (!item.isObject()) {
                    continue;
                }
                String type = item.path("type").asText(null);
                if (type == null || !OPENAI_SERVER_SIDE_ITEM_INPUT_KEYS.containsKey(type)) {
                    continue;
                }
                emitOpenAIServerSideToolSpan(tracer, parentContext, item, type);
            }
        } catch (Exception e) {
            log.debug("Could not emit OpenAI server-side child spans", e);
        }
    }

    private static void emitOpenAIServerSideToolSpan(
            Tracer tracer, Context parentContext, JsonNode item, String type) {
        // Zero-duration marker: stamp start and end at the same instant.
        Instant now = Instant.now();
        Span span =
                tracer.spanBuilder(openAIToolSpanName(item, type))
                        .setParent(parentContext)
                        .setStartTimestamp(now)
                        .startSpan();
        try {
            span.setAttribute("braintrust.span_attributes", TYPE_TOOL_JSON);

            JsonNode input = openAIToolSpanInput(item, type);
            if (input != null && !input.isNull()) {
                span.setAttribute("braintrust.input_json", toJson(input));
            }
            String metadata = openAIToolSpanMetadata(item, type);
            if (metadata != null) {
                span.setAttribute("braintrust.metadata", metadata);
            }

            // When the tool call errored, record the error and skip output.
            JsonNode error = openAIToolSpanError(item);
            if (error != null) {
                span.setStatus(
                        StatusCode.ERROR, error.isValueNode() ? error.asText() : error.toString());
                return;
            }
            JsonNode output = openAIToolSpanOutput(item, type);
            if (output != null) {
                span.setAttribute("braintrust.output_json", toJson(output));
            }
        } catch (Exception e) {
            log.debug("Could not tag OpenAI server-side tool span", e);
        } finally {
            span.end(now);
        }
    }

    private static String openAIToolSpanName(JsonNode item, String type) {
        String serverLabel = nonEmptyText(item, "server_label");
        String name = nonEmptyText(item, "name");
        if (serverLabel != null && name != null) {
            return serverLabel + "." + name;
        }
        if (name != null) {
            return name;
        }
        return type;
    }

    private static JsonNode openAIToolSpanInput(JsonNode item, String type) {
        List<String> inputKeys = OPENAI_SERVER_SIDE_ITEM_INPUT_KEYS.get(type);
        if (inputKeys.isEmpty()) {
            return null;
        }
        ObjectNode inputData = BraintrustJsonMapper.get().createObjectNode();
        for (String key : inputKeys) {
            JsonNode value = item.get(key);
            if (value != null && !value.isNull()) {
                inputData.set(key, maybeParseJsonString(value));
            }
        }
        if (inputData.isEmpty()) {
            return null;
        }
        // MCP calls carry a single `arguments` blob — unwrap it to the bare value.
        if (inputKeys.size() == 1 && "arguments".equals(inputKeys.get(0))) {
            return inputData.get("arguments");
        }
        return inputData;
    }

    private static JsonNode openAIToolSpanOutput(JsonNode item, String type) {
        Set<String> excluded =
                new HashSet<>(Set.of("id", "type", "name", "call_id", "server_label", "error"));
        excluded.addAll(OPENAI_SERVER_SIDE_ITEM_INPUT_KEYS.get(type));

        ObjectNode output = BraintrustJsonMapper.get().createObjectNode();
        var fields = item.fields();
        while (fields.hasNext()) {
            var entry = fields.next();
            String key = entry.getKey();
            JsonNode value = entry.getValue();
            if (excluded.contains(key) || value == null || value.isNull()) {
                continue;
            }
            if ("output".equals(key)) {
                output.set(key, maybeParseJsonString(value));
            } else {
                output.set(key, value);
            }
        }
        return output.isEmpty() ? null : output;
    }

    private static JsonNode openAIToolSpanError(JsonNode item) {
        JsonNode error = item.get("error");
        if (error == null || error.isNull()) {
            return null;
        }
        return maybeParseJsonString(error);
    }

    private static String openAIToolSpanMetadata(JsonNode item, String type) {
        ObjectNode md = BraintrustJsonMapper.get().createObjectNode();
        md.put("tool_type", type);
        putIfPresent(md, "tool_id", item.get("id"));
        putIfPresent(md, "call_id", item.get("call_id"));
        putIfPresent(md, "status", item.get("status"));
        putIfPresent(md, "server_label", item.get("server_label"));
        return md.isEmpty() ? null : toJson(md);
    }

    // -------------------------------------------------------------------------
    // Anthropic provider implementation
    // -------------------------------------------------------------------------

    @SneakyThrows
    private static void tagAnthropicRequest(
            Span span,
            String providerName,
            String baseUrl,
            List<String> pathSegments,
            String method,
            @Nullable String requestBody) {
        span.updateName(getSpanName(providerName, pathSegments));
        span.setAttribute("braintrust.span_attributes", toJson(Map.of("type", "llm")));

        Map<String, Object> metadata = new HashMap<>();
        metadata.put("provider", providerName);
        metadata.put("request_path", String.join("/", pathSegments));
        metadata.put("request_base_uri", baseUrl);
        metadata.put("request_method", method);

        if (requestBody != null) {
            JsonNode requestJson = BraintrustJsonMapper.get().readTree(requestBody);
            if (requestJson.has("stream")
                    && requestJson.get("stream").isBoolean()
                    && requestJson.get("stream").asBoolean()) {
                span.updateName(getStreamingSpanName(providerName, pathSegments));
            }
            if (requestJson.has("model")) {
                metadata.put("model", requestJson.get("model").asText());
            }
            putGenerationParameters(metadata, requestJson);
            // Build input array: messages + system (as a synthetic system-role entry)
            if (requestJson.has("messages")) {
                ArrayNode inputArray = BraintrustJsonMapper.get().createArrayNode();
                // Append messages, simplifying single-text content blocks to plain strings
                for (JsonNode msg : requestJson.get("messages")) {
                    inputArray.add(simplifyAnthropicMessage(msg));
                }
                // Append system prompt as a {role:"system", content:...} entry if present
                JsonNode system = requestJson.get("system");
                if (hasAnthropicSystemPrompt(system)) {
                    var systemNode = BraintrustJsonMapper.get().createObjectNode();
                    systemNode.put("role", "system");
                    systemNode.set("content", system);
                    inputArray.add(systemNode);
                }
                span.setAttribute("braintrust.input_json", toJson(inputArray));
            }
        }

        span.setAttribute("braintrust.metadata", toJson(metadata));
    }

    /**
     * Whether an Anthropic request's {@code system} field actually carries a prompt.
     *
     * <p>{@code system} takes two shapes: a plain string, or an array of content blocks (the form
     * required to attach {@code cache_control} for prompt caching). Emptiness has to be tested per
     * shape — {@link JsonNode#asText()} returns {@code ""} for any container node, so a bare {@code
     * asText().isEmpty()} check reads every array-form system prompt as absent and drops it.
     */
    private static boolean hasAnthropicSystemPrompt(@Nullable JsonNode system) {
        if (system == null || system.isNull()) {
            return false;
        }
        return system.isContainerNode() ? !system.isEmpty() : !system.asText().isEmpty();
    }

    @SneakyThrows
    private static void tagAnthropicResponse(
            Span span,
            String responseBody,
            JsonNode responseJson,
            @Nullable Long timeToFirstTokenNanoseconds) {
        // Anthropic response is the full Message object — output it whole
        span.setAttribute("braintrust.output_json", responseBody);

        Map<String, Object> metrics = new HashMap<>();
        if (timeToFirstTokenNanoseconds != null) {
            metrics.put("time_to_first_token", timeToFirstTokenNanoseconds / 1_000_000_000.0);
        }

        if (responseJson.has("usage")) {
            JsonNode usage = responseJson.get("usage");

            // Prompt caching. These are emitted first because prompt_tokens depends on them:
            // Anthropic reports input_tokens *exclusive* of cache reads and writes, whereas
            // Braintrust's prompt_tokens is the inclusive total that the cost pipeline prices and
            // from which prompt_uncached_tokens is derived.
            if (usage.has("cache_read_input_tokens")) {
                metrics.put("prompt_cached_tokens", usage.get("cache_read_input_tokens"));
            }
            long cacheReadTokens =
                    usage.has("cache_read_input_tokens")
                            ? usage.get("cache_read_input_tokens").asLong()
                            : 0L;
            long cacheCreationTokens = addCacheCreationMetrics(metrics, usage);

            // Roll the cache counts back into the canonical totals. Without this a cached call
            // reports only the uncached remainder as prompt_tokens — e.g. 12 instead of 1377 —
            // which both understates cost and makes the cache metrics larger than the total they
            // are meant to be a subset of.
            if (usage.has("input_tokens")) {
                long promptTokens =
                        usage.get("input_tokens").asLong() + cacheReadTokens + cacheCreationTokens;
                metrics.put("prompt_tokens", promptTokens);
                if (usage.has("output_tokens")) {
                    metrics.put("tokens", promptTokens + usage.get("output_tokens").asLong());
                }
            }
            if (usage.has("output_tokens")) {
                metrics.put("completion_tokens", usage.get("output_tokens"));
            }

            // Server-side tool usage counts (e.g. web_search_requests, web_fetch_requests).
            // Each numeric field becomes a server_tool_use_<key> metric the backend prices —
            // this is how web search cost is attributed.
            if (usage.has("server_tool_use") && usage.get("server_tool_use").isObject()) {
                var fields = usage.get("server_tool_use").fields();
                while (fields.hasNext()) {
                    var entry = fields.next();
                    if (entry.getValue().isNumber()) {
                        metrics.put("server_tool_use_" + entry.getKey(), entry.getValue());
                    }
                }
            }
        }

        if (!metrics.isEmpty()) {
            span.setAttribute("braintrust.metrics", toJson(metrics));
        }
    }

    /**
     * Mapping from Anthropic {@code usage.cache_creation} field names to Braintrust per-TTL metric
     * names. Only supported TTL tiers are included.
     */
    private static final Map<String, String> CACHE_CREATION_FIELD_TO_METRIC =
            Map.of(
                    "ephemeral_5m_input_tokens", "prompt_cache_creation_5m_tokens",
                    "ephemeral_1h_input_tokens", "prompt_cache_creation_1h_tokens");

    /**
     * Emits the Anthropic cache-creation metrics and returns the number of tokens they describe.
     *
     * <p>Anthropic reports the same cache-creation tokens two ways: the flat {@code
     * cache_creation_input_tokens} aggregate, and — on SDKs tracking the 2024-10-22 Messages API
     * revision or newer — a per-TTL breakdown under {@code usage.cache_creation}. They are
     * alternative representations of one number rather than separate token classes, so exactly one
     * is emitted (Anthropic spans must carry a single representation), preferring the breakdown
     * when it is available. The returned count is that number either way, so callers can fold it
     * into {@code prompt_tokens} without double-counting.
     *
     * @return the cache-creation token count, or 0 when the response reports none
     */
    private static long addCacheCreationMetrics(Map<String, Object> metrics, JsonNode usage) {
        JsonNode cacheCreation = usage.get("cache_creation");
        if (cacheCreation != null && cacheCreation.isObject()) {
            long perTtlSum = 0;
            boolean emittedPerTtl = false;
            for (Map.Entry<String, String> entry : CACHE_CREATION_FIELD_TO_METRIC.entrySet()) {
                if (cacheCreation.has(entry.getKey())) {
                    long tokens = cacheCreation.get(entry.getKey()).asLong();
                    metrics.put(entry.getValue(), tokens);
                    perTtlSum += tokens;
                    emittedPerTtl = true;
                }
            }
            if (emittedPerTtl) {
                return perTtlSum;
            }
        }
        if (usage.has("cache_creation_input_tokens")) {
            long aggregate = usage.get("cache_creation_input_tokens").asLong();
            metrics.put("prompt_cache_creation_tokens", aggregate);
            return aggregate;
        }
        return 0L;
    }

    private static final String ANTHROPIC_SERVER_TOOL_USE_TYPE = "server_tool_use";
    private static final String ANTHROPIC_TOOL_RESULT_SUFFIX = "_tool_result";

    /**
     * Emit child tool spans for Anthropic server-side tool use. In the Message {@code content}
     * array these appear as a {@code server_tool_use} block (the call) and a matching {@code
     * *_tool_result} block (e.g. {@code web_search_tool_result}), linked by {@code id} /{@code
     * tool_use_id}. Calls and results are paired (buffering results that arrive before their call);
     * unmatched calls and results each still get a span. Mirrors the Python SDK's {@code
     * _log_server_tool_spans}.
     */
    private static void addAnthropicServerSideChildSpans(
            Tracer tracer, Context parentContext, JsonNode responseJson) {
        try {
            JsonNode content = responseJson.get("content");
            if (content == null || !content.isArray()) {
                return;
            }
            Map<String, JsonNode> callsById = new java.util.LinkedHashMap<>();
            Map<String, List<JsonNode>> pendingResultsById = new java.util.LinkedHashMap<>();
            Set<String> matchedCallIds = new HashSet<>();
            List<JsonNode[]> pairs = new java.util.ArrayList<>(); // {call, result}, either nullable

            for (JsonNode item : content) {
                if (!item.isObject()) {
                    continue;
                }
                String itemType = item.path("type").asText(null);
                if (ANTHROPIC_SERVER_TOOL_USE_TYPE.equals(itemType)) {
                    JsonNode id = item.get("id");
                    if (id != null && id.isTextual()) {
                        callsById.put(id.asText(), item);
                        List<JsonNode> pending = pendingResultsById.remove(id.asText());
                        if (pending != null) {
                            for (JsonNode result : pending) {
                                pairs.add(new JsonNode[] {item, result});
                                matchedCallIds.add(id.asText());
                            }
                        }
                    } else {
                        pairs.add(new JsonNode[] {item, null});
                    }
                } else if (isAnthropicToolResultType(itemType)) {
                    JsonNode toolUseId = item.get("tool_use_id");
                    if (toolUseId != null && toolUseId.isTextual()) {
                        if (callsById.containsKey(toolUseId.asText())) {
                            pairs.add(new JsonNode[] {callsById.get(toolUseId.asText()), item});
                            matchedCallIds.add(toolUseId.asText());
                        } else {
                            pendingResultsById
                                    .computeIfAbsent(
                                            toolUseId.asText(), k -> new java.util.ArrayList<>())
                                    .add(item);
                        }
                    } else {
                        pairs.add(new JsonNode[] {null, item});
                    }
                }
            }

            for (JsonNode[] pair : pairs) {
                emitAnthropicServerToolSpan(tracer, parentContext, pair[0], pair[1]);
            }
            for (Map.Entry<String, JsonNode> entry : callsById.entrySet()) {
                if (!matchedCallIds.contains(entry.getKey())) {
                    emitAnthropicServerToolSpan(tracer, parentContext, entry.getValue(), null);
                }
            }
            for (List<JsonNode> pending : pendingResultsById.values()) {
                for (JsonNode result : pending) {
                    emitAnthropicServerToolSpan(tracer, parentContext, null, result);
                }
            }
        } catch (Exception e) {
            log.debug("Could not emit Anthropic server-side child spans", e);
        }
    }

    private static boolean isAnthropicToolResultType(@Nullable String type) {
        return type != null
                && type.endsWith(ANTHROPIC_TOOL_RESULT_SUFFIX)
                && !type.equals("tool_result");
    }

    private static void emitAnthropicServerToolSpan(
            Tracer tracer,
            Context parentContext,
            @Nullable JsonNode call,
            @Nullable JsonNode result) {
        // Zero-duration marker: stamp start and end at the same instant.
        Instant now = Instant.now();
        Span span =
                tracer.spanBuilder(anthropicToolSpanName(call, result))
                        .setParent(parentContext)
                        .setStartTimestamp(now)
                        .startSpan();
        try {
            span.setAttribute("braintrust.span_attributes", TYPE_TOOL_JSON);

            JsonNode input = anthropicToolSpanInput(call);
            if (input != null && !input.isNull()) {
                span.setAttribute("braintrust.input_json", toJson(input));
            }
            String metadata = anthropicToolSpanMetadata(call, result);
            if (metadata != null) {
                span.setAttribute("braintrust.metadata", metadata);
            }

            JsonNode output = anthropicToolSpanOutput(result);
            if (output == null || output.isNull()) {
                return; // no result content — input + metadata only (matches Python)
            }
            String error = anthropicToolSpanError(result);
            if (error != null) {
                span.setStatus(StatusCode.ERROR, error);
            }
            span.setAttribute("braintrust.output_json", toJson(output));
        } catch (Exception e) {
            log.debug("Could not tag Anthropic server-side tool span", e);
        } finally {
            span.end(now);
        }
    }

    private static String anthropicToolSpanName(
            @Nullable JsonNode call, @Nullable JsonNode result) {
        if (call != null) {
            JsonNode name = call.get("name");
            if (name != null && name.isTextual()) {
                return name.asText();
            }
        }
        if (result != null) {
            JsonNode type = result.get("type");
            if (type != null
                    && type.isTextual()
                    && type.asText().endsWith(ANTHROPIC_TOOL_RESULT_SUFFIX)) {
                String t = type.asText();
                return t.substring(0, t.length() - ANTHROPIC_TOOL_RESULT_SUFFIX.length());
            }
        }
        return "server_tool";
    }

    private static final Set<String> ANTHROPIC_CALL_INPUT_EXCLUDED =
            Set.of("id", "type", "name", "caller");

    private static JsonNode anthropicToolSpanInput(@Nullable JsonNode call) {
        if (call == null) {
            return null;
        }
        JsonNode input = call.get("input");
        if (input != null && !input.isNull()) {
            return input;
        }
        ObjectNode obj = BraintrustJsonMapper.get().createObjectNode();
        var fields = call.fields();
        while (fields.hasNext()) {
            var entry = fields.next();
            if (!ANTHROPIC_CALL_INPUT_EXCLUDED.contains(entry.getKey())) {
                obj.set(entry.getKey(), entry.getValue());
            }
        }
        return obj.isEmpty() ? null : obj;
    }

    private static final Set<String> ANTHROPIC_RESULT_OUTPUT_EXCLUDED =
            Set.of("tool_use_id", "type", "caller");

    private static JsonNode anthropicToolSpanOutput(@Nullable JsonNode result) {
        if (result == null) {
            return null;
        }
        if (result.has("content")) {
            return redactServerToolOutput(result.get("content"));
        }
        ObjectNode obj = BraintrustJsonMapper.get().createObjectNode();
        var fields = result.fields();
        while (fields.hasNext()) {
            var entry = fields.next();
            if (!ANTHROPIC_RESULT_OUTPUT_EXCLUDED.contains(entry.getKey())) {
                obj.set(entry.getKey(), redactServerToolOutput(entry.getValue()));
            }
        }
        return obj.isEmpty() ? null : obj;
    }

    /** Recursively replace {@code encrypted_content} values (opaque, large) with a placeholder. */
    private static JsonNode redactServerToolOutput(JsonNode value) {
        if (value == null) {
            return null;
        }
        if (value.isArray()) {
            ArrayNode arr = BraintrustJsonMapper.get().createArrayNode();
            for (JsonNode item : value) {
                arr.add(redactServerToolOutput(item));
            }
            return arr;
        }
        if (value.isObject()) {
            ObjectNode obj = BraintrustJsonMapper.get().createObjectNode();
            var fields = value.fields();
            while (fields.hasNext()) {
                var entry = fields.next();
                if ("encrypted_content".equals(entry.getKey())) {
                    obj.put(entry.getKey(), "<redacted>");
                } else {
                    obj.set(entry.getKey(), redactServerToolOutput(entry.getValue()));
                }
            }
            return obj;
        }
        return value;
    }

    private static String anthropicToolSpanError(@Nullable JsonNode result) {
        if (result == null) {
            return null;
        }
        JsonNode content = result.get("content");
        if (content == null || !content.isObject()) {
            return null;
        }
        JsonNode type = content.get("type");
        if (type == null || !type.isTextual() || !type.asText().endsWith("_error")) {
            return null;
        }
        JsonNode message = content.get("error_message");
        if (message != null && message.isTextual() && !message.asText().isEmpty()) {
            return message.asText();
        }
        JsonNode code = content.get("error_code");
        if (code != null && code.isTextual() && !code.asText().isEmpty()) {
            return code.asText();
        }
        return type.asText();
    }

    private static String anthropicToolSpanMetadata(
            @Nullable JsonNode call, @Nullable JsonNode result) {
        ObjectNode md = BraintrustJsonMapper.get().createObjectNode();
        JsonNode toolUseId = call != null ? call.get("id") : null;
        if (toolUseId == null || toolUseId.isNull()) {
            toolUseId = result != null ? result.get("tool_use_id") : null;
        }
        putIfPresent(md, "tool_use_id", toolUseId);
        if (call != null) {
            putIfPresent(md, "tool_call_type", call.get("type"));
        }
        if (result != null) {
            putIfPresent(md, "tool_result_type", result.get("type"));
        }
        JsonNode caller = call != null ? call.get("caller") : null;
        if (caller == null || caller.isNull()) {
            caller = result != null ? result.get("caller") : null;
        }
        putIfPresent(md, "caller", caller);
        return md.isEmpty() ? null : toJson(md);
    }

    // -------------------------------------------------------------------------
    // AWS Bedrock provider implementation
    // -------------------------------------------------------------------------

    @SneakyThrows
    private static void tagBedrockRequest(
            Span span,
            String providerName,
            String baseUrl,
            List<String> pathSegments,
            String method,
            @Nullable String requestBody,
            @Nullable String modelId) {
        String endpoint = bedrockEndpoint(pathSegments);
        span.updateName("bedrock." + endpoint);
        span.setAttribute("braintrust.span_attributes", toJson(Map.of("type", "llm")));

        Map<String, Object> metadata = new HashMap<>();
        metadata.put("provider", "bedrock");
        metadata.put("endpoint", endpoint);
        metadata.put("request_path", String.join("/", pathSegments));
        metadata.put("request_base_uri", baseUrl);
        metadata.put("request_method", method);

        if (modelId != null) {
            metadata.put("model", modelId);
        }

        if (requestBody != null) {
            JsonNode requestJson = BraintrustJsonMapper.get().readTree(requestBody);
            // Extract inference parameters from inferenceConfig
            if (requestJson.has("inferenceConfig")) {
                JsonNode cfg = requestJson.get("inferenceConfig");
                if (cfg.has("maxTokens")) metadata.put("max_tokens", cfg.get("maxTokens"));
                if (cfg.has("temperature")) metadata.put("temperature", cfg.get("temperature"));
                if (cfg.has("topP")) metadata.put("top_p", cfg.get("topP"));
                if (cfg.has("stopSequences"))
                    metadata.put("stop_sequences", cfg.get("stopSequences"));
            }
            // Tool definitions and tool-choice policy. Flattened out of toolConfig and renamed to
            // the cross-provider keys (as inferenceConfig is above), so a Bedrock span's tools read
            // the same as an OpenAI or Anthropic one. Entries keep their Bedrock
            // {"toolSpec": {...}} shape — this is what the caller actually sent.
            if (requestJson.has("toolConfig")) {
                JsonNode toolConfig = requestJson.get("toolConfig");
                if (toolConfig.has("tools")) {
                    metadata.put("tools", toolConfig.get("tools"));
                }
                if (toolConfig.has("toolChoice")) {
                    metadata.put("tool_choice", toolConfig.get("toolChoice"));
                }
            }
            // Bedrock Converse uses "messages" with typed content block arrays like
            // [{"text":"..."}]
            if (requestJson.has("messages")) {
                ArrayNode inputArray = BraintrustJsonMapper.get().createArrayNode();
                // Bedrock puts system prompts in a separate top-level "system" array:
                // [{"text": "..."}]. Prepend as a synthetic {role:"system", content:[...]} entry.
                if (requestJson.has("system")
                        && requestJson.get("system").isArray()
                        && !requestJson.get("system").isEmpty()) {
                    var systemNode = BraintrustJsonMapper.get().createObjectNode();
                    systemNode.put("role", "system");
                    systemNode.set("content", requestJson.get("system"));
                    inputArray.add(systemNode);
                }
                for (JsonNode msg : requestJson.get("messages")) {
                    inputArray.add(normalizeBedrockMessage(msg));
                }
                span.setAttribute("braintrust.input_json", toJson(inputArray));
            }
        }

        span.setAttribute("braintrust.metadata", toJson(metadata));
    }

    @SneakyThrows
    private static void tagBedrockResponse(
            Span span, JsonNode responseJson, @Nullable Long timeToFirstTokenNanoseconds) {
        // Bedrock output lives at output.message. Normalize to a single-element array matching the
        // same [{role, content: [...]}] shape as input so the UI can render the LLM thread view.
        if (responseJson.has("output") && responseJson.get("output").has("message")) {
            JsonNode message = responseJson.get("output").get("message");
            ArrayNode outputArray = BraintrustJsonMapper.get().createArrayNode();
            outputArray.add(normalizeBedrockMessage(message));
            span.setAttribute("braintrust.output_json", toJson(outputArray));
        }

        Map<String, Object> metrics = new HashMap<>();
        if (timeToFirstTokenNanoseconds != null) {
            metrics.put("time_to_first_token", timeToFirstTokenNanoseconds / 1_000_000_000.0);
        }

        // Bedrock usage uses camelCase: inputTokens, outputTokens, totalTokens
        if (responseJson.has("usage")) {
            JsonNode usage = responseJson.get("usage");

            // Prompt caching, named to match the Anthropic and OpenAI cache metrics above so
            // hit-rate and cost analysis works across providers. Emitted before the totals
            // because prompt_tokens is derived from them.
            //
            // usage.cacheDetails (per-checkpoint {inputTokens, ttl} entries) is deliberately not
            // mapped: a metric has to be a single number, and AWS does not document whether those
            // entries describe reads or writes — so there is no TTL-suffixed metric it can be
            // placed under without guessing. The two aggregates below are unambiguous.
            if (usage.has("cacheReadInputTokens")) {
                metrics.put("prompt_cached_tokens", usage.get("cacheReadInputTokens"));
            }
            if (usage.has("cacheWriteInputTokens")) {
                metrics.put("prompt_cache_creation_tokens", usage.get("cacheWriteInputTokens"));
            }
            long cacheReadTokens = longOrZero(usage, "cacheReadInputTokens");
            long cacheWriteTokens = longOrZero(usage, "cacheWriteInputTokens");

            // Bedrock reports inputTokens *exclusive* of cache reads and writes while folding both
            // into totalTokens, so the cache counts have to be rolled back into prompt_tokens.
            // Verified against a recorded cachePoint call: inputTokens(12) + cacheWrite(1175) +
            // outputTokens(5) == totalTokens(1192), and the same held for the cache-read turn.
            // Because totalTokens already accounts for them, it is preserved as-is rather than
            // recomputed — which also makes the provider's own total a cross-check on this sum.
            if (usage.has("inputTokens")) {
                metrics.put(
                        "prompt_tokens",
                        usage.get("inputTokens").asLong() + cacheReadTokens + cacheWriteTokens);
            }
            if (usage.has("outputTokens"))
                metrics.put("completion_tokens", usage.get("outputTokens"));
            if (usage.has("totalTokens")) metrics.put("tokens", usage.get("totalTokens"));
        }

        if (!metrics.isEmpty()) {
            span.setAttribute("braintrust.metrics", toJson(metrics));
        }
    }

    // -------------------------------------------------------------------------
    // Shared helpers
    // -------------------------------------------------------------------------

    /**
     * If {@code value} is a string that looks like JSON (starts with {@code [} or {@code {}), parse
     * and return it as a tree; otherwise return it unchanged.
     */
    private static JsonNode maybeParseJsonString(JsonNode value) {
        if (value == null || !value.isTextual()) {
            return value;
        }
        String stripped = value.asText().strip();
        if (stripped.isEmpty() || (stripped.charAt(0) != '[' && stripped.charAt(0) != '{')) {
            return value;
        }
        try {
            return BraintrustJsonMapper.get().readTree(stripped);
        } catch (Exception e) {
            return value;
        }
    }

    /**
     * Request fields that are conversation content, not generation parameters. Each is already
     * captured as the span's input, so copying it into metadata would duplicate a potentially large
     * payload. {@code instructions} (the Responses API's system prompt) belongs in the span input
     * too — tracked separately — so it is withheld here rather than landing in metadata.
     *
     * <p>The list spans every endpoint reachable through a wrapped client, not just chat: {@code
     * prompt} carries the user's text on legacy OpenAI completions, Anthropic's legacy {@code
     * /v1/complete}, and image generation, while {@code requests} nests entire per-request message
     * payloads on the Message Batches API.
     *
     * <p>{@code prompt} additionally must never be copied through: {@code metadata.prompt} is
     * reserved for Braintrust prompt provenance ({@code id}/{@code project_id}/{@code version}/
     * {@code variables}), which user-supplied data must not overwrite.
     */
    private static final Set<String> CONTENT_REQUEST_FIELDS =
            Set.of("messages", "input", "system", "instructions", "prompt", "requests");

    /**
     * Copies a request's generation parameters — {@code temperature}, {@code max_tokens}, {@code
     * tools}, {@code response_format}, Anthropic's {@code thinking}, and so on — into span
     * metadata.
     *
     * <p>Deliberately a denylist rather than an allowlist: OpenAI and Anthropic already name these
     * fields in snake_case, so nothing needs renaming, and a parameter added by a future API
     * version shows up in traces without a change here. Only {@link #CONTENT_REQUEST_FIELDS} are
     * withheld.
     *
     * <p>Uses {@code putIfAbsent} so the keys the caller already set — {@code provider}, {@code
     * model}, and the {@code request_*} routing fields — always win over a same-named body field.
     */
    private static void putGenerationParameters(
            Map<String, Object> metadata, JsonNode requestJson) {
        if (!requestJson.isObject()) {
            return;
        }
        var fields = requestJson.fields();
        while (fields.hasNext()) {
            var entry = fields.next();
            if (CONTENT_REQUEST_FIELDS.contains(entry.getKey()) || entry.getValue().isNull()) {
                continue;
            }
            metadata.putIfAbsent(entry.getKey(), entry.getValue());
        }
    }

    /** Returns {@code node[field]} as a long, or 0 when absent or not numeric. */
    private static long longOrZero(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value != null && value.isNumber() ? value.asLong() : 0L;
    }

    private static void putIfPresent(ObjectNode target, String key, JsonNode value) {
        if (value != null && !value.isNull()) {
            target.set(key, value);
        }
    }

    private static String nonEmptyText(JsonNode item, String field) {
        JsonNode node = item.get(field);
        if (node != null && node.isValueNode()) {
            String text = node.asText();
            if (!text.isEmpty()) {
                return text;
            }
        }
        return null;
    }

    /**
     * Simplifies an Anthropic message node by converting single-text content block arrays (e.g.
     * {@code [{"type":"text","text":"hello"}]}) to plain strings. This normalizes the format used
     * by Spring AI's Anthropic client to match the standard Anthropic SDK format.
     */
    private static JsonNode simplifyAnthropicMessage(JsonNode msg) {
        if (!msg.has("content") || !msg.get("content").isArray()) {
            return msg;
        }
        JsonNode contentArray = msg.get("content");
        // Single element that is a text block → simplify to plain string
        if (contentArray.size() == 1) {
            JsonNode block = contentArray.get(0);
            if (block.isObject()
                    && block.has("type")
                    && "text".equals(block.get("type").asText())
                    && block.has("text")) {
                var simplified = ((com.fasterxml.jackson.databind.node.ObjectNode) msg.deepCopy());
                simplified.put("content", block.get("text").asText());
                return simplified;
            }
        }
        return msg;
    }

    /**
     * Normalizes a Bedrock Converse message so its content blocks are compatible with the UI's
     * schema checks. The Converse wire format uses {@code {"text":"..."}} for text blocks, but both
     * the OpenAI and Anthropic schemas the UI validates against require an explicit {@code
     * "type":"text"} field. This method adds {@code "type"} to any content block that has a
     * recognized Bedrock key but is missing it.
     */
    private static JsonNode normalizeBedrockMessage(JsonNode msg) {
        if (!msg.has("content") || !msg.get("content").isArray()) {
            return msg;
        }
        var mapper = BraintrustJsonMapper.get();
        ArrayNode normalizedContent = mapper.createArrayNode();
        boolean changed = false;
        for (JsonNode block : msg.get("content")) {
            if (block.isObject() && !block.has("type")) {
                var normalized = (com.fasterxml.jackson.databind.node.ObjectNode) block.deepCopy();
                if (block.has("text")) {
                    normalized.put("type", "text");
                    changed = true;
                } else if (block.has("toolUse")) {
                    normalized.put("type", "tool_use");
                    changed = true;
                } else if (block.has("toolResult")) {
                    normalized.put("type", "tool_result");
                    changed = true;
                } else if (block.has("image")) {
                    normalized.put("type", "image");
                    changed = true;
                } else if (block.has("reasoningContent")) {
                    // Extended thinking (Claude 3.7+ / Nova reasoning models). Mapped to
                    // Anthropic's block-type name for the same reason toolUse maps to "tool_use":
                    // the schemas the UI validates against are OpenAI's and Anthropic's, so a
                    // Bedrock-native name like "reasoning_content" would satisfy neither.
                    normalized.put("type", "thinking");
                    changed = true;
                }
                normalizedContent.add(normalized);
            } else {
                normalizedContent.add(block);
            }
        }
        if (!changed) {
            return msg;
        }
        var result = (com.fasterxml.jackson.databind.node.ObjectNode) msg.deepCopy();
        result.set("content", normalizedContent);
        return result;
    }

    /** Returns the Bedrock endpoint name from the last URL path segment (e.g. "converse"). */
    private static String bedrockEndpoint(List<String> pathSegments) {
        if (pathSegments.isEmpty()) {
            return "unknown";
        }
        return pathSegments.get(pathSegments.size() - 1);
    }

    private static String getSpanName(String providerName, List<String> pathSegments) {
        if (pathSegments.isEmpty()) {
            return UNSET_LLM_SPAN_NAME;
        }
        String lastSegment = pathSegments.get(pathSegments.size() - 1);
        return switch (providerName + ":" + lastSegment) {
            case PROVIDER_NAME_OPENAI + ":completions" -> "Chat Completion";
            case PROVIDER_NAME_OPENAI + ":embeddings" -> "Embeddings";
            case PROVIDER_NAME_ANTHROPIC + ":messages" -> "anthropic.messages.create";
            default -> lastSegment;
        };
    }

    private static String getStreamingSpanName(String providerName, List<String> pathSegments) {
        if (pathSegments.isEmpty()) {
            return UNSET_LLM_SPAN_NAME;
        }
        String lastSegment = pathSegments.get(pathSegments.size() - 1);
        return switch (providerName + ":" + lastSegment) {
            case PROVIDER_NAME_ANTHROPIC + ":messages" -> "anthropic.messages.stream";
            default -> getSpanName(providerName, pathSegments);
        };
    }
}
