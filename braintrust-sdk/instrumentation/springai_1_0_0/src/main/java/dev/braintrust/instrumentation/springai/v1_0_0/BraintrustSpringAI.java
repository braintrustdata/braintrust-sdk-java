package dev.braintrust.instrumentation.springai.v1_0_0;

import dev.braintrust.instrumentation.InstrumentationSemConv;
import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.Tracer;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.reactivestreams.Publisher;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DataBufferFactory;
import org.springframework.http.HttpCookie;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpRequest;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.client.ClientHttpRequestExecution;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.http.client.reactive.ClientHttpRequest;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;
import org.springframework.web.reactive.function.BodyInserter;
import org.springframework.web.reactive.function.client.ClientRequest;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.ExchangeFilterFunction;
import org.springframework.web.reactive.function.client.ExchangeFunction;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * Braintrust Spring AI instrumentation entry point.
 *
 * <p>Accepts any Spring AI chat-model builder and instruments it in place before {@code build()}
 * runs by hooking into the underlying HTTP clients (RestClient for synchronous calls, WebClient for
 * streaming) to capture request/response data for Braintrust tracing.
 */
@Slf4j
public class BraintrustSpringAI {
    private static final String TRACER_NAME = "braintrust-java";

    private static final String OPENAI_BUILDER_CLASS =
            "org.springframework.ai.openai.OpenAiChatModel$Builder";
    private static final String ANTHROPIC_BUILDER_CLASS =
            "org.springframework.ai.anthropic.AnthropicChatModel$Builder";

    /** Instruments a Spring AI chat-model builder in place. */
    public static <T> T wrap(OpenTelemetry openTelemetry, T chatModelBuilder) {
        try {
            String builderClassName = chatModelBuilder.getClass().getName();

            if (OPENAI_BUILDER_CLASS.equals(builderClassName)) {
                wrapOpenAI(openTelemetry, chatModelBuilder);
            } else if (ANTHROPIC_BUILDER_CLASS.equals(builderClassName)) {
                wrapAnthropic(openTelemetry, chatModelBuilder);
            } else {
                log.warn("BraintrustSpringAI.wrap: unrecognised builder type {}", builderClassName);
            }
        } catch (Exception e) {
            log.error("failed to apply spring ai instrumentation", e);
        }
        return chatModelBuilder;
    }

    // -------------------------------------------------------------------------
    // OpenAI wrapping
    // -------------------------------------------------------------------------

    private static void wrapOpenAI(OpenTelemetry openTelemetry, Object builder) throws Exception {
        Tracer tracer = openTelemetry.getTracer(TRACER_NAME);
        Object openAiApi = getField(builder, "openAiApi");
        String baseUrl = getField(openAiApi, "baseUrl");

        String providerName = InstrumentationSemConv.PROVIDER_NAME_OPENAI;

        // Instrument the synchronous RestClient
        RestClient restClient = getField(openAiApi, "restClient");
        RestClient instrumentedRest =
                restClient
                        .mutate()
                        .requestInterceptor(
                                new BraintrustRestInterceptor(tracer, providerName, baseUrl))
                        .build();
        setField(openAiApi, "restClient", instrumentedRest);

        // Instrument the reactive WebClient
        WebClient webClient = getField(openAiApi, "webClient");
        WebClient instrumentedWeb =
                webClient
                        .mutate()
                        .filter(new BraintrustWebClientFilter(tracer, providerName, baseUrl))
                        .build();
        setField(openAiApi, "webClient", instrumentedWeb);
    }

    // -------------------------------------------------------------------------
    // Anthropic wrapping
    // -------------------------------------------------------------------------

    private static void wrapAnthropic(OpenTelemetry openTelemetry, Object builder)
            throws Exception {
        Tracer tracer = openTelemetry.getTracer(TRACER_NAME);
        Object anthropicApi = getField(builder, "anthropicApi");
        String baseUrl = extractAnthropicBaseUrl(anthropicApi);

        String providerName = InstrumentationSemConv.PROVIDER_NAME_ANTHROPIC;

        // Instrument the synchronous RestClient
        RestClient restClient = getField(anthropicApi, "restClient");
        RestClient instrumentedRest =
                restClient
                        .mutate()
                        .requestInterceptor(
                                new BraintrustRestInterceptor(tracer, providerName, baseUrl))
                        .build();
        setField(anthropicApi, "restClient", instrumentedRest);

        // Instrument the reactive WebClient
        WebClient webClient = getField(anthropicApi, "webClient");
        WebClient instrumentedWeb =
                webClient
                        .mutate()
                        .filter(new BraintrustWebClientFilter(tracer, providerName, baseUrl))
                        .build();
        setField(anthropicApi, "webClient", instrumentedWeb);
    }

