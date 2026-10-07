package com.google.genai;

import static dev.braintrust.json.BraintrustJsonMapper.fromJson;
import static dev.braintrust.json.BraintrustJsonMapper.toJson;

import com.google.genai.types.HttpOptions;
import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.Scope;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;
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
    private static final String INSTRUMENTATION_NAME = "genai";
    private static final String INSTRUMENTATION_VERSION = "1.18.0";

    /**
     * Operations that produce model output and get the full LLM treatment, keyed on the {@code
     * :operation} suffix of the endpoint so {@code models/}, {@code tunedModels/} and Vertex {@code
     * publishers/google/models/} all match. Everything else (batches, files, caches, token
     * counting, ...) gets a plain {@code google.http} span with the body left untouched.
     */
    private static final Set<String> LLM_OPERATIONS =
            Set.of(
                    "generateContent",
                    "streamGenerateContent",
                    "embedContent",
                    "batchEmbedContents",
                    "predict",
                    "predictLongRunning");

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
        this.tracer = openTelemetry.getTracer(INSTRUMENTATION_NAME, INSTRUMENTATION_VERSION);
    }

    private void tagSpan(
            Span span,
            @Nullable String genAIEndpoint,
            @Nullable String requestBody,
            @Nullable String responseBody) {
        try {
            Map<String, Object> metadata = new java.util.HashMap<>();
            metadata.put("provider", "google");

            // Parse request
            if (requestBody != null) {
                var requestJson = fromJson(requestBody, Map.class);

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

                span.setAttribute("braintrust.input_json", toJson(inputJson));
            }

            // Parse response
            if (responseBody != null) {
                var responseJson = fromJson(responseBody, Map.class);

                // Extract model version from response
                if (responseJson.containsKey("modelVersion")) {
                    metadata.put("model", responseJson.get("modelVersion"));
                }

                // Set full response as output_json
                span.setAttribute("braintrust.output_json", toJson(responseJson));

                // Parse usage metadata for metrics
                if (responseJson.get("usageMetadata") instanceof Map) {
                    var usage = (Map<String, Object>) responseJson.get("usageMetadata");
                    Map<String, Number> metrics = new java.util.HashMap<>();

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

                    span.setAttribute("braintrust.metrics", toJson(metrics));
                }
            }

            // Set metadata
            span.setAttribute("braintrust.metadata", toJson(metadata));

            // Set span_attributes to mark as LLM span
            span.setAttribute("braintrust.span_attributes", toJson(Map.of("type", "llm")));

        } catch (Throwable t) {
            log.warn("failed to tag gemini span", t);
        }
    }

    private static boolean isLlmEndpoint(@Nullable String endpoint) {
        if (endpoint == null) {
            return false;
        }
        String path = endpoint.split("[?#]", 2)[0];
        String lastSegment = path.substring(path.lastIndexOf('/') + 1);
        int colon = lastSegment.indexOf(':');
        return colon >= 0 && LLM_OPERATIONS.contains(lastSegment.substring(colon + 1));
    }

    private Span startSpan(String endpoint, boolean llm) {
        return tracer.spanBuilder(llm ? getOperation(endpoint) : "google.http")
                .setSpanKind(SpanKind.CLIENT)
                .startSpan();
    }

    /**
     * Completes the span for a response the delegate returned. LLM responses are buffered so the
     * body can be tagged and still handed back; anything else is returned as-is so large payloads
     * (file downloads, batch results) stream straight through to the caller.
     */
    private ApiResponse finishResponse(
            Span span,
            String endpoint,
            boolean llm,
            Supplier<String> requestBody,
            ApiResponse response)
            throws Exception {
        if (!llm) {
            // getBody() is where the SDK raises ApiException for a non-2xx response (reading the
            // error body to build the message); a successful body is returned unread.
            response.getBody();
            span.setStatus(StatusCode.OK);
            return response;
        }
        BufferedApiResponse bufferedResponse = new BufferedApiResponse(response);
        span.setStatus(StatusCode.OK);
        tagSpan(span, endpoint, requestBody.get(), bufferedResponse.getBodyAsString());
        return bufferedResponse;
    }

    private static void recordError(Span span, Throwable t) {
        span.setStatus(StatusCode.ERROR, t.getMessage());
        span.recordException(t);
    }

    private ApiResponse traceRequest(
            String endpoint, Supplier<String> requestBody, Callable<ApiResponse> call)
            throws Exception {
        boolean llm = isLlmEndpoint(endpoint);
        Span span = startSpan(endpoint, llm);
        try (Scope scope = span.makeCurrent()) {
            return finishResponse(span, endpoint, llm, requestBody, call.call());
        } catch (Throwable t) {
            recordError(span, t);
            throw t;
        } finally {
            span.end();
        }
    }

    private CompletableFuture<ApiResponse> traceAsyncRequest(
            String endpoint,
            Supplier<String> requestBody,
            Supplier<CompletableFuture<ApiResponse>> call) {
        boolean llm = isLlmEndpoint(endpoint);
        Span span = startSpan(endpoint, llm);
        Context context = Context.current().with(span);

        CompletableFuture<ApiResponse> future;
        try {
            future = call.get();
        } catch (RuntimeException | Error t) {
            recordError(span, t);
            span.end();
            throw t;
        }
        return future.handle(
                (response, throwable) -> {
                    try (Scope scope = context.makeCurrent()) {
                        if (throwable != null) {
                            recordError(span, throwable);
                            throw new RuntimeException(throwable);
                        }
                        try {
                            return finishResponse(span, endpoint, llm, requestBody, response);
                        } catch (Exception e) {
                            recordError(span, e);
                            throw new RuntimeException(e);
                        }
                    } finally {
                        span.end();
                    }
                });
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
        return traceRequest(
                genAIUrl,
                () -> requestBody,
                () -> delegate.request(requestMethod, genAIUrl, requestBody, options));
    }

    @Override
    @SneakyThrows
    public ApiResponse request(
            String requestMethod,
            String genAIUrl,
            byte[] requestBodyBytes,
            Optional<HttpOptions> options) {
        return traceRequest(
                genAIUrl,
                decode(requestBodyBytes),
                () -> delegate.request(requestMethod, genAIUrl, requestBodyBytes, options));
    }

    @Override
    public CompletableFuture<ApiResponse> asyncRequest(
            String method, String url, String body, Optional<HttpOptions> options) {
        return traceAsyncRequest(
                url, () -> body, () -> delegate.asyncRequest(method, url, body, options));
    }

    @Override
    public CompletableFuture<ApiResponse> asyncRequest(
            String method, String url, byte[] body, Optional<HttpOptions> options) {
        return traceAsyncRequest(
                url, decode(body), () -> delegate.asyncRequest(method, url, body, options));
    }

    /**
     * Request bytes as a string for tagging; only read for LLM calls, which are the only ones
     * tagged.
     */
    private static Supplier<String> decode(@Nullable byte[] body) {
        return () -> body == null ? null : new String(body, StandardCharsets.UTF_8);
    }

    private static String getModel(String genAIEndpoint) {
        try {
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

    /** Wrapper for ApiResponse that buffers the response body so it can be read multiple times. */
    static class BufferedApiResponse extends ApiResponse {
        private final ApiResponse delegate;
        private final byte[] bufferedBody;

        public BufferedApiResponse(ApiResponse delegate) throws Exception {
            this.delegate = delegate;
            ResponseBody body = delegate.getBody();
            this.bufferedBody = body != null ? body.bytes() : null;
        }

        @Override
        public ResponseBody getBody() {
            if (bufferedBody == null) {
                return null;
            }
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
