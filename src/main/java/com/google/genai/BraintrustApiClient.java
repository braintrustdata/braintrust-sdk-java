package com.google.genai;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.genai.types.HttpOptions;
import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.Scope;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import javax.annotation.Nullable;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import okhttp3.Headers;
import okhttp3.MediaType;
import okhttp3.ResponseBody;

/**
 * Instrumented wrapper for ApiClient that adds OpenTelemetry spans.
 *
 * <p>This class lives in com.google.genai package to access package-private ApiClient class.
 */
@Slf4j
class BraintrustApiClient extends ApiClient {
    private static final ObjectMapper JSON_MAPPER = new ObjectMapper();

    private final ApiClient delegate;
    private final Tracer tracer;

    public BraintrustApiClient(ApiClient delegate, OpenTelemetry openTelemetry) {
        // We must call super(), but we'll override all methods to delegate
        // Pass the delegate's config to minimize differences
        super(
                delegate.apiKey != null ? delegate.apiKey : Optional.empty(),
                delegate.project != null ? delegate.project : Optional.empty(),
                delegate.location != null ? delegate.location : Optional.empty(),
                delegate.credentials != null ? delegate.credentials : Optional.empty(),
                delegate.httpOptions != null ? Optional.of(delegate.httpOptions) : Optional.empty(),
                delegate.clientOptions != null ? delegate.clientOptions : Optional.empty());
        this.delegate = delegate;
        this.tracer = openTelemetry.getTracer("io.opentelemetry.gemini-java-1.20");
    }

    private void tagSpan(
            Span span,
            @Nullable String genAIEndpoint,
            @Nullable String requestMethod,
            @Nullable String requestBody,
            @Nullable String responseBody,
            double timeToFirstToken) {
        try {
            Map<String, Object> metadata = new java.util.HashMap<>();
            metadata.put("provider", "gemini");

            // Parse request
            if (requestBody != null) {
                var requestJson = JSON_MAPPER.readValue(requestBody, Map.class);

                // Extract metadata fields
                for (String field :
                        List.of(
                                "model",
                                "systemInstruction",
                                "tools",
                                "toolConfig",
                                "safetySettings",
                                "cachedContent")) {
                    if (requestJson.containsKey(field)) {
                        metadata.put(field, requestJson.get(field));
                    }
                }

                // Extract generationConfig fields into metadata
                if (requestJson.get("generationConfig") instanceof Map) {
                    var genConfig = (Map<String, Object>) requestJson.get("generationConfig");
                    for (String field :
                            List.of(
                                    "temperature",
                                    "topP",
                                    "topK",
                                    "candidateCount",
                                    "maxOutputTokens",
                                    "stopSequences",
                                    "responseMimeType",
                                    "responseSchema")) {
                        if (genConfig.containsKey(field)) {
                            metadata.put(field, genConfig.get(field));
                        }
                    }
                }

                // Build input_json
                Map<String, Object> inputJson = new java.util.HashMap<>();
                String model = getModel(genAIEndpoint);
                if (requestJson.containsKey("model")) {
                    inputJson.put("model", requestJson.get("model"));
                } else if (model != null) {
                    inputJson.put("model", model);
                }
                if (requestJson.containsKey("contents")) {
                    inputJson.put("contents", requestJson.get("contents"));
                }
                if (requestJson.containsKey("generationConfig")) {
                    inputJson.put("config", requestJson.get("generationConfig"));
                }

                span.setAttribute(
                        "braintrust.input_json", JSON_MAPPER.writeValueAsString(inputJson));
            }

            // Parse response
            if (responseBody != null) {
                var responseJson = JSON_MAPPER.readValue(responseBody, Map.class);

                // Extract model version from response
                if (responseJson.containsKey("modelVersion")) {
                    metadata.put("model", responseJson.get("modelVersion"));
                }

                // Set full response as output_json
                span.setAttribute(
                        "braintrust.output_json", JSON_MAPPER.writeValueAsString(responseJson));

                // Parse usage metadata for metrics
                Map<String, Number> metrics = new java.util.HashMap<>();

                // Always add time_to_first_token
                metrics.put("time_to_first_token", timeToFirstToken);

                if (responseJson.get("usageMetadata") instanceof Map) {
                    var usage = (Map<String, Object>) responseJson.get("usageMetadata");

                    if (usage.containsKey("promptTokenCount")) {
                        metrics.put("prompt_tokens", (Number) usage.get("promptTokenCount"));
                    }
                    if (usage.containsKey("candidatesTokenCount")) {
                        metrics.put(
                                "completion_tokens", (Number) usage.get("candidatesTokenCount"));
                    }
                    if (usage.containsKey("totalTokenCount")) {
                        metrics.put("tokens", (Number) usage.get("totalTokenCount"));
                    }
                    if (usage.containsKey("cachedContentTokenCount")) {
                        metrics.put(
                                "prompt_cached_tokens",
                                (Number) usage.get("cachedContentTokenCount"));
                    }
                }

                // Always set metrics (at minimum with time_to_first_token)
                span.setAttribute("braintrust.metrics", JSON_MAPPER.writeValueAsString(metrics));
            }

            // Set metadata
            span.setAttribute("braintrust.metadata", JSON_MAPPER.writeValueAsString(metadata));

            // Set span_attributes to mark as LLM span
            span.setAttribute(
                    "braintrust.span_attributes",
                    JSON_MAPPER.writeValueAsString(Map.of("type", "llm")));

        } catch (Throwable t) {
            log.warn("failed to tag gemini span", t);
        }
    }