    /**
     * Extracts the base URL from an AnthropicApi instance. Unlike OpenAiApi, Anthropic doesn't
     * store baseUrl as a field — it's baked into the RestClient's URI builder factory.
     */
    private static String extractAnthropicBaseUrl(Object anthropicApi) {
        try {
            Object restClient = getField(anthropicApi, "restClient");
            Object uriBuilderFactory = getField(restClient, "uriBuilderFactory");
            Object baseUri = getField(uriBuilderFactory, "baseUri");
            return (String) baseUri.getClass().getMethod("toUriString").invoke(baseUri);
        } catch (Exception e) {
            log.warn("Failed to extract baseUrl from Anthropic builder", e);
            return "https://api.anthropic.com";
        }
    }

    // -------------------------------------------------------------------------
    // RestClient interceptor (synchronous / non-streaming calls)
    // -------------------------------------------------------------------------

    /**
     * Interceptor that wraps each synchronous REST call in an OTel span, capturing the full request
     * and response bodies for Braintrust logging.
     */
    static class BraintrustRestInterceptor implements ClientHttpRequestInterceptor {
        private final Tracer tracer;
        private final String providerName;
        private final String baseUrl;

        BraintrustRestInterceptor(Tracer tracer, String providerName, String baseUrl) {
            this.tracer = tracer;
            this.providerName = providerName;
            this.baseUrl = baseUrl;
        }

        @Override
        public ClientHttpResponse intercept(
                HttpRequest request, byte[] body, ClientHttpRequestExecution execution)
                throws IOException {
            Span span = tracer.spanBuilder(InstrumentationSemConv.UNSET_LLM_SPAN_NAME).startSpan();
            try {
                String requestBody = new String(body, StandardCharsets.UTF_8);
                List<String> pathSegments = extractPathSegments(request.getURI());
                InstrumentationSemConv.tagLLMSpanRequest(
                        span,
                        providerName,
                        baseUrl,
                        pathSegments,
                        request.getMethod().name(),
                        requestBody);

                ClientHttpResponse response = execution.execute(request, body);

                // Buffer the response body so we can read it and still return it to the caller.
                byte[] responseBytes = response.getBody().readAllBytes();
                String responseBody = new String(responseBytes, StandardCharsets.UTF_8);

                InstrumentationSemConv.tagLLMSpanResponse(span, providerName, responseBody);

                span.end();
                return new BufferedClientHttpResponse(response, responseBytes);
            } catch (Exception e) {
                InstrumentationSemConv.tagLLMSpanResponse(span, e);
                span.end();
                throw e;
            }
        }
    }

    /**
     * Wraps a {@link ClientHttpResponse} to return a pre-buffered body, so the original response
     * stream can be consumed by our interceptor without preventing the caller from reading it.
     */
    private static class BufferedClientHttpResponse implements ClientHttpResponse {
        private final ClientHttpResponse delegate;
        private final byte[] body;

        BufferedClientHttpResponse(ClientHttpResponse delegate, byte[] body) {
            this.delegate = delegate;
            this.body = body;
        }

        @Override
        public HttpStatusCode getStatusCode() throws IOException {
            return delegate.getStatusCode();
        }

        @Override
        public String getStatusText() throws IOException {
            return delegate.getStatusText();
        }

        @Override
        public HttpHeaders getHeaders() {
            return delegate.getHeaders();
        }

        @Override
        public InputStream getBody() {
            return new ByteArrayInputStream(body);
        }

        @Override
        public void close() {
            delegate.close();
        }
    }

    // -------------------------------------------------------------------------
    // WebClient filter (reactive / streaming calls)
    // -------------------------------------------------------------------------

    /**
     * Exchange filter that wraps each reactive/streaming call in an OTel span. Captures the request
     * body by wrapping the {@link BodyInserter}, and reassembles the SSE response stream for
     * response tagging.
     */
    static class BraintrustWebClientFilter implements ExchangeFilterFunction {
        private final Tracer tracer;
        private final String providerName;
        private final String baseUrl;

        BraintrustWebClientFilter(Tracer tracer, String providerName, String baseUrl) {
            this.tracer = tracer;
            this.providerName = providerName;
            this.baseUrl = baseUrl;
        }

