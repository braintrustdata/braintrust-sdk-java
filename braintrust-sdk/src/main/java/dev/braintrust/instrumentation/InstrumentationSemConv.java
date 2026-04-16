package dev.braintrust.instrumentation;

import static dev.braintrust.json.BraintrustJsonMapper.toJson;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import dev.braintrust.json.BraintrustJsonMapper;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.StatusCode;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import lombok.SneakyThrows;

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
            Span span, @Nonnull String providerName, @Nonnull String responseBody) {
        tagLLMSpanResponse(span, providerName, responseBody, null);
    }

    @SneakyThrows
    public static void tagLLMSpanResponse(
            Span span,
            @Nonnull String providerName,
            @Nonnull String responseBody,
            @Nullable Long timeToFirstTokenNanoseconds) {
        switch (providerName) {
            case PROVIDER_NAME_OPENAI ->
                    tagOpenAIResponse(span, responseBody, timeToFirstTokenNanoseconds);
            case PROVIDER_NAME_ANTHROPIC ->
                    tagAnthropicResponse(span, responseBody, timeToFirstTokenNanoseconds);
            case PROVIDER_NAME_BEDROCK ->
                    tagBedrockResponse(span, responseBody, timeToFirstTokenNanoseconds);
            default -> tagOpenAIResponse(span, responseBody, timeToFirstTokenNanoseconds);
        }
    }

    public static void tagLLMSpanResponse(Span span, @Nonnull Throwable responseError) {
        span.setStatus(StatusCode.ERROR, responseError.getMessage());
        span.recordException(responseError);
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

        Map<String, String> metadata = new HashMap<>();
        metadata.put("provider", providerName);
        metadata.put("request_path", String.join("/", pathSegments));
        metadata.put("request_base_uri", baseUrl);
        metadata.put("request_method", method);

        if (requestBody != null) {
            JsonNode requestJson = BraintrustJsonMapper.get().readTree(requestBody);
            if (requestJson.has("model")) {
                metadata.put("model", requestJson.get("model").asText());
            }
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
            Span span, String responseBody, @Nullable Long timeToFirstTokenNanoseconds) {
        JsonNode responseJson = BraintrustJsonMapper.get().readTree(responseBody);

        // Output — chat completions API uses "choices"; Responses API uses "output"
        if (responseJson.has("choices")) {
            span.setAttribute("braintrust.output_json", toJson(responseJson.get("choices")));
        } else if (responseJson.has("output")) {
            span.setAttribute("braintrust.output_json", toJson(responseJson.get("output")));
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
            // Responses API field names
            if (usage.has("input_tokens")) metrics.put("prompt_tokens", usage.get("input_tokens"));
            if (usage.has("output_tokens"))
                metrics.put("completion_tokens", usage.get("output_tokens"));
            if (usage.has("input_tokens") && usage.has("output_tokens")) {
                metrics.put(
                        "tokens",
                        usage.get("input_tokens").asLong() + usage.get("output_tokens").asLong());
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

        Map<String, String> metadata = new HashMap<>();
        metadata.put("provider", providerName);
        metadata.put("request_path", String.join("/", pathSegments));
        metadata.put("request_base_uri", baseUrl);
        metadata.put("request_method", method);

        if (requestBody != null) {
            JsonNode requestJson = BraintrustJsonMapper.get().readTree(requestBody);
            if (requestJson.has("model")) {
                metadata.put("model", requestJson.get("model").asText());
            }
            // Build input array: messages + system (as a synthetic system-role entry)
            if (requestJson.has("messages")) {
                ArrayNode inputArray = BraintrustJsonMapper.get().createArrayNode();
                // Append messages, simplifying single-text content blocks to plain strings
                for (JsonNode msg : requestJson.get("messages")) {
                    inputArray.add(simplifyAnthropicMessage(msg));
                }
                // Append system prompt as a {role:"system", content:"..."} entry if present
                if (requestJson.has("system")
                        && !requestJson.get("system").isNull()
                        && !requestJson.get("system").asText().isEmpty()) {
                    var systemNode = BraintrustJsonMapper.get().createObjectNode();
                    systemNode.put("role", "system");
                    systemNode.set("content", requestJson.get("system"));
                    inputArray.add(systemNode);
                }
                span.setAttribute("braintrust.input_json", toJson(inputArray));
            }
        }

        span.setAttribute("braintrust.metadata", toJson(metadata));
    }

    @SneakyThrows
    private static void tagAnthropicResponse(
            Span span, String responseBody, @Nullable Long timeToFirstTokenNanoseconds) {
        JsonNode responseJson = BraintrustJsonMapper.get().readTree(responseBody);

        // Anthropic response is the full Message object — output it whole
        span.setAttribute("braintrust.output_json", responseBody);

        Map<String, Object> metrics = new HashMap<>();
        if (timeToFirstTokenNanoseconds != null) {
            metrics.put("time_to_first_token", timeToFirstTokenNanoseconds / 1_000_000_000.0);
        }

        if (responseJson.has("usage")) {
            JsonNode usage = responseJson.get("usage");
            if (usage.has("input_tokens")) metrics.put("prompt_tokens", usage.get("input_tokens"));
            if (usage.has("output_tokens"))
                metrics.put("completion_tokens", usage.get("output_tokens"));
            if (usage.has("input_tokens") && usage.has("output_tokens")) {
                metrics.put(
                        "tokens",
                        usage.get("input_tokens").asLong() + usage.get("output_tokens").asLong());
            }
        }

        if (!metrics.isEmpty()) {
            span.setAttribute("braintrust.metrics", toJson(metrics));
        }
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
            Span span, String responseBody, @Nullable Long timeToFirstTokenNanoseconds) {
        JsonNode responseJson = BraintrustJsonMapper.get().readTree(responseBody);

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
            if (usage.has("inputTokens")) metrics.put("prompt_tokens", usage.get("inputTokens"));
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
}
