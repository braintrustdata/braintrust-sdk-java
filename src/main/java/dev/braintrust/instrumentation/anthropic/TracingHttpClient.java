package dev.braintrust.instrumentation.anthropic;

import com.anthropic.core.RequestOptions;
import com.anthropic.core.http.HttpClient;
import com.anthropic.core.http.HttpRequest;
import com.anthropic.core.http.HttpRequestBody;
import com.anthropic.core.http.HttpResponse;
import com.anthropic.helpers.BetaMessageAccumulator;
import com.anthropic.helpers.MessageAccumulator;
import com.anthropic.models.beta.messages.BetaRawMessageStreamEvent;
import com.anthropic.models.messages.RawMessageStreamEvent;
import dev.braintrust.instrumentation.InstrumentationSemConv;
import dev.braintrust.json.BraintrustJsonMapper;
import dev.braintrust.trace.BraintrustTracing;
import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.Tracer;
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
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;

@Slf4j
public class TracingHttpClient implements HttpClient {
    private final Tracer tracer;
    private final HttpClient underlying;

    public TracingHttpClient(OpenTelemetry openTelemetry, HttpClient underlying) {
        this.tracer = BraintrustTracing.getTracer(openTelemetry);
        this.underlying = underlying;
    }

    @Override
    public void close() {
        underlying.close();
    }

    @Override
    public @NonNull HttpResponse execute(
            @NonNull HttpRequest httpRequest, @NonNull RequestOptions requestOptions) {
        var span = tracer.spanBuilder(InstrumentationSemConv.UNSET_LLM_SPAN_NAME).startSpan();
        try (var ignored = span.makeCurrent()) {
            var bufferedRequest = bufferRequestBody(httpRequest);

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

            boolean isBeta = !bufferedRequest.headers().values("anthropic-beta").isEmpty();
            var response = underlying.execute(bufferedRequest, requestOptions);
            return new TeeingStreamHttpResponse(response, span, isBeta);
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
        private final boolean isBeta;
        private final long spanStartNanos = System.nanoTime();
        private final AtomicLong timeToFirstTokenNanos = new AtomicLong();
        private final ByteArrayOutputStream teeBuffer = new ByteArrayOutputStream();
        private final InputStream teeStream;

        TeeingStreamHttpResponse(HttpResponse delegate, Span span, boolean isBeta) {
            this.delegate = delegate;
            this.span = span;
            this.isBeta = isBeta;
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
                tagSpanFromBuffer(span, bytes, timeToFirstTokenNanos.get(), isBeta);
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

    private static void tagSpanFromBuffer(
            Span span, byte[] bytes, Long timeToFirstTokenNanos, boolean isBeta) {
        if (bytes.length == 0) return;
        try {
            String firstLine = firstNonEmptyLine(bytes);
            // Anthropic SSE starts with "event: message_start\ndata: ..." so we detect
            // either prefix. OpenAI SSE starts directly with "data:".
            boolean isSse =
                    firstLine != null
                            && (firstLine.startsWith("data:") || firstLine.startsWith("event:"));
            if (isSse) {
                tagSpanFromSseBytes(span, bytes, timeToFirstTokenNanos, isBeta);
            } else {
                // Non-streaming: plain Message JSON — pass it whole, no time_to_first_token
                InstrumentationSemConv.tagLLMSpanResponse(
                        span,
                        InstrumentationSemConv.PROVIDER_NAME_ANTHROPIC,
                        new String(bytes, StandardCharsets.UTF_8));
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
            Span span, byte[] sseBytes, Long timeToFirstTokenNanos, boolean isBeta) {
        try {
            var mapper = BraintrustJsonMapper.get();
            var reader =
                    new BufferedReader(
                            new InputStreamReader(
                                    new ByteArrayInputStream(sseBytes), StandardCharsets.UTF_8));
            String assembledMessageJson;
            if (isBeta) {
                var accumulator = BetaMessageAccumulator.create();
                String line;
                while ((line = reader.readLine()) != null) {
                    if (!line.startsWith("data:")) continue;
                    String data = line.substring("data:".length()).strip();
                    if (data.isEmpty()) continue;
                    try {
                        accumulator.accumulate(
                                mapper.readValue(data, BetaRawMessageStreamEvent.class));
                    } catch (Exception ignored) {
                        // skip unrecognized event types (e.g. ping)
                    }
                }
                assembledMessageJson = BraintrustJsonMapper.toJson(accumulator.message());
            } else {
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
                assembledMessageJson = BraintrustJsonMapper.toJson(accumulator.message());
            }
            InstrumentationSemConv.tagLLMSpanResponse(
                    span,
                    InstrumentationSemConv.PROVIDER_NAME_ANTHROPIC,
                    assembledMessageJson,
                    timeToFirstTokenNanos);
        } catch (Exception e) {
            log.error("Could not parse Anthropic SSE buffer to tag streaming span output", e);
        }
    }

    @Override
    public @NonNull CompletableFuture<HttpResponse> executeAsync(
            @NonNull HttpRequest httpRequest, @NonNull RequestOptions requestOptions) {
        return underlying.executeAsync(httpRequest, requestOptions);
    }
}
