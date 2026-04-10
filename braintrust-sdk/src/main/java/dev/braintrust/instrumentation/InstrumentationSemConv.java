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
        switch (providerName) {
            case PROVIDER_NAME_OPENAI ->
                    tagOpenAIRequest(
                            span, providerName, baseUrl, pathSegments, method, requestBody);
            case PROVIDER_NAME_ANTHROPIC ->
                    tagAnthropicRequest(
                            span, providerName, baseUrl, pathSegments, method, requestBody);
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