        @Override
        public Mono<ClientResponse> filter(ClientRequest request, ExchangeFunction next) {
            Span span = tracer.spanBuilder(InstrumentationSemConv.UNSET_LLM_SPAN_NAME).startSpan();
            long startNanos = System.nanoTime();

            List<String> pathSegments = extractPathSegments(request.url());
            String method = request.method().name();

            // Wrap the request to intercept the serialized body for input tagging.
            var bodyCapture = new BodyCapturingRequest(request);

            // Store context needed for metadata back-fill after stream completes.
            StreamContext streamCtx =
                    new StreamContext(providerName, baseUrl, pathSegments, method);

            return next.exchange(bodyCapture.wrappedRequest())
                    .map(
                            response -> {
                                // Tag the request now that we have the captured body.
                                String capturedBody = bodyCapture.getCapturedBody();
                                InstrumentationSemConv.tagLLMSpanRequest(
                                        span,
                                        providerName,
                                        baseUrl,
                                        pathSegments,
                                        method,
                                        capturedBody);

                                // Wrap the response body to intercept each chunk for TTFT
                                // tracking and to reassemble the full SSE stream for response
                                // tagging.
                                return response.mutate()
                                        .body(
                                                originalBody ->
                                                        wrapStreamingBody(
                                                                originalBody,
                                                                span,
                                                                startNanos,
                                                                streamCtx))
                                        .build();
                            })
                    .doOnError(
                            error -> {
                                InstrumentationSemConv.tagLLMSpanResponse(span, error);
                                span.end();
                            });
        }

        /**
         * Wraps the streaming response body {@link Flux} to:
         *
         * <ol>
         *   <li>Record time-to-first-token on the first data chunk
         *   <li>Collect all SSE data lines to reassemble the response for tagging
         *   <li>End the span when the stream completes or errors
         * </ol>
         */
        @SuppressWarnings("unchecked")
        private Flux<DataBuffer> wrapStreamingBody(
                Publisher<? extends DataBuffer> originalBody,
                Span span,
                long startNanos,
                StreamContext streamCtx) {
            final long[] ttftNanos = {-1};
            StringBuilder assembled = new StringBuilder();

            return Flux.from((Publisher<DataBuffer>) originalBody)
                    .doOnNext(
                            dataBuffer -> {
                                if (ttftNanos[0] < 0) {
                                    ttftNanos[0] = System.nanoTime() - startNanos;
                                }
                                // Read the chunk content without consuming the buffer for the
                                // downstream.
                                int readableCount = dataBuffer.readableByteCount();
                                byte[] bytes = new byte[readableCount];
                                dataBuffer.read(bytes);
                                // Reset read position so downstream can still read the buffer.
                                dataBuffer.readPosition(dataBuffer.readPosition() - readableCount);
                                assembled.append(new String(bytes, StandardCharsets.UTF_8));
                            })
                    .doOnComplete(
                            () -> {
                                try {
                                    String responseBody =
                                            reassembleSSEResponse(
                                                    assembled.toString(), span, streamCtx);
                                    Long ttft = ttftNanos[0] >= 0 ? ttftNanos[0] : null;
                                    InstrumentationSemConv.tagLLMSpanResponse(
                                            span, streamCtx.providerName(), responseBody, ttft);
                                } catch (Exception e) {
                                    log.debug("failed to tag streaming response", e);
                                }
                                span.end();
                            })
                    .doOnError(
                            error -> {
                                InstrumentationSemConv.tagLLMSpanResponse(span, error);
                                span.end();
                            });
        }
    }

    // -------------------------------------------------------------------------
    // Request body capture (WebClient)
    // -------------------------------------------------------------------------

    /**
     * Wraps a {@link ClientRequest} to capture the serialized request body bytes. The body is
     * intercepted by wrapping the original {@link BodyInserter} and tapping into the output stream.
     */
    private static class BodyCapturingRequest {
        private final ClientRequest original;
        private volatile String capturedBody;

        BodyCapturingRequest(ClientRequest original) {
            this.original = original;
        }

        @SuppressWarnings({"unchecked", "rawtypes"})
        ClientRequest wrappedRequest() {
            var originalBody = (BodyInserter) original.body();
            return ClientRequest.from(original)
                    .body(
                            (outputMessage, context) -> {
                                var capturingOutput =
                                        new BodyCapturingOutputMessage(
                                                (ClientHttpRequest) outputMessage);
                                return originalBody
                                        .insert(capturingOutput, context)
                                        .doOnSuccess(
                                                v ->
                                                        capturedBody =
                                                                capturingOutput.getCapturedBody());
                            })
                    .build();
        }

