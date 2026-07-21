package dev.braintrust.instrumentation.anthropic.v2_2_0;

import com.anthropic.core.RequestOptions;
import com.anthropic.core.http.HttpClient;
import com.anthropic.core.http.HttpRequest;
import com.anthropic.core.http.HttpRequestBody;
import com.anthropic.core.http.HttpResponse;
import com.anthropic.helpers.MessageAccumulator;
import com.anthropic.models.messages.RawMessageStreamEvent;
import dev.braintrust.bootstrap.BraintrustBridge;
import dev.braintrust.instrumentation.InstrumentationSemConv;
import dev.braintrust.json.BraintrustJsonMapper;
import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanContext;
import io.opentelemetry.api.trace.TraceFlags;
import io.opentelemetry.api.trace.TraceState;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Context;
import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class TracingHttpClient implements HttpClient {
    private final Tracer tracer;
    private final HttpClient underlying;

    public TracingHttpClient(OpenTelemetry openTelemetry, HttpClient underlying) {
        this.tracer = openTelemetry.getTracer(BraintrustBridge.INSTRUMENTATION_NAME);
        this.underlying = underlying;
    }

    /**
     * Starts the LLM span. anthropic-java (and frameworks like Spring AI 2.x) dispatch
     * async/streaming requests on executors where the caller's thread-local context is lost — which
     * would orphan the span. {@link ContextCapturingProxy} captures the caller's context at the
     * service-call boundary and threads it through as {@code headerContext}; when that is absent we
     * fall back to whatever context is current on this thread (correct for synchronous calls). We
     * deliberately do <em>not</em> fall back to the context that was current when the client was
     * instrumented — a long-lived client wrapped inside some unrelated span would otherwise parent
     * every future request to that stale span.
     */
    private Span startLlmSpan(@Nullable Context headerContext) {
        Context parent = headerContext != null ? headerContext : Context.current();
        return tracer.spanBuilder(InstrumentationSemConv.UNSET_LLM_SPAN_NAME)
                .setParent(parent)
                .startSpan();
    }

    /**
     * Extracts the caller context injected by {@link ContextCapturingProxy} (if any) and strips the
     * internal header from the outgoing request.
     */
    private static ExtractedRequest extractCallerContext(HttpRequest request) {
        var values = request.headers().values(ContextCapturingProxy.CONTEXT_HEADER);
        if (values.isEmpty()) {
            return new ExtractedRequest(request, null);
        }
        Context context = contextFromTraceparent(values.get(0));
        HttpRequest stripped =
                request.toBuilder()
                        .replaceHeaders(ContextCapturingProxy.CONTEXT_HEADER, java.util.List.of())
                        .build();
        return new ExtractedRequest(stripped, context);
    }

    /** Parses a W3C {@code traceparent} value ({@code 00-<traceId>-<spanId>-<flags>}). */
    @Nullable
    private static Context contextFromTraceparent(String traceparent) {
        try {
            String[] parts = traceparent.split("-");
            if (parts.length < 4) {
                return null;
            }
            SpanContext spanContext =
                    SpanContext.create(
                            parts[1],
                            parts[2],
                            TraceFlags.fromHex(parts[3], 0),
                            TraceState.getDefault());
            if (!spanContext.isValid()) {
                return null;
            }
            return Context.root().with(Span.wrap(spanContext));
        } catch (Exception e) {
            log.debug("invalid context header value: {}", traceparent, e);
            return null;
        }
    }

    private record ExtractedRequest(HttpRequest request, @Nullable Context callerContext) {}

    @Override
    public void close() {
        underlying.close();
    }

    @Override
    public @Nonnull HttpResponse execute(
            @Nonnull HttpRequest httpRequest, @Nonnull RequestOptions requestOptions) {
        var extracted = extractCallerContext(httpRequest);
        var span = startLlmSpan(extracted.callerContext());
        try (var ignored = span.makeCurrent()) {
            var bufferedRequest = bufferRequestBody(extracted.request());

            String inputJson =
                    bufferedRequest.body() != null
                            ? readBodyAsString(bufferedRequest.body())
                            : null;

            InstrumentationSemConv.tagLLMSpanRequest(
                    span,
                    InstrumentationSemConv.PROVIDER_NAME_ANTHROPIC,
                    bufferedRequest.baseUrl() != null ? bufferedRequest.baseUrl() : "",
                    bufferedRequest.pathSegments(),
                    bufferedRequest.method().name(),
                    inputJson);

            var response = underlying.execute(bufferedRequest, requestOptions);
            return new TeeingStreamHttpResponse(response, span);
        } catch (Exception e) {
            InstrumentationSemConv.tagLLMSpanResponse(span, e);
            span.end();
            throw e;
        }
    }

    @Override
    public @Nonnull CompletableFuture<HttpResponse> executeAsync(
            @Nonnull HttpRequest httpRequest, @Nonnull RequestOptions requestOptions) {
        var extracted = extractCallerContext(httpRequest);
        var span = startLlmSpan(extracted.callerContext());
        try {
            var bufferedRequest = bufferRequestBody(extracted.request());
            String inputJson =
                    bufferedRequest.body() != null
                            ? readBodyAsString(bufferedRequest.body())
                            : null;
            InstrumentationSemConv.tagLLMSpanRequest(
                    span,
                    InstrumentationSemConv.PROVIDER_NAME_ANTHROPIC,
                    bufferedRequest.baseUrl() != null ? bufferedRequest.baseUrl() : "",
                    bufferedRequest.pathSegments(),
                    bufferedRequest.method().name(),
                    inputJson);
            return underlying
                    .executeAsync(bufferedRequest, requestOptions)
                    .thenApply(
                            response -> (HttpResponse) new TeeingStreamHttpResponse(response, span))
                    .whenComplete(
                            (response, t) -> {
                                if (t != null) {
                                    // this means the future itself failed
                                    InstrumentationSemConv.tagLLMSpanResponse(span, t);
                                    span.end();
                                }
                            });
        } catch (Exception e) {
            InstrumentationSemConv.tagLLMSpanResponse(span, e);
            span.end();
            throw e;
        }
    }

    // -------------------------------------------------------------------------
    // Request buffering — identical pattern to OpenAI TracingHttpClient
    // -------------------------------------------------------------------------

    private static HttpRequest bufferRequestBody(HttpRequest request) {
        HttpRequestBody originalBody = request.body();
        if (originalBody == null) {
            return request;
        }
        var baos = new ByteArrayOutputStream();
        originalBody.writeTo(baos);
        byte[] bytes = baos.toByteArray();
        String contentType = originalBody.contentType();

        HttpRequestBody bufferedBody =
                new HttpRequestBody() {
                    @Override
                    public void writeTo(OutputStream outputStream) {
                        try {
                            outputStream.write(bytes);
                        } catch (java.io.IOException e) {
                            throw new RuntimeException(e);
                        }
                    }

                    @Override
                    public String contentType() {
                        return contentType;
                    }

                    @Override
                    public long contentLength() {
                        return bytes.length;
                    }

                    @Override
                    public boolean repeatable() {
                        return true;
                    }

                    @Override
                    public void close() {}
                };

        return request.toBuilder().body(bufferedBody).build();
    }

    private static String readBodyAsString(HttpRequestBody body) {
        var baos = new ByteArrayOutputStream((int) Math.max(body.contentLength(), 0));
        body.writeTo(baos);
        return baos.toString(StandardCharsets.UTF_8);
    }

    // -------------------------------------------------------------------------
    // Response tee — identical pattern to OpenAI TracingHttpClient
    // -------------------------------------------------------------------------

    /**
     * Tees the response body so bytes are accumulated as the caller reads, then on close tags the
     * span by auto-detecting SSE vs plain JSON from the first non-empty line.
     */
    private static final class TeeingStreamHttpResponse implements HttpResponse {
        private final HttpResponse delegate;
        private final Span span;
        private final long spanStartNanos = System.nanoTime();
        private final AtomicLong timeToFirstTokenNanos = new AtomicLong();
        private final ByteArrayOutputStream teeBuffer = new ByteArrayOutputStream();
        private final InputStream teeStream;

        TeeingStreamHttpResponse(HttpResponse delegate, Span span) {
            this.delegate = delegate;
            this.span = span;
            this.teeStream =
                    new TeeInputStream(
                            delegate.body(), teeBuffer, this::onFirstByte, this::onStreamClosed);
        }

        private void onFirstByte() {
            timeToFirstTokenNanos.set(System.nanoTime() - spanStartNanos);
        }

        private void onStreamClosed() {
            try {
                byte[] bytes;
                synchronized (teeBuffer) {
                    bytes = teeBuffer.toByteArray();
                }
                tagSpanFromBuffer(span, bytes, timeToFirstTokenNanos.get());
            } finally {
                span.end();
            }
        }

        @Override
        public int statusCode() {
            return delegate.statusCode();
        }

        @Override
        public com.anthropic.core.http.Headers headers() {
            return delegate.headers();
        }

        @Override
        public InputStream body() {
            return teeStream;
        }

        @Override
        public void close() {
            try {
                teeStream.close();
            } catch (java.io.IOException ignored) {
            }
            delegate.close();
        }
    }

    private static final class TeeInputStream extends InputStream {
        private final InputStream source;
        private final OutputStream sink;
        private final Runnable onFirstByte;
        private final Runnable onClose;
        private final AtomicBoolean firstByteSeen = new AtomicBoolean(false);
        private final AtomicBoolean closed = new AtomicBoolean(false);

        TeeInputStream(
                InputStream source, OutputStream sink, Runnable onFirstByte, Runnable onClose) {
            this.source = source;
            this.sink = sink;
            this.onFirstByte = onFirstByte;
            this.onClose = onClose;
        }

        @Override
        public int read() throws java.io.IOException {
            int b = source.read();
            if (b == -1) {
                notifyClosed();
            } else {
                notifyFirstByte();
                sink.write(b);
            }
            return b;
        }

        @Override
        public int read(byte[] buf, int off, int len) throws java.io.IOException {
            int n = source.read(buf, off, len);
            if (n == -1) {
                notifyClosed();
            } else {
                notifyFirstByte();
                sink.write(buf, off, n);
            }
            return n;
        }

        @Override
        public void close() throws java.io.IOException {
            notifyClosed();
            source.close();
        }

        private void notifyFirstByte() {
            if (!firstByteSeen.compareAndExchange(false, true)) {
                onFirstByte.run();
            }
        }

        private void notifyClosed() {
            if (!closed.compareAndExchange(false, true)) {
                onClose.run();
            }
        }
    }

    // -------------------------------------------------------------------------
    // Span tagging from buffered bytes
    // -------------------------------------------------------------------------

    private static void tagSpanFromBuffer(Span span, byte[] bytes, Long timeToFirstTokenNanos) {
        if (bytes.length == 0) return;
        try {
            String firstLine = firstNonEmptyLine(bytes);
            // Anthropic SSE starts with "event: message_start\ndata: ..." so we detect
            // either prefix. OpenAI SSE starts directly with "data:".
            boolean isSse =
                    firstLine != null
                            && (firstLine.startsWith("data:") || firstLine.startsWith("event:"));
            if (isSse) {
                tagSpanFromSseBytes(span, bytes, timeToFirstTokenNanos);
            } else {
                // Non-streaming: plain Message JSON — pass it whole, no time_to_first_token
                InstrumentationSemConv.tagLLMSpanResponse(
                        span,
                        InstrumentationSemConv.PROVIDER_NAME_ANTHROPIC,
                        new String(bytes, StandardCharsets.UTF_8),
                        null);
            }
        } catch (Exception e) {
            log.error("Could not tag span from Anthropic response buffer", e);
        }
    }

    private static String firstNonEmptyLine(byte[] bytes) {
        int start = 0;
        for (int i = 0; i <= bytes.length; i++) {
            if (i == bytes.length || bytes[i] == '\n') {
                String line = new String(bytes, start, i - start, StandardCharsets.UTF_8).strip();
                if (!line.isEmpty()) return line;
                start = i + 1;
            }
        }
        return null;
    }

    /**
     * Anthropic SSE wire format has named events:
     *
     * <pre>
     * event: message_start
     * data: {"type":"message_start","message":{...}}
     *
     * event: content_block_delta
     * data: {"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"Hi"}}
     * </pre>
     *
     * We only need the {@code data:} lines — the event name is redundant with the {@code type}
     * field inside the JSON. Feed each data payload to {@link MessageAccumulator} and serialize the
     * assembled {@link com.anthropic.models.messages.Message} for the span.
     */
    private static void tagSpanFromSseBytes(
            Span span, byte[] sseBytes, Long timeToFirstTokenNanos) {
        try {
            var mapper = BraintrustJsonMapper.get();
            var reader =
                    new BufferedReader(
                            new InputStreamReader(
                                    new ByteArrayInputStream(sseBytes), StandardCharsets.UTF_8));
            var accumulator = MessageAccumulator.create();
            String line;
            while ((line = reader.readLine()) != null) {
                if (!line.startsWith("data:")) continue;
                String data = line.substring("data:".length()).strip();
                if (data.isEmpty()) continue;
                try {
                    accumulator.accumulate(mapper.readValue(data, RawMessageStreamEvent.class));
                } catch (Exception ignored) {
                    // skip unrecognized event types (e.g. ping)
                }
            }
            String assembledMessageJson = BraintrustJsonMapper.toJson(accumulator.message());
            InstrumentationSemConv.tagLLMSpanResponse(
                    span,
                    InstrumentationSemConv.PROVIDER_NAME_ANTHROPIC,
                    assembledMessageJson,
                    timeToFirstTokenNanos);
        } catch (Exception e) {
            log.error("Could not parse Anthropic SSE buffer to tag streaming span output", e);
        }
    }
}
