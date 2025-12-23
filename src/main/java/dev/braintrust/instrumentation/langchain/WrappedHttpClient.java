package dev.braintrust.instrumentation.langchain;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.braintrust.trace.BraintrustTracing;
import dev.langchain4j.exception.HttpException;
import dev.langchain4j.http.client.HttpClient;
import dev.langchain4j.http.client.HttpRequest;
import dev.langchain4j.http.client.SuccessfulHttpResponse;
import dev.langchain4j.http.client.sse.ServerSentEvent;
import dev.langchain4j.http.client.sse.ServerSentEventContext;
import dev.langchain4j.http.client.sse.ServerSentEventListener;
import dev.langchain4j.http.client.sse.ServerSentEventParser;
import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Scope;
import java.util.HashMap;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;

@Slf4j
class WrappedHttpClient implements HttpClient {
    private static final ObjectMapper JSON_MAPPER = new ObjectMapper();

    private final Tracer tracer;
    private final HttpClient underlying;

    public WrappedHttpClient(OpenTelemetry openTelemetry, HttpClient underlying) {
        this.tracer = BraintrustTracing.getTracer(openTelemetry);
        this.underlying = underlying;
    }

    @Override
    public SuccessfulHttpResponse execute(HttpRequest request)
            throws HttpException, RuntimeException {
        ProviderInfo providerInfo = detectProvider(request);
        String spanName = getSpanName(providerInfo);
        Span span = tracer.spanBuilder(spanName).setSpanKind(SpanKind.CLIENT).startSpan();
        long startTime = System.nanoTime();
        try (Scope scope = span.makeCurrent()) {
            tagSpan(span, request, providerInfo);
            var response = underlying.execute(request);
            long endTime = System.nanoTime();
            double timeToFirstToken = (endTime - startTime) / 1_000_000_000.0;
            tagSpan(span, response, providerInfo, timeToFirstToken);
            return response;
        } catch (Throwable t) {
            tagSpan(span, t);
            throw t;
        } finally {
            span.end();
        }
    }

    @Override
    public void execute(HttpRequest request, ServerSentEventListener listener) {
        if (listener instanceof WrappedServerSentEventListener) {
            // we've already applied instrumentation
            underlying.execute(request, listener);
            return;
        }
        ProviderInfo providerInfo = detectProvider(request);
        String spanName = getSpanName(providerInfo);
        Span span = tracer.spanBuilder(spanName).setSpanKind(SpanKind.CLIENT).startSpan();
        try (Scope scope = span.makeCurrent()) {
            tagSpan(span, request, providerInfo);
            underlying.execute(
                    request, new WrappedServerSentEventListener(listener, span, providerInfo));
        } catch (Throwable t) {
            tagSpan(span, t);
            span.end();
            throw t;
        }
    }

    @Override
    public void execute(
            HttpRequest request, ServerSentEventParser parser, ServerSentEventListener listener) {
        if (listener instanceof WrappedServerSentEventListener) {
            // we've already applied instrumentation
            underlying.execute(request, parser, listener);
            return;
        }
        ProviderInfo providerInfo = detectProvider(request);
        String spanName = getSpanName(providerInfo);
        Span span = tracer.spanBuilder(spanName).setSpanKind(SpanKind.CLIENT).startSpan();
        try {
            tagSpan(span, request, providerInfo);
            underlying.execute(
                    request, parser, new WrappedServerSentEventListener(listener, span, providerInfo));
        } catch (Throwable t) {
            tagSpan(span, t);
            span.end();
            throw t;
        }
    }

    /**
     * Detect provider and endpoint from the request URL.
     */
    private static ProviderInfo detectProvider(HttpRequest request) {
        String url = request.url();
        String provider = "unknown";
        String endpoint = "";

        // Extract endpoint from URL path
        try {
            java.net.URI uri = new java.net.URI(url);
            endpoint = uri.getPath();

            // Detect provider from hostname
            String host = uri.getHost();
            if (host != null) {
                if (host.contains("api.openai.com")) {
                    provider = "openai";
                } else if (host.contains("api.anthropic.com")) {
                    provider = "anthropic";
                } else if (host.contains("generativelanguage.googleapis.com")) {
                    provider = "google";
                }
                // Add more providers here as needed
            }
        } catch (Exception e) {
            log.debug("Failed to parse URL: {}", url, e);
        }

        return new ProviderInfo(provider, endpoint);
    }