        String getCapturedBody() {
            return capturedBody;
        }
    }

    /**
     * Wraps a {@link ClientHttpRequest} to capture the body bytes as they are written, while
     * delegating all other operations to the original.
     */
    private static class BodyCapturingOutputMessage implements ClientHttpRequest {
        private final ClientHttpRequest delegate;
        private final StringBuilder captured = new StringBuilder();

        BodyCapturingOutputMessage(ClientHttpRequest delegate) {
            this.delegate = delegate;
        }

        String getCapturedBody() {
            return captured.toString();
        }

        @Override
        public HttpHeaders getHeaders() {
            return delegate.getHeaders();
        }

        @Override
        public DataBufferFactory bufferFactory() {
            return delegate.bufferFactory();
        }

        @Override
        public void beforeCommit(Supplier<? extends Mono<Void>> action) {
            delegate.beforeCommit(action);
        }

        @Override
        public boolean isCommitted() {
            return delegate.isCommitted();
        }

        @Override
        public Mono<Void> writeWith(Publisher<? extends DataBuffer> body) {
            return delegate.writeWith(
                    Flux.from(body)
                            .doOnNext(
                                    dataBuffer -> {
                                        int readableCount = dataBuffer.readableByteCount();
                                        byte[] bytes = new byte[readableCount];
                                        dataBuffer.read(bytes);
                                        dataBuffer.readPosition(
                                                dataBuffer.readPosition() - readableCount);
                                        captured.append(new String(bytes, StandardCharsets.UTF_8));
                                    }));
        }

        @Override
        public Mono<Void> writeAndFlushWith(
                Publisher<? extends Publisher<? extends DataBuffer>> body) {
            return delegate.writeAndFlushWith(
                    Flux.from(body)
                            .map(
                                    inner ->
                                            Flux.from(inner)
                                                    .doOnNext(
                                                            dataBuffer -> {
                                                                int readableCount =
                                                                        dataBuffer
                                                                                .readableByteCount();
                                                                byte[] bytes =
                                                                        new byte[readableCount];
                                                                dataBuffer.read(bytes);
                                                                dataBuffer.readPosition(
                                                                        dataBuffer.readPosition()
                                                                                - readableCount);
                                                                captured.append(
                                                                        new String(
                                                                                bytes,
                                                                                StandardCharsets
                                                                                        .UTF_8));
                                                            })));
        }

        @Override
        public Mono<Void> setComplete() {
            return delegate.setComplete();
        }

        @Override
        public HttpMethod getMethod() {
            return delegate.getMethod();
        }

        @Override
        public URI getURI() {
            return delegate.getURI();
        }

        @Override
        public MultiValueMap<String, HttpCookie> getCookies() {
            return delegate.getCookies();
        }

        @Override
        public <T> T getNativeRequest() {
            return delegate.getNativeRequest();
        }

        @Override
        public Map<String, Object> getAttributes() {
            return delegate.getAttributes();
        }
    }

    // -------------------------------------------------------------------------
    // SSE reassembly
    // -------------------------------------------------------------------------

    /** Context needed to back-fill span metadata from SSE stream data. */
    record StreamContext(
            String providerName, String baseUrl, List<String> pathSegments, String method) {}

    /**
     * Reassembles an SSE stream into a single JSON response object suitable for passing to {@link
     * InstrumentationSemConv#tagLLMSpanResponse}. Dispatches to provider-specific logic based on
     * the {@code streamCtx.providerName()}.
     *
     * <p>If a {@code span} and {@code streamCtx} are provided and a {@code model} field is found in
     * any chunk, the span's {@code braintrust.metadata} attribute is re-set to include the model
     * (since the WebClient filter does not have access to the serialized request body at filter
     * time).
     */
    @SneakyThrows
    static String reassembleSSEResponse(String rawSSE, Span span, StreamContext streamCtx) {
        if (streamCtx != null
                && InstrumentationSemConv.PROVIDER_NAME_ANTHROPIC.equals(
                        streamCtx.providerName())) {
            return reassembleAnthropicSSE(rawSSE, span, streamCtx);
        }
        return reassembleOpenAISSE(rawSSE, span, streamCtx);
    }

    // -------------------------------------------------------------------------
    // OpenAI SSE reassembly
    // -------------------------------------------------------------------------

