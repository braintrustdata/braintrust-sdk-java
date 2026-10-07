package dev.braintrust.instrumentation.openai.v2_15_0;

import com.fasterxml.jackson.databind.json.JsonMapper;
import com.openai.core.ObjectMappers;
import com.openai.core.RequestOptions;
import com.openai.core.http.*;
import dev.braintrust.instrumentation.InstrumentationSemConv;
import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanContext;
import io.opentelemetry.api.trace.TraceFlags;
import io.opentelemetry.api.trace.TraceState;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Context;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import javax.annotation.Nullable;
import lombok.NonNull;
import lombok.extern.slf4j.Slf4j;

@Slf4j
class TracingHttpClient implements HttpClient {
    private static final JsonMapper JSON_MAPPER = ObjectMappers.jsonMapper();
    private final Tracer tracer;
    private final HttpClient underlying;

    public TracingHttpClient(OpenTelemetry openTelemetry, HttpClient underlying) {
        this(
                openTelemetry.getTracer(
                        BraintrustOpenAI.INSTRUMENTATION_NAME,
                        BraintrustOpenAI.INSTRUMENTATION_VERSION),
                underlying);
    }

    TracingHttpClient(Tracer tracer, HttpClient underlying) {
        this.tracer = tracer;
        this.underlying = underlying;
    }

    TracingHttpClient withTracer(Tracer tracer) {
        return this.tracer == tracer ? this : new TracingHttpClient(tracer, underlying);
    }