    /**
     * Get span name based on the provider and endpoint.
     */
    private static String getSpanName(ProviderInfo info) {
        // Map endpoints to human-readable names
        if (info.endpoint.contains("/chat/completions") || info.endpoint.contains("/v1/completions")) {
            return "Chat Completion";
        } else if (info.endpoint.contains("/embeddings")) {
            return "Embeddings";
        } else if (info.endpoint.contains("/messages")) {
            return "Messages";
        }
        // Default to generic name
        return "LLM Request";
    }

    /**
     * Tag span with request data: input messages, model, provider.
     */
    private static void tagSpan(Span span, HttpRequest request, ProviderInfo providerInfo) {
        try {
            // Set span type to llm (in span_attributes, not metadata)
            span.setAttribute("braintrust.span_attributes.type", "llm");

            // Build metadata map
            Map<String, String> metadata = new HashMap<>();
            metadata.put("provider", providerInfo.provider);

            // Parse request body to extract model and messages
            String body = request.body();
            if (body != null && !body.isEmpty()) {
                JsonNode requestJson = JSON_MAPPER.readTree(body);

                // Extract model
                if (requestJson.has("model")) {
                    String model = requestJson.get("model").asText();
                    metadata.put("model", model);
                }

                // Extract messages array for input
                if (requestJson.has("messages")) {
                    String messagesJson = JSON_MAPPER.writeValueAsString(requestJson.get("messages"));
                    span.setAttribute("braintrust.input_json", messagesJson);
                }
            }

            // Serialize metadata as JSON
            span.setAttribute("braintrust.metadata", JSON_MAPPER.writeValueAsString(metadata));
        } catch (Exception e) {
            log.debug("Failed to parse request for span tagging", e);
        }
    }

    /**
     * Tag span with response data: output messages, usage metrics.
     */
    private static void tagSpan(
            Span span, SuccessfulHttpResponse response, ProviderInfo providerInfo, double timeToFirstToken) {
        try {
            // Build metrics map
            Map<String, Object> metrics = new HashMap<>();
            metrics.put("time_to_first_token", timeToFirstToken);

            String body = response.body();
            if (body != null && !body.isEmpty()) {
                JsonNode responseJson = JSON_MAPPER.readTree(body);

                // Extract choices array for output
                if (responseJson.has("choices")) {
                    String choicesJson = JSON_MAPPER.writeValueAsString(responseJson.get("choices"));
                    span.setAttribute("braintrust.output_json", choicesJson);
                }

                // Extract usage metrics if present
                if (responseJson.has("usage")) {
                    JsonNode usage = responseJson.get("usage");
                    if (usage.has("prompt_tokens")) {
                        metrics.put("prompt_tokens", usage.get("prompt_tokens").asLong());
                    }
                    if (usage.has("completion_tokens")) {
                        metrics.put("completion_tokens", usage.get("completion_tokens").asLong());
                    }
                    if (usage.has("total_tokens")) {
                        metrics.put("tokens", usage.get("total_tokens").asLong());
                    }
                }
            }

            // Serialize metrics as JSON
            span.setAttribute("braintrust.metrics", JSON_MAPPER.writeValueAsString(metrics));
        } catch (Exception e) {
            log.debug("Failed to parse response for span tagging", e);
        }
    }

    /**
     * Tag span with error information.
     */
    private static void tagSpan(Span span, Throwable t) {
        span.setStatus(StatusCode.ERROR, t.getMessage());
        span.recordException(t);
    }

    /**
     * Wraps a ServerSentEventListener to properly end the span when streaming completes or errors.
     * Also buffers streaming chunks to extract usage data.
     */
    private static class WrappedServerSentEventListener implements ServerSentEventListener {
        private final ServerSentEventListener delegate;
        private final Span span;
        private final ProviderInfo providerInfo;
        private final StringBuilder outputBuffer = new StringBuilder();
        private long firstTokenTime = 0;
        private final long startTime;
        private JsonNode usageData = null;

        WrappedServerSentEventListener(
                ServerSentEventListener delegate, Span span, ProviderInfo providerInfo) {
            this.delegate = delegate;
            this.span = span;
            this.providerInfo = providerInfo;
            this.startTime = System.nanoTime();
        }

