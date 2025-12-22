package dev.braintrust.instrumentation.langchain;

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

class WrappedHttpClient implements HttpClient {
    private final Tracer tracer;
    private final HttpClient underlying;

    public WrappedHttpClient(OpenTelemetry openTelemetry, HttpClient underlying) {
        this.tracer = BraintrustTracing.getTracer(openTelemetry);
        this.underlying = underlying;
    }

    @Override
    public SuccessfulHttpResponse execute(HttpRequest request)
            throws HttpException, RuntimeException {
        Span span = tracer.spanBuilder("TODO").setSpanKind(SpanKind.CLIENT).startSpan();
        try (Scope scope = span.makeCurrent()) {
            tagSpan(span, request);
            var response = underlying.execute(request);
            tagSpan(span, response);
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
        Span span = tracer.spanBuilder("TODO").setSpanKind(SpanKind.CLIENT).startSpan();
        try (Scope scope = span.makeCurrent()) {
            tagSpan(span, request);
            underlying.execute(request, new WrappedServerSentEventListener(listener, span));
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
        Span span = tracer.spanBuilder("TODO").setSpanKind(SpanKind.CLIENT).startSpan();
        try {
            tagSpan(span, request);
            underlying.execute(request, parser, new WrappedServerSentEventListener(listener, span));
        } catch (Throwable t) {
            tagSpan(span, t);
            span.end();
            throw t;
        }
    }

    private static void tagSpan(Span span, HttpRequest request) {
        // TODO
    }

    private static void tagSpan(Span span, SuccessfulHttpResponse response) {
        // TODO
    }

    private static void tagSpan(Span span, Throwable t) {
        // TODO
    }

    /**
     * Wraps a ServerSentEventListener to properly end the span when streaming completes or errors.
     */
    private static class WrappedServerSentEventListener implements ServerSentEventListener {
        private final ServerSentEventListener delegate;
        private final Span span;

        WrappedServerSentEventListener(ServerSentEventListener delegate, Span span) {
            this.delegate = delegate;
            this.span = span;
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
            event.data();
        }

        @Override
        public void onError(Throwable error) {
            try {
                delegate.onError(error);
            } finally {
                tagSpan(span, error);
                span.end();
            }
        }

        @Override
        public void onClose() {
            try {
                delegate.onClose();
            } finally {
                // End span on successful completion
                span.end();
            }
        }
    }

    private record ProviderInfo(String provider, String endpoint) {}
}