    /**
     * Starts a request span. openai-java dispatches async/streaming requests through
     * CompletableFuture continuations on the common pool (and frameworks like Spring AI 2.x on
     * their own executors), where the caller's thread-local context is lost — which would orphan
     * the span. {@link ContextCapturingProxy} captures the caller's context at the service-call
     * boundary and threads it through as {@code headerContext}; when that is absent we fall back to
     * whatever context is current on this thread (correct for synchronous calls). We deliberately
     * do <em>not</em> fall back to the context that was current when the client was instrumented —
     * a long-lived client wrapped inside some unrelated span would otherwise parent every future
     * request to that stale span.
     */
    private Span startSpan(String name, @Nullable Context headerContext) {
        Context parent = headerContext != null ? headerContext : Context.current();
        return tracer.spanBuilder(name).setParent(parent).startSpan();
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
                        .replaceHeaders(ContextCapturingProxy.CONTEXT_HEADER, List.of())
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
    public @NonNull HttpResponse execute(
            @NonNull HttpRequest httpRequest, @NonNull RequestOptions requestOptions) {
        var extracted = extractCallerContext(httpRequest);
        if (!isLlmRequest(extracted.request())) {
            return executeHttp(extracted, requestOptions);
        }
        var span = startSpan(InstrumentationSemConv.UNSET_LLM_SPAN_NAME, extracted.callerContext());
        try (var ignored = span.makeCurrent()) {
            // Only JSON bodies are buffered for tagging. Multipart and binary bodies must reach
            // the transport untouched, including one-shot upload streams.
            boolean jsonBody = hasJsonBody(extracted.request());
            var bufferedRequest =
                    jsonBody ? bufferRequestBody(extracted.request()) : extracted.request();

            String inputJson = jsonBody ? readBodyAsString(bufferedRequest.body()) : null;

            InstrumentationSemConv.tagLLMSpanRequest(
                    span,
                    InstrumentationSemConv.PROVIDER_NAME_OPENAI,
                    bufferedRequest.baseUrl() != null ? bufferedRequest.baseUrl() : "",
                    bufferedRequest.pathSegments(),
                    bufferedRequest.method().name(),
                    inputJson,
                    null,
                    headersAsMap(bufferedRequest.headers()));
            var response = underlying.execute(bufferedRequest, requestOptions);
            // The wrapper tags JSON/SSE responses and tracks binary response lifecycle without
            // buffering or parsing their payloads.
            return new TeeingStreamHttpResponse(response, span, tracer);
        } catch (Exception e) {
            InstrumentationSemConv.tagLLMSpanResponse(span, e);
            span.end();
            throw e;
        }
    }

    @Override
    public @NonNull CompletableFuture<HttpResponse> executeAsync(
            @NonNull HttpRequest httpRequest, @NonNull RequestOptions requestOptions) {
        var extracted = extractCallerContext(httpRequest);
        if (!isLlmRequest(extracted.request())) {
            return executeHttpAsync(extracted, requestOptions);
        }
        var span = startSpan(InstrumentationSemConv.UNSET_LLM_SPAN_NAME, extracted.callerContext());
        try {
            boolean jsonBody = hasJsonBody(extracted.request());
            var bufferedRequest =
                    jsonBody ? bufferRequestBody(extracted.request()) : extracted.request();
            String inputJson = jsonBody ? readBodyAsString(bufferedRequest.body()) : null;
            InstrumentationSemConv.tagLLMSpanRequest(
                    span,
                    InstrumentationSemConv.PROVIDER_NAME_OPENAI,
                    bufferedRequest.baseUrl() != null ? bufferedRequest.baseUrl() : "",
                    bufferedRequest.pathSegments(),
                    bufferedRequest.method().name(),
                    inputJson,
                    null,
                    headersAsMap(bufferedRequest.headers()));
            return underlying
                    .executeAsync(bufferedRequest, requestOptions)
                    .thenApply(
                            response ->
                                    (HttpResponse)
                                            new TeeingStreamHttpResponse(response, span, tracer))
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
     * Model inference endpoints get the full LLM treatment. Single trailing segments cover
     * completions (chat and legacy), embeddings, responses, and moderations; media inference
     * requires an explicit trailing route pair. A {@code v1} prefix or an Azure {@code
     * openai/deployments/<name>} prefix doesn't matter. Only POST is classified as inference;
     * retrieve/list calls and other resources (files, batches, models, vector stores, ...) get a
     * plain {@code openai.http} span with the body left untouched.
     */
    private static final Set<String> LLM_ENDPOINTS =
            Set.of("completions", "embeddings", "responses", "moderations");

    private static boolean isLlmRequest(HttpRequest request) {
        var path = request.pathSegments();
        if (request.method() != HttpMethod.POST || path.isEmpty()) {
            return false;
        }
        String endpoint = path.get(path.size() - 1);
        if (LLM_ENDPOINTS.contains(endpoint)) {
            return true;
        }
        if (path.size() < 2) {
            return false;
        }
        return switch (path.get(path.size() - 2)) {
            case "images" ->
                    endpoint.equals("generations")
                            || endpoint.equals("edits")
                            || endpoint.equals("variations");
            case "audio" ->
                    endpoint.equals("speech")
                            || endpoint.equals("transcriptions")
                            || endpoint.equals("translations");
            default -> false;
        };
    }

    private HttpResponse executeHttp(ExtractedRequest extracted, RequestOptions requestOptions) {
        var span = startSpan("openai.http", extracted.callerContext());
        try (var ignored = span.makeCurrent()) {
            var response = underlying.execute(extracted.request(), requestOptions);
            InstrumentationSemConv.tagHttpSpanResponse(span, response.statusCode());
            return response;
        } catch (Exception e) {
            InstrumentationSemConv.tagLLMSpanResponse(span, e);
            throw e;
        } finally {
            span.end();
        }
    }

    private CompletableFuture<HttpResponse> executeHttpAsync(
            ExtractedRequest extracted, RequestOptions requestOptions) {
        var span = startSpan("openai.http", extracted.callerContext());
        try (var ignored = span.makeCurrent()) {
            return underlying
                    .executeAsync(extracted.request(), requestOptions)
                    .whenComplete(
                            (response, error) -> {
                                try {
                                    if (error != null) {
                                        InstrumentationSemConv.tagLLMSpanResponse(span, error);
                                    } else {
                                        InstrumentationSemConv.tagHttpSpanResponse(
                                                span, response.statusCode());
                                    }
                                } finally {
                                    span.end();
                                }
                            })
                    // Isolate cleanup from cancellation of the caller's future.
                    .copy();
        } catch (Exception e) {
            InstrumentationSemConv.tagLLMSpanResponse(span, e);
            span.end();
            throw e;
        }
    }

    /** Only declared JSON bodies may be consumed for request tagging. */
    private static boolean hasJsonBody(HttpRequest request) {
        return request.body() != null
                && matchesMediaType(request.body().contentType(), "application/json");
    }

    private static boolean matchesMediaType(@Nullable String contentType, String mediaType) {
        if (contentType == null) {
            return false;
        }
        int end = contentType.indexOf(';');
        if (end < 0) {
            end = contentType.length();
        }
        int start = 0;
        while (start < end && contentType.charAt(start) <= ' ') {
            start++;
        }
        while (end > start && contentType.charAt(end - 1) <= ' ') {
            end--;
        }
        return end - start == mediaType.length()
                && contentType.regionMatches(true, start, mediaType, 0, mediaType.length());
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
     * The response body as a single JSON document, plus the timing that goes with it. Reassembly is
     * kept separate from tagging so that {@link TeeingStreamHttpResponse#onStreamClosed} can hand
     * the semconv layer everything about the response in one call — a body we couldn't produce
     * becomes a null {@code body} rather than a skipped tagging call, which is what keeps the
     * response headers from being lost on an empty or unrecognized body.
     */
    /**
     * Adapts openai-java's {@link Headers} to the vendor-neutral shape {@link
     * InstrumentationSemConv} consumes. Returns an empty map on failure so a header-shape change
     * can never take down the tagging that follows it.
     */
    private static Map<String, List<String>> headersAsMap(@Nullable Headers headers) {
        if (headers == null) {
            return Map.of();
        }
        try {
            var map = new HashMap<String, List<String>>();
            for (String name : headers.names()) {
                map.put(name, headers.values(name));
            }
            return map;
        } catch (Exception e) {
            log.debug("could not read headers", e);
            return Map.of();
        }
    }

    /**
     * {@link HttpResponse} wrapper that captures JSON/SSE bytes for tagging, but leaves declared
     * binary audio payloads unbuffered. Both paths end the span when the body reaches EOF or is
     * closed, retaining response headers and HTTP error status even without a JSON body.
     */
    private static final class TeeingStreamHttpResponse implements HttpResponse {
        private final HttpResponse delegate;
        private final Span span;
        private final Tracer tracer;
        private final long spanStartNanos = System.nanoTime();
        private final AtomicLong timeToFirstTokenNanos = new AtomicLong();
        @Nullable private final ByteArrayOutputStream teeBuffer;
        private final InputStream teeStream;

        TeeingStreamHttpResponse(HttpResponse delegate, Span span, Tracer tracer) {
            this.delegate = delegate;
            this.span = span;
            this.tracer = tracer;
            var contentTypes = delegate.headers().values("Content-Type");
            String contentType = contentTypes.isEmpty() ? null : contentTypes.get(0);
            boolean binary =
                    contentType != null
                            && (contentType.regionMatches(true, 0, "audio/", 0, 6)
                                    || matchesMediaType(contentType, "application/octet-stream"));
            this.teeBuffer = binary ? null : new ByteArrayOutputStream();
            this.teeStream =
                    new TeeInputStream(
                            delegate.body(),
                            teeBuffer != null ? teeBuffer : OutputStream.nullOutputStream(),
                            this::onFirstByte,
                            this::onStreamClosed);
        }

        private void onFirstByte() {
            timeToFirstTokenNanos.set(System.nanoTime() - spanStartNanos);
        }

        /** Called back by {@link TeeInputStream} when the stream is fully drained or closed. */
        private void onStreamClosed() {
            try {
                // Synchronize on teeBuffer to ensure any write() that was in-flight on a
                // concurrent read thread has fully completed before we snapshot the bytes.
                byte[] bytes = null;
                if (teeBuffer != null) {
                    synchronized (teeBuffer) {
                        bytes = teeBuffer.toByteArray();
                    }
                }

                // Recorded before tagging: openai-java raises above this layer, so the error
                // status is ours alone to set, and losing it to a body-parsing problem is worse
                // than losing the parsed output.
                // Anything outside 2xx, not just 4xx/5xx: both vendor SDKs treat success as
                // exactly 200..299, so a final 3xx that the http client did not follow (a 304, or
                // a redirect with no usable Location) is raised to the caller as an
                // UnexpectedStatusCodeException and must mark the span failed too.
                int statusCode = delegate.statusCode();
                if (statusCode < 200 || statusCode >= 300) {
                    InstrumentationSemConv.tagLLMSpanHttpError(
                            span,
                            statusCode,
                            bytes != null ? new String(bytes, StandardCharsets.UTF_8) : null);
                }

                // Wire-format bookkeeping lives in ResponseReassembler; this hands semconv
                // everything the response carried in one flat call. A null body (empty response,
                // or an SSE shape we didn't recognize) still tags the headers.
                // tagLLMSpanResponse also emits child spans for any server-side tool calls (web
                // search, etc.) nested under the LLM span while it is still live. No-op for Chat
                // Completions responses (no `output` array).
                try {
                    var reassembled =
                            bytes != null
                                    ? ResponseReassembler.reassemble(
                                            bytes, timeToFirstTokenNanos.get())
                                    : null;
                    InstrumentationSemConv.tagLLMSpanResponse(
                            tracer,
                            span,
                            InstrumentationSemConv.PROVIDER_NAME_OPENAI,
                            reassembled != null ? reassembled.body() : null,
                            reassembled != null ? reassembled.timeToFirstTokenNanos() : null,
                            headersAsMap(delegate.headers()));
                } catch (Exception e) {
                    // Observability must never change the response behavior seen by the caller.
                    log.error("Could not tag span from response buffer", e);
                }
            } finally {
                span.end();
            }
        }

        @Override
        public int statusCode() {
            return delegate.statusCode();
        }

        @Override
        public Headers headers() {
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
            } catch (IOException ignored) {
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
        public int read() throws IOException {
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