        @Override
        public void onOpen(SuccessfulHttpResponse response) {
            delegate.onOpen(response);
        }

        @Override
        public void onEvent(ServerSentEvent event, ServerSentEventContext context) {
            instrumentEvent(event);
            delegate.onEvent(event, context);
        }

        @Override
        public void onEvent(ServerSentEvent event) {
            instrumentEvent(event);
            delegate.onEvent(event);
        }

        private void instrumentEvent(ServerSentEvent event) {
            String data = event.data();
            if (data == null || data.isEmpty() || "[DONE]".equals(data)) {
                return;
            }

            // Track time to first token
            if (firstTokenTime == 0) {
                firstTokenTime = System.nanoTime();
            }

            // Buffer the data for final processing
            try {
                JsonNode chunk = JSON_MAPPER.readTree(data);

                // For streaming, we accumulate deltas into the complete message
                // Just track if we have any content
                if (chunk.has("choices") && chunk.get("choices").size() > 0) {
                    JsonNode choice = chunk.get("choices").get(0);
                    if (choice.has("delta")) {
                        JsonNode delta = choice.get("delta");
                        if (delta.has("content")) {
                            String content = delta.get("content").asText();
                            outputBuffer.append(content);
                        }
                    }
                }

                // Extract usage data if present (usually in the last chunk)
                if (chunk.has("usage")) {
                    usageData = chunk.get("usage");
                }
            } catch (Exception e) {
                log.debug("Failed to parse streaming event: {}", data, e);
            }
        }

        @Override
        public void onError(Throwable error) {
            try {
                delegate.onError(error);
            } finally {
                tagSpan(span, error);
                finalizeSpan();
                span.end();
            }
        }

        @Override
        public void onClose() {
            try {
                delegate.onClose();
            } finally {
                finalizeSpan();
                span.end();
            }
        }

        private void finalizeSpan() {
            // Build metrics map for streaming
            Map<String, Object> metrics = new HashMap<>();

            // Add time to first token if we have it
            if (firstTokenTime > 0) {
                double timeToFirstToken = (firstTokenTime - startTime) / 1_000_000_000.0;
                metrics.put("time_to_first_token", timeToFirstToken);
            }

            // Reconstruct output as a choices array for streaming
            // Format: [{"index": 0, "finish_reason": "stop", "message": {"role": "assistant", "content": "..."}}]
            if (outputBuffer.length() > 0) {
                try {
                    // Create a proper choice object matching OpenAI API format
                    var choiceBuilder = JSON_MAPPER.createObjectNode();
                    choiceBuilder.put("index", 0);
                    choiceBuilder.put("finish_reason", "stop");

                    var messageNode = JSON_MAPPER.createObjectNode();
                    messageNode.put("role", "assistant");
                    messageNode.put("content", outputBuffer.toString());

                    choiceBuilder.set("message", messageNode);

                    var choicesArray = JSON_MAPPER.createArrayNode();
                    choicesArray.add(choiceBuilder);

                    span.setAttribute("braintrust.output_json", choicesArray.toString());
                } catch (Exception e) {
                    log.debug("Failed to reconstruct streaming output", e);
                }
            }

            // Set usage metrics if we collected them
            if (usageData != null) {
                try {
                    if (usageData.has("prompt_tokens")) {
                        metrics.put("prompt_tokens", usageData.get("prompt_tokens").asLong());
                    }
                    if (usageData.has("completion_tokens")) {
                        metrics.put("completion_tokens", usageData.get("completion_tokens").asLong());
                    }
                    if (usageData.has("total_tokens")) {
                        metrics.put("tokens", usageData.get("total_tokens").asLong());
                    }
                } catch (Exception e) {
                    log.debug("Failed to extract usage metrics from streaming data", e);
                }
            }

            // Serialize metrics as JSON
            try {
                if (!metrics.isEmpty()) {
                    span.setAttribute("braintrust.metrics", JSON_MAPPER.writeValueAsString(metrics));
                }
            } catch (Exception e) {
                log.debug("Failed to serialize metrics", e);
            }
        }
    }

    private record ProviderInfo(String provider, String endpoint) {}
}