    @SneakyThrows
    private static String reassembleOpenAISSE(String rawSSE, Span span, StreamContext streamCtx) {
        var mapper = dev.braintrust.json.BraintrustJsonMapper.get();
        var choices = mapper.createObjectNode();
        var usage = mapper.createObjectNode();
        String model = null;

        for (String line : rawSSE.split("\n")) {
            if (!line.startsWith("data: ") || line.equals("data: [DONE]")) {
                continue;
            }
            String json = line.substring("data: ".length()).trim();
            var chunk = mapper.readTree(json);

            if (model == null && chunk.has("model") && !chunk.get("model").isNull()) {
                model = chunk.get("model").asText();
            }

            if (chunk.has("choices")) {
                for (var choiceChunk : chunk.get("choices")) {
                    int index = choiceChunk.has("index") ? choiceChunk.get("index").asInt() : 0;
                    String indexKey = String.valueOf(index);

                    if (!choices.has(indexKey)) {
                        var choice = mapper.createObjectNode();
                        var message = mapper.createObjectNode();
                        message.put("role", "assistant");
                        message.put("content", "");
                        choice.set("message", message);
                        choice.put("index", index);
                        choices.set(indexKey, choice);
                    }

                    var choice = choices.get(indexKey);
                    if (choiceChunk.has("delta")) {
                        var delta = choiceChunk.get("delta");
                        if (delta.has("content") && !delta.get("content").isNull()) {
                            String existing = choice.get("message").get("content").asText();
                            ((com.fasterxml.jackson.databind.node.ObjectNode) choice.get("message"))
                                    .put("content", existing + delta.get("content").asText());
                        }
                        if (delta.has("tool_calls")) {
                            if (!choice.get("message").has("tool_calls")) {
                                ((com.fasterxml.jackson.databind.node.ObjectNode)
                                                choice.get("message"))
                                        .set("tool_calls", mapper.createArrayNode());
                            }
                            for (var tc : delta.get("tool_calls")) {
                                ((com.fasterxml.jackson.databind.node.ArrayNode)
                                                choice.get("message").get("tool_calls"))
                                        .add(tc);
                            }
                        }
                    }
                    if (choiceChunk.has("finish_reason")
                            && !choiceChunk.get("finish_reason").isNull()) {
                        ((com.fasterxml.jackson.databind.node.ObjectNode) choice)
                                .put("finish_reason", choiceChunk.get("finish_reason").asText());
                    }
                }
            }

            if (chunk.has("usage") && !chunk.get("usage").isNull()) {
                var u = chunk.get("usage");
                u.fields().forEachRemaining(entry -> usage.set(entry.getKey(), entry.getValue()));
            }
        }

        backfillModelMetadata(span, streamCtx, model);

        var choicesArray = mapper.createArrayNode();
        choices.fields().forEachRemaining(entry -> choicesArray.add(entry.getValue()));

        var result = mapper.createObjectNode();
        result.set("choices", choicesArray);
        if (usage.size() > 0) {
            result.set("usage", usage);
        }
        return dev.braintrust.json.BraintrustJsonMapper.toJson(result);
    }

    // -------------------------------------------------------------------------
    // Anthropic SSE reassembly
    // -------------------------------------------------------------------------

