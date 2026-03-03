package dev.braintrust.instrumentation;

import static dev.braintrust.json.BraintrustJsonMapper.toJson;

import com.fasterxml.jackson.databind.JsonNode;
import dev.braintrust.json.BraintrustJsonMapper;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.StatusCode;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import lombok.SneakyThrows;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

public class InstrumentationSemConv {
    public static final String PROVIDER_NAME_OPENAI = "openai";
    public static final String PROVIDER_NAME_OTHER = "generic-ai-provider";
    public static final String UNSET_LLM_SPAN_NAME = "llm";

    // -------------------------------------------------------------------------
    // Public API — provider-dispatching entry points
    // -------------------------------------------------------------------------

    @SneakyThrows
    public static void tagLLMSpanRequest(
            Span span,
            @NonNull String providerName,
            @NonNull String baseUrl,
            @NonNull List<String> pathSegments,
            @NonNull String method,
            @Nullable String requestBody) {
        switch (providerName) {
            case PROVIDER_NAME_OPENAI ->
                    tagOpenAIRequest(
                            span, providerName, baseUrl, pathSegments, method, requestBody);
            default ->
                    tagOpenAIRequest(
                            span, providerName, baseUrl, pathSegments, method, requestBody);
        }
    }

    public static void tagLLMSpanResponse(
            Span span, @NonNull String providerName, @NonNull String responseBody) {
        tagLLMSpanResponse(span, providerName, responseBody, null);
    }

    @SneakyThrows
    public static void tagLLMSpanResponse(
            Span span,
            @NonNull String providerName,
            @NonNull String responseBody,
            @Nullable Long timeToFirstTokenNanoseconds) {
        switch (providerName) {
            case PROVIDER_NAME_OPENAI ->
                    tagOpenAIResponse(span, responseBody, timeToFirstTokenNanoseconds);
            default -> tagOpenAIResponse(span, responseBody, timeToFirstTokenNanoseconds);
        }
    }

    public static void tagLLMSpanResponse(Span span, @NonNull Throwable responseError) {
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
        span.updateName(getSpanName(pathSegments));
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
    // Shared helpers
    // -------------------------------------------------------------------------

    private static String getSpanName(List<String> pathSegments) {
        if (pathSegments.isEmpty()) {
            return UNSET_LLM_SPAN_NAME;
        }
        return switch (pathSegments.get(pathSegments.size() - 1)) {
            case "completions" -> "Chat Completion";
            case "embeddings" -> "Embeddings";
            case "messages" -> "Messages";
            default -> pathSegments.get(pathSegments.size() - 1);
        };
    }
}