    // Override accessor methods to delegate to original client
    @Override
    public boolean vertexAI() {
        return delegate.vertexAI();
    }

    @Override
    public String project() {
        return delegate.project();
    }

    @Override
    public String location() {
        return delegate.location();
    }

    @Override
    public String apiKey() {
        return delegate.apiKey();
    }

    @Override
    @SneakyThrows
    public ApiResponse request(
            String requestMethod,
            String genAIUrl,
            String requestBody,
            Optional<HttpOptions> options) {
        Span span =
                tracer.spanBuilder(getOperation(genAIUrl)).setSpanKind(SpanKind.CLIENT).startSpan();
        try (Scope scope = span.makeCurrent()) {
            long startTimeNanos = System.nanoTime();
            ApiResponse response = delegate.request(requestMethod, genAIUrl, requestBody, options);
            double timeToFirstToken = (System.nanoTime() - startTimeNanos) / 1_000_000_000.0;

            BufferedApiResponse bufferedResponse = new BufferedApiResponse(response);
            span.setStatus(StatusCode.OK);
            tagSpan(
                    span,
                    genAIUrl,
                    requestMethod,
                    requestBody,
                    bufferedResponse.getBodyAsString(),
                    timeToFirstToken);
            return bufferedResponse;
        } catch (Throwable t) {
            span.setStatus(StatusCode.ERROR, t.getMessage());
            span.recordException(t);
            throw t;
        } finally {
            span.end();
        }
    }

    @Override
    @SneakyThrows
    public ApiResponse request(
            String requestMethod,
            String genAIUrl,
            byte[] requestBodyBytes,
            Optional<HttpOptions> options) {
        Span span =
                tracer.spanBuilder(getOperation(genAIUrl)).setSpanKind(SpanKind.CLIENT).startSpan();
        try (Scope scope = span.makeCurrent()) {
            long startTimeNanos = System.nanoTime();
            ApiResponse response =
                    delegate.request(requestMethod, genAIUrl, requestBodyBytes, options);
            double timeToFirstToken = (System.nanoTime() - startTimeNanos) / 1_000_000_000.0;

            BufferedApiResponse bufferedResponse = new BufferedApiResponse(response);
            span.setStatus(StatusCode.OK);
            tagSpan(
                    span,
                    genAIUrl,
                    requestMethod,
                    new String(requestBodyBytes),
                    bufferedResponse.getBodyAsString(),
                    timeToFirstToken);
            return bufferedResponse;
        } catch (Throwable t) {
            span.setStatus(StatusCode.ERROR, t.getMessage());
            span.recordException(t);
            throw t;
        } finally {
            span.end();
        }
    }

    @Override
    public CompletableFuture<ApiResponse> asyncRequest(
            String method, String url, String body, Optional<HttpOptions> options) {
        Span span = tracer.spanBuilder(getOperation(url)).setSpanKind(SpanKind.CLIENT).startSpan();
        Context context = Context.current().with(span);
        long startTimeNanos = System.nanoTime();

        return delegate.asyncRequest(method, url, body, options)
                .handle(
                        (response, throwable) -> {
                            try (Scope scope = context.makeCurrent()) {
                                if (throwable != null) {
                                    span.setStatus(StatusCode.ERROR, throwable.getMessage());
                                    span.recordException(throwable);
                                    throw new RuntimeException(throwable);
                                }

                                try {
                                    double timeToFirstToken =
                                            (System.nanoTime() - startTimeNanos) / 1_000_000_000.0;

                                    // Buffer the response so we can read it for instrumentation
                                    BufferedApiResponse bufferedResponse =
                                            new BufferedApiResponse(response);
                                    span.setStatus(StatusCode.OK);
                                    tagSpan(
                                            span,
                                            url,
                                            method,
                                            body,
                                            bufferedResponse.getBodyAsString(),
                                            timeToFirstToken);
                                    return (ApiResponse) bufferedResponse;
                                } catch (Exception e) {
                                    span.setStatus(StatusCode.ERROR, e.getMessage());
                                    span.recordException(e);
                                    throw new RuntimeException(e);
                                }
                            } finally {
                                span.end();
                            }
                        });
    }

