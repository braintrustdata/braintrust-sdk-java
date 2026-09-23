package dev.braintrust.trace;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.sun.net.httpserver.HttpServer;
import dev.braintrust.config.BraintrustConfig;
import dev.braintrust.json.BraintrustJsonMapper;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.proto.collector.trace.v1.ExportTraceServiceRequest;
import io.opentelemetry.sdk.trace.ReadableSpan;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.data.DelegatingSpanData;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.stream.Stream;
import lombok.SneakyThrows;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Integration tests inspecting actual OTLP payloads and attachment requests. Additional span
 * processors no longer see attachment conversion, which is specific to the Braintrust exporter.
 */
public class AttachmentProcessorTest {

    /** A small valid 1x1 PNG encoded as base64. */
    private static final String BASE64_PNG =
            "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8DwHwAFBQIAX8jx0gAAAABJRU5ErkJggg==";

    /** Fake base64 content standing in for a PDF document. */
    private static final String BASE64_PDF = "JVBERi0xLjQKMSAwIG9iago=";

    private static final AttributeKey<String> INPUT_JSON =
            AttributeKey.stringKey("braintrust.input_json");

    private HttpServer server;
    private BraintrustConfig.Builder configBuilder;
    private SdkTracerProvider provider;
    private Tracer tracer;
    private final BlockingQueue<byte[]> exports = new LinkedBlockingQueue<>();
    private final BlockingQueue<byte[]> uploads = new LinkedBlockingQueue<>();
    private final BlockingQueue<String> attachmentRequests = new LinkedBlockingQueue<>();
    private final BlockingQueue<String> completedUploads = new LinkedBlockingQueue<>();
    private final AtomicInteger spanCounter = new AtomicInteger();
    private final CountDownLatch statusReceived = new CountDownLatch(1);
    private volatile CompletableFuture<Void> statusResponse =
            CompletableFuture.completedFuture(null);

    @BeforeEach
    void setUp() throws Exception {
        server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        var baseUrl = "http://localhost:" + server.getAddress().getPort();
        server.createContext(
                "/",
                exchange -> {
                    var path = exchange.getRequestURI().getPath();
                    try (exchange) {
                        var body = exchange.getRequestBody().readAllBytes();
                        String response = "{}";
                        if (path.equals("/otel/v1/traces")) {
                            exports.add(body);
                        } else {
                            attachmentRequests.add(path);
                            switch (path) {
                                case "/api/apikey/login" ->
                                        response =
                                                "{\"org_info\":[{\"id\":\"org-123\",\"name\":\"test-org\"}]}";
                                case "/attachment" ->
                                        response =
                                                "{\"signedUrl\":\""
                                                        + baseUrl
                                                        + "/upload\",\"headers\":{}}";
                                case "/upload" -> uploads.add(body);
                                case "/attachment/status" -> {
                                    statusReceived.countDown();
                                    statusResponse.join();
                                }
                                default -> throw new AssertionError("Unexpected request: " + path);
                            }
                        }
                        exchange.getResponseHeaders().add("Content-Type", "application/json");
                        byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
                        exchange.sendResponseHeaders(200, bytes.length);
                        exchange.getResponseBody().write(bytes);
                    }
                    if (path.equals("/attachment/status")) {
                        completedUploads.add(path);
                    }
                });
        server.start();
        configBuilder =
                BraintrustConfig.builder()
                        .apiKey("test-key")
                        .apiUrl(baseUrl)
                        .filterAISpans(false)
                        .compressOtelPayload(false);
    }

    private void startTracing(SpanCustomizer... customizers) {
        for (var customizer : customizers) {
            configBuilder.addSpanCustomizer(customizer);
        }
        var config = configBuilder.build();
        provider =
                SdkTracerProvider.builder()
                        .addSpanProcessor(
                                new BraintrustSpanProcessor(
                                        config,
                                        SimpleSpanProcessor.create(
                                                new BraintrustSpanExporter(config))))
                        .build();
        tracer = provider.get("attachment-processor-test");
    }

    @AfterEach
    void tearDown() {
        statusResponse.complete(null);
        try {
            if (provider != null) {
                provider.close();
            }
        } finally {
            server.stop(0);
        }
    }

    // ── Parameterized: one case per provider attachment format ────────

    record FormatTestCase(String name, String inputJson, Consumer<JsonNode> assertions) {
        @Override
        public String toString() {
            return name;
        }
    }

