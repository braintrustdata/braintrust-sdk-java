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
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Scope;
import java.net.URI;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
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
                    tracer,
                    span,
                    options.providerName(),
                    response.body(),
                    null,
                    response.headers());
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
                    span,
                    options.providerName(),
                    baseUrl,
                    pathSegments,
                    "POST",
                    request.body(),
                    null,
                    request.headers());
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
        // before the model has produced anything. firstPayloadNanos is a fallback for streams
        // whose shape is not recognized at all; sawRecognizedShape is what keeps that fallback
        // from firing on a recognized stream that simply never produced output, where the honest
        // answer is that there was no first token.
        private final AtomicLong firstOutputNanos = new AtomicLong();
        private final AtomicLong firstPayloadNanos = new AtomicLong();
        private volatile boolean sawRecognizedShape;
        // Handles both endpoints this module instruments: chat-completions chunk streams and
        // Responses API (`/v1/responses`) event streams.
        private final SseStreamAccumulator accumulator =
                new SseStreamAccumulator(BraintrustJsonMapper.get());
        // A stream can report a failed generation in band, after the HTTP request itself has
        // succeeded. LangChain4j delivers those failures to the caller's own response handler and
        // then closes the transport normally, so onError below is never reached — retaining the
        // failure here is what stops onClose from finalizing a failed call as a successful span.
        @javax.annotation.Nullable private volatile String streamFailure;

        // onOpen is the only point the SSE transport exposes response headers, but the body is
        // not assembled until the stream closes — so they are copied aside here and handed to the
        // semconv layer together with the body in finalizeSpan, potentially from another thread.
        // A copy rather than the client's own map: we outlive the callback that handed it to us.
        // Starts empty, which is also the right answer for a stream that errors before it opens.
        private final Map<String, List<String>> responseHeaders = new ConcurrentHashMap<>();

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
                captureResponseHeaders(response);
                delegate.onOpen(response);
            }
        }

        /**
         * Copies the response headers aside, entry by entry rather than in bulk: {@link
         * ConcurrentHashMap} rejects null keys and values, and header maps from some HttpClient
         * implementations carry a null key for the status line. Best-effort throughout — throwing
         * here would break the caller's stream for the sake of a span attribute.
         */
        private void captureResponseHeaders(SuccessfulHttpResponse response) {
            try {
                Map<String, List<String>> headers = response.headers();
                if (headers == null) {
                    return;
                }
                headers.forEach(
                        (name, values) -> {
                            if (name != null && values != null) {
                                responseHeaders.put(name, values);
                            }
                        });
            } catch (Exception e) {
                log.debug("could not capture response headers", e);
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
            firstPayloadNanos.compareAndExchange(0L, System.nanoTime() - startNanos);
            // Only classify until the first output is seen; afterwards this is a single volatile
            // read per chunk.
            if (firstOutputNanos.get() == 0L) {
                var kind = SseStreamAccumulator.classify(BraintrustJsonMapper.get(), data);
                if (kind != SseStreamAccumulator.PayloadKind.UNRECOGNIZED) {
                    sawRecognizedShape = true;
                }
                if (kind == SseStreamAccumulator.PayloadKind.OUTPUT) {
                    firstOutputNanos.compareAndExchange(0L, System.nanoTime() - startNanos);
                }
            }
            accumulator.merge(data);
        }

        /**
         * Source for {@code time_to_first_token}, or {@code null} when the stream produced no first
         * token to time.
         *
         * <p>The first generated output when there was one. Otherwise the first payload, but only
         * for a stream whose shape was never recognized — there the timestamp is a slightly early
         * approximation, which beats dropping a metric the spec requires for streaming spans. A
         * recognized stream that produced no output (one that failed before generating, or
         * completed empty) reports nothing: its first payload is lifecycle metadata, and publishing
         * that as a token latency would silently corrupt latency aggregates.
         */
        @javax.annotation.Nullable
        private Long timeToFirstTokenNanos() {
            long output = firstOutputNanos.get();
            if (output != 0L) {
                return output;
            }
            if (sawRecognizedShape) {
                return null;
            }
            long payload = firstPayloadNanos.get();
            return payload != 0L ? payload : null;
        }

        private void finalizeSpan() {
            String failure = streamFailure;
            if (failure != null) {
                // Recorded before tagging: a failed stream's body is often partial, and losing the
                // error status to a tagging problem is worse than losing the partial output.
                span.setStatus(StatusCode.ERROR, failure);
            }
            try {
                Long ttft = timeToFirstTokenNanos();
                String responseBody = accumulator.build();
                InstrumentationSemConv.tagLLMSpanResponse(
                        tracer, span, providerName, responseBody, ttft, responseHeaders);
            } catch (Exception e) {
                log.debug("Failed to finalize streaming span", e);
            }
        }
    }
}
