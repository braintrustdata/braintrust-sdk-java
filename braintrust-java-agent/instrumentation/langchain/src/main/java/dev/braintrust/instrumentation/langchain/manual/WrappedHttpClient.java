package dev.braintrust.instrumentation.langchain.manual;

import static dev.braintrust.json.BraintrustJsonMapper.toJson;

import com.fasterxml.jackson.databind.JsonNode;
import dev.braintrust.instrumentation.InstrumentationSemConv;
import dev.braintrust.json.BraintrustJsonMapper;
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
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Scope;
import java.net.URI;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import lombok.extern.slf4j.Slf4j;

@Slf4j
class WrappedHttpClient implements HttpClient {
    private final Tracer tracer;
    private final HttpClient underlying;
    private final BraintrustLangchain.Options options;

    public WrappedHttpClient(
            OpenTelemetry openTelemetry,
            HttpClient underlying,
            BraintrustLangchain.Options options) {
        this.tracer = BraintrustTracing.getTracer(openTelemetry);
        this.underlying = underlying;
        this.options = options;
    }

    @Override
    public SuccessfulHttpResponse execute(HttpRequest request)
            throws HttpException, RuntimeException {
        Span span =
                tracer.spanBuilder(InstrumentationSemConv.UNSET_LLM_SPAN_NAME)
                        .setSpanKind(SpanKind.CLIENT)
                        .startSpan();
        try (Scope scope = span.makeCurrent()) {
            tagRequest(span, request);
            var response = underlying.execute(request);
            InstrumentationSemConv.tagLLMSpanResponse(
                    span, options.providerName(), response.body());
            return response;
        } catch (Throwable t) {
            InstrumentationSemConv.tagLLMSpanResponse(span, t);
            throw t;
        } finally {
            span.end();
        }
    }

    @Override
    public void execute(HttpRequest request, ServerSentEventListener listener) {
        if (listener instanceof WrappedServerSentEventListener) {
            underlying.execute(request, listener);
            return;
        }
        Span span =
                tracer.spanBuilder(InstrumentationSemConv.UNSET_LLM_SPAN_NAME)
                        .setSpanKind(SpanKind.CLIENT)
                        .startSpan();
        try (Scope ignored = span.makeCurrent()) {
            tagRequest(span, request);
            underlying.execute(
                    request,
                    new WrappedServerSentEventListener(listener, span, options.providerName()));
        } catch (Throwable t) {
            InstrumentationSemConv.tagLLMSpanResponse(span, t);
            span.end();
            throw t;
        }
    }

    @Override
    public void execute(
            HttpRequest request, ServerSentEventParser parser, ServerSentEventListener listener) {
        if (listener instanceof WrappedServerSentEventListener) {
            underlying.execute(request, parser, listener);
            return;
        }
        Span span =
                tracer.spanBuilder(InstrumentationSemConv.UNSET_LLM_SPAN_NAME)
                        .setSpanKind(SpanKind.CLIENT)
                        .startSpan();
        try (Scope ignored = span.makeCurrent()) {
            tagRequest(span, request);
            underlying.execute(
                    request,
                    parser,
                    new WrappedServerSentEventListener(listener, span, options.providerName()));
        } catch (Throwable t) {
            InstrumentationSemConv.tagLLMSpanResponse(span, t);
            span.end();
            throw t;
        }
    }

    private void tagRequest(Span span, HttpRequest request) {
        try {
            URI uri = new URI(request.url());
            String baseUrl = uri.getScheme() + "://" + uri.getAuthority();
            List<String> pathSegments =
                    Arrays.stream(uri.getPath().split("/")).filter(s -> !s.isEmpty()).toList();
            InstrumentationSemConv.tagLLMSpanRequest(
                    span, options.providerName(), baseUrl, pathSegments, "POST", request.body());
        } catch (Exception e) {
            log.debug("Failed to tag request span", e);
        }
    }

    static class WrappedServerSentEventListener implements ServerSentEventListener {
        private final ServerSentEventListener delegate;
        private final Span span;
        private final String providerName;
        private final long startNanos = System.nanoTime();
        private final AtomicLong timeToFirstTokenNanos = new AtomicLong();
        private final StringBuilder contentBuffer = new StringBuilder();
        private String finishReason = null;
        private JsonNode usageData = null;

        WrappedServerSentEventListener(
                ServerSentEventListener delegate, Span span, String providerName) {
            this.delegate = delegate;
            this.span = span;
            this.providerName = providerName;
        }

        @Override
        public void onOpen(SuccessfulHttpResponse response) {
            try (Scope ignored = span.makeCurrent()) {
                delegate.onOpen(response);
            }
        }

        @Override
        public void onEvent(ServerSentEvent event, ServerSentEventContext context) {
            try (Scope ignored = span.makeCurrent()) {
                accumulateChunk(event.data());
                delegate.onEvent(event, context);
            }
        }

        @Override
        public void onEvent(ServerSentEvent event) {
            try (Scope ignored = span.makeCurrent()) {
                accumulateChunk(event.data());
                delegate.onEvent(event);
            }
        }

        @Override
        public void onError(Throwable error) {
            try (Scope ignored = span.makeCurrent()) {
                delegate.onError(error);
            } finally {
                InstrumentationSemConv.tagLLMSpanResponse(span, error);
                span.end();
            }
        }

        @Override
        public void onClose() {
            try (Scope ignored = span.makeCurrent()) {
                delegate.onClose();
            } finally {
                finalizeSpan();
                span.end();
            }
        }

        private void accumulateChunk(String data) {
            if (data == null || data.isEmpty() || "[DONE]".equals(data)) return;
            try {
                if (timeToFirstTokenNanos.get() == 0L) {
                    timeToFirstTokenNanos.compareAndExchange(0L, System.nanoTime() - startNanos);
                }
                JsonNode chunk = BraintrustJsonMapper.get().readTree(data);
                if (chunk.has("choices") && chunk.get("choices").size() > 0) {
                    JsonNode choice = chunk.get("choices").get(0);
                    if (choice.has("delta")) {
                        JsonNode delta = choice.get("delta");
                        if (delta.has("content")) {
                            contentBuffer.append(delta.get("content").asText());
                        }
                    }
                    if (choice.has("finish_reason") && !choice.get("finish_reason").isNull()) {
                        finishReason = choice.get("finish_reason").asText();
                    }
                }
                if (chunk.has("usage") && !chunk.get("usage").isNull()) {
                    usageData = chunk.get("usage");
                }
            } catch (Exception e) {
                log.debug("Failed to parse SSE chunk: {}", data, e);
            }
        }

        private void finalizeSpan() {
            try {
                var root = BraintrustJsonMapper.get().createObjectNode();

                var choicesArray = BraintrustJsonMapper.get().createArrayNode();
                var choice = BraintrustJsonMapper.get().createObjectNode();
                choice.put("index", 0);
                if (finishReason != null) choice.put("finish_reason", finishReason);
                var message = BraintrustJsonMapper.get().createObjectNode();
                message.put("role", "assistant");
                message.put("content", contentBuffer.toString());
                choice.set("message", message);
                choicesArray.add(choice);
                root.set("choices", choicesArray);

                if (usageData != null) {
                    root.set("usage", usageData);
                }

                long ttft = timeToFirstTokenNanos.get();
                InstrumentationSemConv.tagLLMSpanResponse(
                        span, providerName, toJson(root), ttft == 0L ? null : ttft);
            } catch (Exception e) {
                log.debug("Failed to finalize streaming span", e);
            }
        }
    }
}