    static Stream<FormatTestCase> attachmentFormatCases() {
        return Stream.of(
                // OpenAI: data URI in image_url.url
                new FormatTestCase(
                        "openai-image",
                        "[{\"role\":\"user\",\"content\":[{\"type\":\"text\",\"text\":\"describe"
                            + " this\"},"
                            + "{\"type\":\"image_url\",\"image_url\":{\"url\":\"data:image/png;base64,"
                                + BASE64_PNG
                                + "\"}}]}]",
                        root -> {
                            JsonNode url =
                                    root.get(0).get("content").get(1).get("image_url").get("url");
                            assertAttachmentRef(url, "image/png");
                        }),

                // Bedrock image: {"format": "png", "source": {"bytes": "<base64>"}}
                new FormatTestCase(
                        "bedrock-image",
                        "[{\"role\":\"user\",\"content\":[{\"type\":\"text\",\"text\":\"describe"
                                + " this\"},{\"type\":\"image\",\"image\":{\"format\":\"png\","
                                + "\"source\":{\"bytes\":\""
                                + BASE64_PNG
                                + "\"}}}]}]",
                        root -> {
                            JsonNode image = root.get(0).get("content").get(1).get("image");
                            assertEquals("png", image.get("format").asText());
                            assertAttachmentRef(image.get("source").get("bytes"), "image/png");
                        }),

                // Bedrock document: {"format": "pdf", "name": "doc", "source": {"bytes": "..."}}
                new FormatTestCase(
                        "bedrock-document",
                        "[{\"role\":\"user\",\"content\":[{\"type\":\"text\",\"text\":\"summarize"
                            + " this\"},{\"type\":\"document\",\"document\":{\"format\":\"pdf\","
                            + "\"name\":\"report\",\"source\":{\"bytes\":\""
                                + BASE64_PDF
                                + "\"}}}]}]",
                        root -> {
                            JsonNode doc = root.get(0).get("content").get(1).get("document");
                            assertEquals("pdf", doc.get("format").asText());
                            assertEquals("report", doc.get("name").asText());
                            assertAttachmentRef(doc.get("source").get("bytes"), "application/pdf");
                        }),

                // Bedrock audio: uses block type key to resolve mp4 as audio/mp4 (not video/mp4)
                new FormatTestCase(
                        "bedrock-audio",
                        "[{\"role\":\"user\",\"content\":[{\"type\":\"text\",\"text\":\"transcribe"
                                + " this\"},{\"type\":\"audio\",\"audio\":{\"format\":\"mp4\","
                                + "\"source\":{\"bytes\":\""
                                + BASE64_PDF
                                + "\"}}}]}]",
                        root -> {
                            JsonNode audio = root.get(0).get("content").get(1).get("audio");
                            assertEquals("mp4", audio.get("format").asText());
                            assertAttachmentRef(audio.get("source").get("bytes"), "audio/mp4");
                        }),

                // Anthropic image: {"type":"base64","media_type":"image/png","data":"<base64>"}
                new FormatTestCase(
                        "anthropic-image",
                        "[{\"role\":\"user\",\"content\":[{\"type\":\"text\",\"text\":\"describe"
                                + " this\"},{\"type\":\"image\",\"source\":{\"type\":\"base64\","
                                + "\"media_type\":\"image/png\",\"data\":\""
                                + BASE64_PNG
                                + "\"}}]}]",
                        root -> {
                            JsonNode source = root.get(0).get("content").get(1).get("source");
                            assertAttachmentRef(source, "image/png");
                        }),

                // Anthropic document: same source structure, different media_type
                new FormatTestCase(
                        "anthropic-document",
                        "[{\"role\":\"user\",\"content\":[{\"type\":\"text\",\"text\":\"summarize"
                                + " this\"},{\"type\":\"document\",\"source\":{\"type\":\"base64\","
                                + "\"media_type\":\"application/pdf\",\"data\":\""
                                + BASE64_PDF
                                + "\"}}]}]",
                        root -> {
                            JsonNode source = root.get(0).get("content").get(1).get("source");
                            assertAttachmentRef(source, "application/pdf");
                        }),

                // Gemini image: {"inlineData": {"mimeType": "image/png", "data": "<base64>"}}
                new FormatTestCase(
                        "gemini-image",
                        "{\"contents\":[{\"role\":\"user\",\"parts\":[{\"text\":\"describe"
                                + " this\"},{\"inlineData\":{\"mimeType\":\"image/png\",\"data\":\""
                                + BASE64_PNG
                                + "\"}}]}]}",
                        root -> {
                            JsonNode part = root.get("contents").get(0).get("parts").get(1);
                            assertNull(part.get("inlineData"), "inlineData should be removed");
                            assertAttachmentRef(part.get("image_url").get("url"), "image/png");
                        }),

                // Gemini PDF: non-image content uses file: {file_data: ref}
                new FormatTestCase(
                        "gemini-document",
                        "{\"contents\":[{\"role\":\"user\",\"parts\":[{\"text\":\"summarize"
                                + " this\"},{\"inlineData\":{\"mimeType\":\"application/pdf\","
                                + "\"data\":\""
                                + BASE64_PDF
                                + "\"}}]}]}",
                        root -> {
                            JsonNode part = root.get("contents").get(0).get("parts").get(1);
                            assertNull(part.get("inlineData"), "inlineData should be removed");
                            assertNull(part.get("image_url"), "non-image should not use image_url");
                            assertAttachmentRef(
                                    part.get("file").get("file_data"), "application/pdf");
                        }));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("attachmentFormatCases")
    @SneakyThrows
    void attachmentFormatReplacesBase64WithRef(FormatTestCase testCase) {
        startTracing();
        String exportedJson = sendAndGetExportedInput(testCase.inputJson);
        testCase.assertions.accept(BraintrustJsonMapper.get().readTree(exportedJson));
        assertNotNull(uploads.poll(5, TimeUnit.SECONDS), "Attachment bytes must be uploaded");
        assertNotNull(completedUploads.poll(5, TimeUnit.SECONDS), "Upload must complete");
    }

    // ── Negative cases ────────────────────────────────────────────────

    @Test
    void nonDataUriInputIsUnchanged() {
        startTracing();
        String inputJson = "[{\"role\":\"user\",\"content\":\"Hello, how are you?\"}]";
        assertEquals(inputJson, sendAndGetExportedInput(inputJson));
    }

    @Test
    void partialDataUriInTextIsNotReplaced() {
        startTracing();
        String inputJson =
                "[{\"role\":\"user\",\"content\":\"Check this: data:image/png;base64,"
                        + BASE64_PNG
                        + " please\"}]";
        assertEquals(inputJson, sendAndGetExportedInput(inputJson));
    }

    @Test
    void redactionSeesInlineAttachmentsAndPreventsUploads() throws Exception {
        String inline = "{\"url\":\"data:image/png;base64," + BASE64_PNG + "\"}";
        var seen = new AtomicReference<SpanData>();
        startTracing(
                new SpanCustomizer() {
                    @Override
                    public SpanData onSpanExport(SpanData span) {
                        seen.set(span);
                        var attributes =
                                span.getAttributes().toBuilder()
                                        .remove(BraintrustSpanProcessor.INPUT_JSON)
                                        .put(BraintrustSpanProcessor.OUTPUT_JSON, "\"redacted\"")
                                        .build();
                        return new DelegatingSpanData(span) {
                            @Override
                            public io.opentelemetry.api.common.Attributes getAttributes() {
                                return attributes;
                            }

                            @Override
                            public int getTotalAttributeCount() {
                                return attributes.size();
                            }
                        };
                    }
                });
        var span = tracer.spanBuilder("redacted").startSpan();
        span.setAttribute(BraintrustSpanProcessor.INPUT_JSON, inline);
        span.setAttribute(BraintrustSpanProcessor.OUTPUT_JSON, inline);
        span.end();

        var exported = awaitExportedSpan();
        assertEquals(inline, seen.get().getAttributes().get(BraintrustSpanProcessor.INPUT_JSON));
        assertEquals(inline, seen.get().getAttributes().get(BraintrustSpanProcessor.OUTPUT_JSON));
        assertFalse(
                exported.getAttributesList().stream()
                        .anyMatch(a -> a.getKey().equals("braintrust.input_json")));
        assertEquals(
                "\"redacted\"",
                exported.getAttributesList().stream()
                        .filter(a -> a.getKey().equals("braintrust.output_json"))
                        .findFirst()
                        .orElseThrow()
                        .getValue()
                        .getStringValue());
        assertNull(attachmentRequests.poll(300, TimeUnit.MILLISECONDS));
    }

    @Test
    void rejectedBatchDoesNotUploadEarlierSpansAttachments() throws Exception {
        configBuilder.addSpanCustomizer(
                new SpanCustomizer() {
                    @Override
                    public SpanData onSpanExport(SpanData span) {
                        if (span.getName().equals("reject")) {
                            throw new IllegalStateException("Redaction failed");
                        }
                        return span;
                    }
                });
        try (var source = SdkTracerProvider.builder().build();
                var exporter = new BraintrustSpanExporter(configBuilder.build())) {
            var first =
                    source.get("test")
                            .spanBuilder("accept")
                            .setAttribute("braintrust.parent", "project_name:first")
                            .setAttribute(
                                    INPUT_JSON,
                                    "{\"url\":\"data:image/png;base64," + BASE64_PNG + "\"}")
                            .startSpan();
            var second =
                    source.get("test")
                            .spanBuilder("reject")
                            .setAttribute("braintrust.parent", "project_name:second")
                            .startSpan();
            first.end();
            second.end();
            var result =
                    exporter.export(
                                    List.of(
                                            ((ReadableSpan) first).toSpanData(),
                                            ((ReadableSpan) second).toSpanData()))
                            .join(5, TimeUnit.SECONDS);
            assertTrue(result.isDone());
            assertFalse(result.isSuccess());
            assertNull(attachmentRequests.poll(300, TimeUnit.MILLISECONDS));
            assertTrue(exports.isEmpty());
        }
    }

    @Test
    void customizedOutputAttachmentIsConvertedAndUploaded() throws Exception {
        startTracing(
                new SpanCustomizer() {
                    @Override
                    public SpanData onSpanExport(SpanData span) {
                        var attributes =
                                span.getAttributes().toBuilder()
                                        .put(
                                                BraintrustSpanProcessor.OUTPUT_JSON,
                                                "{\"url\":\"data:application/pdf;base64,"
                                                        + BASE64_PDF
                                                        + "\"}")
                                        .build();
                        return new DelegatingSpanData(span) {
                            @Override
                            public io.opentelemetry.api.common.Attributes getAttributes() {
                                return attributes;
                            }
                        };
                    }
                });
        var span = tracer.spanBuilder("replace-output").startSpan();
        span.setAttribute(
                BraintrustSpanProcessor.OUTPUT_JSON,
                "{\"url\":\"data:image/png;base64," + BASE64_PNG + "\"}");
        span.end();
        var exported = awaitExportedSpan();
        var output =
                exported.getAttributesList().stream()
                        .filter(a -> a.getKey().equals("braintrust.output_json"))
                        .findFirst()
                        .orElseThrow()
                        .getValue()
                        .getStringValue();
        assertAttachmentRef(
                BraintrustJsonMapper.get().readTree(output).get("url"), "application/pdf");
        assertArrayEquals(
                java.util.Base64.getDecoder().decode(BASE64_PDF),
                uploads.poll(5, TimeUnit.SECONDS));
        assertNotNull(completedUploads.poll(5, TimeUnit.SECONDS));
        assertTrue(uploads.isEmpty(), "The original attachment must not be uploaded");
    }

    @Test
    void providerShutdownWaitsForAttachmentStatusResponse() throws Exception {
        statusResponse = new CompletableFuture<>();
        startTracing();
        var span = tracer.spanBuilder("pending-attachment").startSpan();
        span.setAttribute(INPUT_JSON, "{\"url\":\"data:image/png;base64," + BASE64_PNG + "\"}");
        span.end();
        assertTrue(statusReceived.await(5, TimeUnit.SECONDS));

        var closer = Executors.newSingleThreadExecutor();
        var closing = closer.submit(() -> provider.close());
        try {
            assertThrows(TimeoutException.class, () -> closing.get(300, TimeUnit.MILLISECONDS));
            statusResponse.complete(null);
            closing.get(5, TimeUnit.SECONDS);
            assertNotNull(completedUploads.poll(5, TimeUnit.SECONDS));
        } finally {
            statusResponse.complete(null);
            closer.shutdownNow();
            assertTrue(closer.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    @Test
    void disabledConversionLeavesAttachmentsInline() throws Exception {
        configBuilder.autoConvertAIAttachments(false);
        startTracing();
        String inline = "{\"url\":\"data:image/png;base64," + BASE64_PNG + "\"}";
        assertEquals(inline, sendAndGetExportedInput(inline));
        assertNull(attachmentRequests.poll(300, TimeUnit.MILLISECONDS));
    }

    // ── Helpers ───────────────────────────────────────────────────────

    @SneakyThrows
    private String sendAndGetExportedInput(String inputJson) {
        String spanName = "negative-" + spanCounter.incrementAndGet();
        var span = tracer.spanBuilder(spanName).startSpan();
        span.setAttribute("braintrust.input_json", inputJson);
        span.end();

        var exported = awaitExportedSpan();
        return exported.getAttributesList().stream()
                .filter(attribute -> attribute.getKey().equals(INPUT_JSON.getKey()))
                .findFirst()
                .orElseThrow()
                .getValue()
                .getStringValue();
    }

    @SneakyThrows
    private io.opentelemetry.proto.trace.v1.Span awaitExportedSpan() {
        var result = provider.forceFlush().join(5, TimeUnit.SECONDS);
        assertTrue(result.isSuccess());
        var body = exports.poll(5, TimeUnit.SECONDS);
        assertNotNull(body, "Expected an OTLP export");
        return ExportTraceServiceRequest.parseFrom(body)
                .getResourceSpans(0)
                .getScopeSpans(0)
                .getSpans(0);
    }

    private static void assertAttachmentRef(JsonNode node, String expectedContentType) {
        assertNotNull(node, "attachment ref node should not be null");
        assertTrue(node.isObject(), "attachment ref should be an object, got: " + node);
        assertEquals("braintrust_attachment", node.get("type").asText());
        assertEquals(expectedContentType, node.get("content_type").asText());
        assertNotNull(node.get("filename"));
        assertFalse(node.get("filename").asText().isEmpty());
        assertNotNull(node.get("key"));
        assertFalse(node.get("key").asText().isEmpty());
    }
}
