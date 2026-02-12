package dev.braintrust.instrumentation.openai;

import com.openai.core.RequestOptions;
import com.openai.core.http.HttpClient;
import com.openai.core.http.HttpRequest;
import com.openai.core.http.HttpRequestBody;
import com.openai.core.http.HttpResponse;
import com.openai.helpers.ChatCompletionAccumulator;
import com.openai.models.chat.completions.ChatCompletionChunk;
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
            // Buffer the request body so we can (a) read its bytes for the span attribute and
            // (b) supply a fresh, repeatable body to the underlying client — avoiding any
            // one-shot stream consumption issue.
            var bufferedRequest = bufferRequestBody(httpRequest);

            String inputJson =
                    bufferedRequest.body() != null
                            ? readBodyAsString(bufferedRequest.body())
                            : null;

            InstrumentationSemConv.tagLLMSpanRequest(
                    span,
                    InstrumentationSemConv.PROVIDER_NAME_OPENAI,
                    bufferedRequest.baseUrl(),
                    bufferedRequest.pathSegments(),
                    bufferedRequest.method().name(),
                    inputJson);
            var response = underlying.execute(bufferedRequest, requestOptions);
            // Always tee the response body. onStreamClosed() detects whether the collected
            // bytes are SSE or plain JSON and tags the span accordingly.
            return new TeeingStreamHttpResponse(response, span);
        } catch (Exception e) {
            InstrumentationSemConv.tagLLMSpanResponse(span, e);
            span.end();
            throw e;
        }
    }

    @Override
    public @NonNull CompletableFuture<HttpResponse> executeAsync(
            @NonNull HttpRequest httpRequest, @NonNull RequestOptions requestOptions) {
        var span = tracer.spanBuilder(InstrumentationSemConv.UNSET_LLM_SPAN_NAME).startSpan();
        try {
            var bufferedRequest = bufferRequestBody(httpRequest);
            String inputJson =
                    bufferedRequest.body() != null
                            ? readBodyAsString(bufferedRequest.body())
                            : null;
            InstrumentationSemConv.tagLLMSpanRequest(
                    span,
                    InstrumentationSemConv.PROVIDER_NAME_OPENAI,
                    bufferedRequest.baseUrl(),
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

    /**
     * Captures the request body into an in-memory byte array and returns a new {@link HttpRequest}
     * backed by those bytes. The original body stream is consumed exactly once here; the returned
     * request uses a {@link HttpRequestBody} that is always {@link HttpRequestBody#repeatable()
     * repeatable}, so the underlying client can read it safely (including on retry).
     *
     * <p>If the original body is {@code null} or already in-memory (repeatable), the cost is just
     * one extra copy of the bytes — acceptable for observability.
     */
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
        // Body was already buffered by bufferRequestBody, so writeTo is safe to call again.
        var baos = new ByteArrayOutputStream((int) Math.max(body.contentLength(), 0));
        body.writeTo(baos);
        return baos.toString(StandardCharsets.UTF_8);
    }

    /**
     * Tags the span from bytes collected by {@link TeeingStreamHttpResponse}. Auto-detects whether
     * the bytes are an SSE stream (first non-empty line starts with {@code "data: "}) or a plain
     * JSON response, and parses accordingly.
     */
    private static void tagSpanFromBuffer(Span span, byte[] bytes, Long timeToFirstTokenNanos) {
        if (bytes.length == 0) return;
        try {
            String firstLine = firstNonEmptyLine(bytes);
            if (firstLine != null && firstLine.startsWith("data:")) {
                tagSpanFromSseBytes(span, bytes, timeToFirstTokenNanos);
            } else {
                InstrumentationSemConv.tagLLMSpanResponse(
                        span,
                        InstrumentationSemConv.PROVIDER_NAME_OPENAI,
                        new String(bytes, StandardCharsets.UTF_8));
            }
        } catch (Exception e) {
            log.error("Could not tag span from response buffer", e);
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
     * Parses SSE wire bytes, feeds each {@code data:} chunk through {@link
     * ChatCompletionAccumulator}, then tags the span with the reassembled output JSON.
     */
    private static void tagSpanFromSseBytes(
            Span span, byte[] sseBytes, Long timeToFirstTokenNanos) {
        try {
            var accumulator = ChatCompletionAccumulator.create();
            var reader =
                    new BufferedReader(
                            new InputStreamReader(
                                    new ByteArrayInputStream(sseBytes), StandardCharsets.UTF_8));
            String line;
            while ((line = reader.readLine()) != null) {
                if (!line.startsWith("data:")) continue;
                String data = line.substring("data:".length()).strip();
                if (data.isEmpty() || data.equals("[DONE]")) continue;
                ChatCompletionChunk chunk =
                        BraintrustJsonMapper.get().readValue(data, ChatCompletionChunk.class);
                accumulator.accumulate(chunk);
            }
            var chatCompletion = accumulator.chatCompletion();
            InstrumentationSemConv.tagLLMSpanResponse(
                    span,
                    InstrumentationSemConv.PROVIDER_NAME_OPENAI,
                    BraintrustJsonMapper.toJson(chatCompletion),
                    timeToFirstTokenNanos);
        } catch (Exception e) {
            log.error("Could not parse SSE buffer to tag streaming span output", e);
        }
    }

    /**
     * {@link HttpResponse} wrapper for streaming (SSE) responses. Its {@link #body()} returns a tee
     * {@link InputStream} that copies every byte the caller reads into an in-memory buffer. When
     * the stream is fully consumed and {@link #close()} is called, the accumulated bytes are
     * available via {@link #collectedBytes()} for span tagging.
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

        /** Called back by {@link TeeInputStream} when the stream is fully drained or closed. */
        private void onStreamClosed() {
            try {
                // Synchronize on teeBuffer to ensure any write() that was in-flight on a
                // concurrent read thread has fully completed before we snapshot the bytes.
                byte[] bytes;
                synchronized (teeBuffer) {
                    bytes = teeBuffer.toByteArray();
                }
                tagSpanFromBuffer(span, bytes, timeToFirstTokenNanos.get());
            } finally {
                span.end();
            }
        }

        byte[] collectedBytes() {
            return teeBuffer.toByteArray();
        }

        @Override
        public int statusCode() {
            return delegate.statusCode();
        }

        @Override
        public com.openai.core.http.Headers headers() {
            return delegate.headers();
        }

        @Override
        public InputStream body() {
            return teeStream;
        }

        @Override
        public void close() {
            try {
                teeStream.close(); // triggers onStreamClosed if not already fired (e.g. abandoned
                // stream)
            } catch (java.io.IOException ignored) {
            }
            delegate.close();
        }
    }

    /**
     * An {@link InputStream} that copies every byte read from {@code source} into {@code sink}, and
     * fires {@code onClose} exactly once when the stream reaches EOF or is explicitly closed.
     */
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
}