    @Override
    public CompletableFuture<ApiResponse> asyncRequest(
            String method, String url, byte[] body, Optional<HttpOptions> options) {
        Span span = tracer.spanBuilder(getOperation(url)).setSpanKind(SpanKind.CLIENT).startSpan();
        Context context = Context.current().with(span);
        long startTimeNanos = System.nanoTime();

        return delegate.asyncRequest(method, url, body, options)
                .handle(
                        (response, throwable) -> {
                            try (Scope scope = context.makeCurrent()) {
                                if (throwable != null) {
                                    span.setStatus(StatusCode.ERROR, throwable.getMessage());
                                    span.recordException(throwable);
                                    throw new RuntimeException(throwable);
                                }

                                try {
                                    double timeToFirstToken =
                                            (System.nanoTime() - startTimeNanos) / 1_000_000_000.0;

                                    // Buffer the response so we can read it for instrumentation
                                    BufferedApiResponse bufferedResponse =
                                            new BufferedApiResponse(response);
                                    span.setStatus(StatusCode.OK);
                                    tagSpan(
                                            span,
                                            url,
                                            method,
                                            new String(body),
                                            bufferedResponse.getBodyAsString(),
                                            timeToFirstToken);
                                    return (ApiResponse) bufferedResponse;
                                } catch (Exception e) {
                                    span.setStatus(StatusCode.ERROR, e.getMessage());
                                    span.recordException(e);
                                    throw new RuntimeException(e);
                                }
                            } finally {
                                span.end();
                            }
                        });
    }

    private static String getModel(String genAIEndpoint) {
        try {
            //  endpoint has model and request type. Example:
            //  models/gemini-2.0-flash-lite:generateContent
            var segments = genAIEndpoint.split("/");
            var lastSegment = segments[segments.length - 1].split(":");
            return lastSegment[0];
        } catch (Exception e) {
            log.debug("unable to determine model name", e);
            return "gemini";
        }
    }

    private static String getOperation(String genAIEndpoint) {
        try {
            //  endpoint has model and request type. Example:
            //  models/gemini-2.0-flash-lite:generateContent
            var segments = genAIEndpoint.split("/");
            var lastSegment = segments[segments.length - 1].split(":");
            return toSnakeCase(lastSegment[1]);
        } catch (Exception e) {
            log.debug("unable to determine operation name", e);
            return "gemini.api.call";
        }
    }

    /** convert a camelCaseString to a snake_case_string */
    private static String toSnakeCase(String camelCase) {
        if (camelCase == null || camelCase.isEmpty()) return camelCase;

        StringBuilder sb = new StringBuilder(camelCase.length() + 5);

        for (int i = 0; i < camelCase.length(); i++) {
            char c = camelCase.charAt(i);
            if (Character.isUpperCase(c)) {
                if (i > 0) sb.append('_');
                sb.append(Character.toLowerCase(c));
            } else {
                sb.append(c);
            }
        }

        return sb.toString();
    }

    /**
     * Wrapper for ApiResponse that buffers the response body so it can be read multiple times.
     *
     * <p>This allows us to capture the response body for instrumentation while still allowing the
     * delegate to read it.
     */
    static class BufferedApiResponse extends ApiResponse {
        private final ApiResponse delegate;
        private final byte[] bufferedBody;

        public BufferedApiResponse(ApiResponse delegate) throws Exception {
            this.delegate = delegate;
            // Read the body once and buffer it
            ResponseBody body = delegate.getBody();
            this.bufferedBody = body != null ? body.bytes() : null;
        }

        @Override
        public ResponseBody getBody() {
            if (bufferedBody == null) {
                return null;
            }
            // Create a new ResponseBody from the buffered bytes
            // Get the original content type if available
            MediaType contentType = null;
            try {
                ResponseBody originalBody = delegate.getBody();
                if (originalBody != null) {
                    contentType = originalBody.contentType();
                }
            } catch (Exception e) {
                // Ignore, use null content type
            }
            return ResponseBody.create(bufferedBody, contentType);
        }

        @Override
        public Headers getHeaders() {
            return delegate.getHeaders();
        }

        @Override
        public void close() {
            delegate.close();
        }

        /** Get the buffered body as a string for instrumentation. */
        public String getBodyAsString() {
            return bufferedBody != null ? new String(bufferedBody) : null;
        }
    }
}
