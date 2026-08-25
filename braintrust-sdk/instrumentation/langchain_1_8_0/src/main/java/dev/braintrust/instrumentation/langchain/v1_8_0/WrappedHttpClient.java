package dev.braintrust.instrumentation.langchain.v1_8_0;

import dev.braintrust.bootstrap.BraintrustBridge;
import dev.braintrust.instrumentation.InstrumentationSemConv;
import dev.braintrust.instrumentation.SseResponseAccumulator;
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
import io.opentelemetry.api.trace.StatusCode;
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
        this.tracer = openTelemetry.getTracer(BraintrustBridge.INSTRUMENTATION_NAME);
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
        Span span =
                tracer.spanBuilder(InstrumentationSemConv.UNSET_LLM_SPAN_NAME)
                        .setSpanKind(SpanKind.CLIENT)
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
        Span span =
                tracer.spanBuilder(InstrumentationSemConv.UNSET_LLM_SPAN_NAME)
                        .setSpanKind(SpanKind.CLIENT)
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
        private final AtomicLong timeToFirstTokenNanos = new AtomicLong();
        private final SseResponseAccumulator accumulator =
                new SseResponseAccumulator(BraintrustJsonMapper.get());
        // A stream can report a failed generation in band, after the HTTP request itself has
        // succeeded. LangChain4j delivers those failures to the caller's own response handler and
        // then closes the transport normally, so onError below is never reached — retaining the
        // failure here is what stops onClose from finalizing a failed call as a successful span.
        @javax.annotation.Nullable private volatile String streamFailure;

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
                accumulateEvent(event);
                delegate.onEvent(event, context);
            }
        }

        @Override
        public void onEvent(ServerSentEvent event) {
            try (Scope ignored = span.makeCurrent()) {
                accumulateEvent(event);
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

        private void accumulateEvent(ServerSentEvent event) {
            String data = event.data();
            if (streamFailure == null) {
                streamFailure =
                        SseStreamAccumulator.streamFailure(
                                BraintrustJsonMapper.get(), event.event(), data);
            }
            if (data == null || data.isEmpty() || "[DONE]".equals(data)) return;
            if (timeToFirstTokenNanos.get() == 0L) {
                timeToFirstTokenNanos.compareAndExchange(0L, System.nanoTime() - startNanos);
            }
            accumulator.merge(data);
        }

        private void finalizeSpan() {
            String failure = streamFailure;
            if (failure != null) {
                // Recorded before tagging: a failed stream's body is often partial, and losing the
                // error status to a tagging problem is worse than losing the partial output.
                span.setStatus(StatusCode.ERROR, failure);
            }
            try {
                // Absent rather than zero: a stream that never delivered a payload has no first
                // token to time, and 0.0 would land in latency aggregates as a real measurement.
                long elapsed = timeToFirstTokenNanos.get();
                Long ttft = elapsed != 0L ? elapsed : null;
                String responseBody = accumulator.build();
                InstrumentationSemConv.tagLLMSpanResponse(
                        tracer, span, providerName, responseBody, ttft);
            } catch (Exception e) {
                log.debug("Failed to finalize streaming span", e);
            }
        }
    }
}
