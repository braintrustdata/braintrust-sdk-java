package dev.braintrust.instrumentation.langchain.v1_14_0;

import dev.braintrust.bootstrap.BraintrustBridge;
import dev.braintrust.instrumentation.InstrumentationSemConv;
import dev.braintrust.instrumentation.SseStreamAccumulator;
import dev.braintrust.json.BraintrustJsonMapper;
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
import java.time.Instant;
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
        this.tracer = openTelemetry.getTracer(BraintrustBridge.INSTRUMENTATION_NAME);
        this.underlying = underlying;
        this.options = options;
    }

    @Override
    public SuccessfulHttpResponse execute(HttpRequest request)
            throws HttpException, RuntimeException {
        Instant llmSpanStart = Instant.now();
        Span span =
                tracer.spanBuilder(InstrumentationSemConv.UNSET_LLM_SPAN_NAME)
                        .setSpanKind(SpanKind.CLIENT)
                        .setStartTimestamp(llmSpanStart)
                        .startSpan();
        try (Scope scope = span.makeCurrent()) {
            tagRequest(span, request);
            var response = underlying.execute(request);
            InstrumentationSemConv.tagLLMSpanResponse(
                    tracer, span, options.providerName(), response.body());
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
        Instant llmSpanStart = Instant.now();
        Span span =
                tracer.spanBuilder(InstrumentationSemConv.UNSET_LLM_SPAN_NAME)
                        .setSpanKind(SpanKind.CLIENT)
                        .setStartTimestamp(llmSpanStart)
                        .startSpan();
        try (Scope ignored = span.makeCurrent()) {
            tagRequest(span, request);
            underlying.execute(
                    request,
                    new WrappedServerSentEventListener(
                            listener, span, options.providerName(), tracer));
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
        Instant llmSpanStart = Instant.now();
        Span span =
                tracer.spanBuilder(InstrumentationSemConv.UNSET_LLM_SPAN_NAME)
                        .setSpanKind(SpanKind.CLIENT)
                        .setStartTimestamp(llmSpanStart)
                        .startSpan();
        try (Scope ignored = span.makeCurrent()) {
            tagRequest(span, request);
            underlying.execute(
                    request,
                    parser,
                    new WrappedServerSentEventListener(
                            listener, span, options.providerName(), tracer));
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
        private final Tracer tracer;
        private final long startNanos = System.nanoTime();
        // Time-to-first-token is measured from the first payload that carries generated output,
        // not the first payload of any kind — a Responses stream opens with response.created
        // before the model has produced anything. firstPayloadNanos is kept only as a fallback so
        // an unrecognized stream shape still reports a (slightly early) TTFT rather than none.
        private final AtomicLong firstOutputNanos = new AtomicLong();
        private final AtomicLong firstPayloadNanos = new AtomicLong();
        // Handles both endpoints this module instruments: chat-completions chunk streams and
        // Responses API (`/v1/responses`) event streams.
        private final SseStreamAccumulator accumulator =
                new SseStreamAccumulator(BraintrustJsonMapper.get());

        WrappedServerSentEventListener(
                ServerSentEventListener delegate, Span span, String providerName, Tracer tracer) {
            this.delegate = delegate;
            this.span = span;
            this.providerName = providerName;
            this.tracer = tracer;
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
            firstPayloadNanos.compareAndExchange(0L, System.nanoTime() - startNanos);
            // Only parse for output detection until the first one is found; afterwards this is a
            // single volatile read per chunk.
            if (firstOutputNanos.get() == 0L
                    && SseStreamAccumulator.carriesGeneratedOutput(
                            BraintrustJsonMapper.get(), data)) {
                firstOutputNanos.compareAndExchange(0L, System.nanoTime() - startNanos);
            }
            accumulator.merge(data);
        }

        /**
         * Seconds-precision source for {@code time_to_first_token}: the first generated output if
         * one was recognized, else the first payload seen, else absent — a stream that produced
         * nothing reports no TTFT rather than a fabricated zero.
         */
        @javax.annotation.Nullable
        private Long timeToFirstTokenNanos() {
            long output = firstOutputNanos.get();
            if (output != 0L) {
                return output;
            }
            long payload = firstPayloadNanos.get();
            return payload != 0L ? payload : null;
        }

        private void finalizeSpan() {
            try {
                Long ttft = timeToFirstTokenNanos();
                String responseBody = accumulator.build();
                InstrumentationSemConv.tagLLMSpanResponse(
                        tracer, span, providerName, responseBody, ttft);
            } catch (Exception e) {
                log.debug("Failed to finalize streaming span", e);
            }
        }
    }
}