    /**
     * Reassembles an Anthropic-format SSE stream into the Anthropic non-streaming response format:
     * {@code {"role":"assistant","content":[{"type":"text","text":"..."}],"usage":{...}}}
     *
     * <p>Anthropic SSE events use types like {@code message_start}, {@code content_block_start},
     * {@code content_block_delta}, {@code message_delta}, and {@code message_stop}.
     */
    @SneakyThrows
    private static String reassembleAnthropicSSE(
            String rawSSE, Span span, StreamContext streamCtx) {
        var mapper = dev.braintrust.json.BraintrustJsonMapper.get();
        var contentBlocks = mapper.createArrayNode();
        var usage = mapper.createObjectNode();
        String model = null;

        // Track content blocks by index for delta merging
        Map<Integer, StringBuilder> textByIndex = new HashMap<>();

        for (String line : rawSSE.split("\n")) {
            if (!line.startsWith("data: ") || line.equals("data: [DONE]")) {
                continue;
            }
            String json = line.substring("data: ".length()).trim();
            var event = mapper.readTree(json);
            String type = event.has("type") ? event.get("type").asText() : "";

            switch (type) {
                case "message_start" -> {
                    if (event.has("message")) {
                        var message = event.get("message");
                        if (model == null
                                && message.has("model")
                                && !message.get("model").isNull()) {
                            model = message.get("model").asText();
                        }
                        if (message.has("usage") && !message.get("usage").isNull()) {
                            message.get("usage")
                                    .fields()
                                    .forEachRemaining(
                                            entry -> usage.set(entry.getKey(), entry.getValue()));
                        }
                    }
                }
                case "content_block_start" -> {
                    int index = event.has("index") ? event.get("index").asInt() : 0;
                    textByIndex.putIfAbsent(index, new StringBuilder());
                    // If the content block has initial text, capture it
                    if (event.has("content_block")
                            && event.get("content_block").has("text")
                            && !event.get("content_block").get("text").asText().isEmpty()) {
                        textByIndex
                                .get(index)
                                .append(event.get("content_block").get("text").asText());
                    }
                }
                case "content_block_delta" -> {
                    int index = event.has("index") ? event.get("index").asInt() : 0;
                    textByIndex.putIfAbsent(index, new StringBuilder());
                    if (event.has("delta") && event.get("delta").has("text")) {
                        textByIndex.get(index).append(event.get("delta").get("text").asText());
                    }
                }
                case "message_delta" -> {
                    if (event.has("usage") && !event.get("usage").isNull()) {
                        event.get("usage")
                                .fields()
                                .forEachRemaining(
                                        entry -> usage.set(entry.getKey(), entry.getValue()));
                    }
                }
                default -> {
                    // message_stop, ping, etc. — nothing to do
                }
            }
        }

        backfillModelMetadata(span, streamCtx, model);

        // Build content blocks array
        textByIndex.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .forEach(
                        entry -> {
                            var block = mapper.createObjectNode();
                            block.put("type", "text");
                            block.put("text", entry.getValue().toString());
                            contentBlocks.add(block);
                        });

        var result = mapper.createObjectNode();
        result.put("role", "assistant");
        result.set("content", contentBlocks);
        if (usage.size() > 0) {
            result.set("usage", usage);
        }
        return dev.braintrust.json.BraintrustJsonMapper.toJson(result);
    }

    // -------------------------------------------------------------------------
    // Shared SSE helpers
    // -------------------------------------------------------------------------

    /**
     * Back-fills the span's {@code braintrust.metadata} attribute with the model extracted from SSE
     * stream data. Span setAttribute is last-writer-wins, so we rebuild the full metadata map.
     */
    private static void backfillModelMetadata(Span span, StreamContext streamCtx, String model) {
        if (span != null && streamCtx != null && model != null) {
            try {
                Map<String, String> metadata = new HashMap<>();
                metadata.put("provider", streamCtx.providerName());
                metadata.put("request_path", String.join("/", streamCtx.pathSegments()));
                metadata.put("request_base_uri", streamCtx.baseUrl());
                metadata.put("request_method", streamCtx.method());
                metadata.put("model", model);
                span.setAttribute(
                        "braintrust.metadata",
                        dev.braintrust.json.BraintrustJsonMapper.toJson(metadata));
            } catch (Exception e) {
                log.debug("failed to update span metadata with model", e);
            }
        }
    }

    // -------------------------------------------------------------------------
    // Reflection helpers
    // -------------------------------------------------------------------------

    /** Extracts non-empty path segments from a URI. */
    static List<String> extractPathSegments(URI uri) {
        String path = uri.getPath();
        if (path == null || path.isEmpty()) {
            return List.of();
        }
        return Arrays.stream(path.split("/"))
                .filter(s -> !s.isEmpty())
                .collect(Collectors.toList());
    }

    @SuppressWarnings("unchecked")
    static <T> T getField(Object obj, String fieldName) throws ReflectiveOperationException {
        Class<?> clazz = obj.getClass();
        while (clazz != null) {
            try {
                Field field = clazz.getDeclaredField(fieldName);
                field.setAccessible(true);
                return (T) field.get(obj);
            } catch (NoSuchFieldException e) {
                clazz = clazz.getSuperclass();
            }
        }
        throw new NoSuchFieldException(
                "Field '" + fieldName + "' not found on " + obj.getClass().getName());
    }

    static void setField(Object obj, String fieldName, Object value)
            throws ReflectiveOperationException {
        Class<?> clazz = obj.getClass();
        while (clazz != null) {
            try {
                Field field = clazz.getDeclaredField(fieldName);
                field.setAccessible(true);
                field.set(obj, value);
                return;
            } catch (NoSuchFieldException e) {
                clazz = clazz.getSuperclass();
            }
        }
        throw new NoSuchFieldException(
                "Field '" + fieldName + "' not found on " + obj.getClass().getName());
    }

    private BraintrustSpringAI() {}
}
