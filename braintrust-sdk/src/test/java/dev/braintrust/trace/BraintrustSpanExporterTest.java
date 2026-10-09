package dev.braintrust.trace;

import static org.junit.jupiter.api.Assertions.*;

import com.sun.net.httpserver.HttpsConfigurator;
import com.sun.net.httpserver.HttpsServer;
import dev.braintrust.Braintrust;
import dev.braintrust.config.BraintrustConfig;
import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanContext;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.api.trace.TraceFlags;
import io.opentelemetry.api.trace.TraceState;
import io.opentelemetry.context.Context;
import io.opentelemetry.proto.collector.trace.v1.ExportTraceServiceRequest;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.logs.SdkLoggerProvider;
import io.opentelemetry.sdk.metrics.SdkMeterProvider;
import io.opentelemetry.sdk.resources.Resource;
import io.opentelemetry.sdk.trace.ReadableSpan;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.data.DelegatingSpanData;
import io.opentelemetry.sdk.trace.data.EventData;
import io.opentelemetry.sdk.trace.data.LinkData;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.data.StatusData;
import java.io.ByteArrayInputStream;
import java.io.FileInputStream;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.security.*;
import java.time.Duration;
import java.util.AbstractList;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.zip.GZIPInputStream;
import javax.net.ssl.*;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

public class BraintrustSpanExporterTest {
    private static final AttributeKey<String> KEEP = AttributeKey.stringKey("custom.keep");
    private static final AttributeKey<String> PARENT =
            AttributeKey.stringKey(BraintrustTracing.PARENT_KEY);
    private static final SpanContext PARENT_CONTEXT =
            SpanContext.create(
                    "1234567890abcdef1234567890abcdef",
                    "1234567890abcdef",
                    TraceFlags.getSampled(),
                    TraceState.getDefault());

    private HttpsServer server;
    private SSLContext serverSslContext;
    private SSLContext clientSslContext;
    private X509TrustManager clientTrustManager;
    private int port;
    private BlockingQueue<CapturedRequest> requests;
    private CountDownLatch requestLatch;
    private java.nio.file.Path keystoreFile;

    @BeforeEach
    void setUp() throws Exception {
        GlobalOpenTelemetry.resetForTest();

        requests = new LinkedBlockingQueue<>();
        requestLatch = new CountDownLatch(1);

        // Generate self-signed certificate using keytool
        keystoreFile = Files.createTempFile("test-keystore", ".jks");
        // Delete the empty file so keytool can create it fresh
        Files.delete(keystoreFile);
        var keystorePath = keystoreFile.toAbsolutePath().toString();
        var password = "testpass";

        // Use keytool to generate a self-signed certificate with SAN for localhost
        var keytoolProcess =
                new ProcessBuilder(
                                "keytool",
                                "-genkeypair",
                                "-alias",
                                "test",
                                "-keyalg",
                                "RSA",
                                "-keysize",
                                "2048",
                                "-validity",
                                "365",
                                "-keystore",
                                keystorePath,
                                "-storepass",
                                password,
                                "-keypass",
                                password,
                                "-dname",
                                "CN=localhost, O=Braintrust Test, C=US",
                                "-ext",
                                "SAN=DNS:localhost,IP:127.0.0.1",
                                "-storetype",
                                "JKS")
                        .start();
        if (!keytoolProcess.waitFor(30, TimeUnit.SECONDS)) {
            keytoolProcess.destroyForcibly();
            fail("keytool did not finish within 30 seconds");
        }
        int exitCode = keytoolProcess.exitValue();
        if (exitCode != 0) {
            throw new RuntimeException("keytool failed with exit code " + exitCode);
        }

        // Load the server keystore
        var serverKeyStore = KeyStore.getInstance("JKS");
        try (var fis = new FileInputStream(keystorePath)) {
            serverKeyStore.load(fis, password.toCharArray());
        }

        var serverKmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        serverKmf.init(serverKeyStore, password.toCharArray());

        serverSslContext = SSLContext.getInstance("TLS");
        serverSslContext.init(serverKmf.getKeyManagers(), null, new SecureRandom());

        // Create client trust manager that trusts the server's self-signed cert
        var cert = serverKeyStore.getCertificate("test");
        var clientTrustStore = KeyStore.getInstance("JKS");
        clientTrustStore.load(null, null);
        clientTrustStore.setCertificateEntry("test-server", cert);

        var clientTmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        clientTmf.init(clientTrustStore);
        clientTrustManager = (X509TrustManager) clientTmf.getTrustManagers()[0];

        clientSslContext = SSLContext.getInstance("TLS");
        clientSslContext.init(null, new TrustManager[] {clientTrustManager}, new SecureRandom());

        // Start HTTPS server
        server = HttpsServer.create(new InetSocketAddress("localhost", 0), 0);
        server.setHttpsConfigurator(new HttpsConfigurator(serverSslContext));
        port = server.getAddress().getPort();

        // Handle OTLP traces endpoint
        server.createContext(
                "/otel/v1/traces",
                exchange -> {
                    try (exchange) {
                        var body = exchange.getRequestBody().readAllBytes();
                        requests.add(
                                new CapturedRequest(
                                        body,
                                        exchange.getRequestHeaders().getFirst("Content-Encoding"),
                                        exchange.getRequestHeaders().getFirst("x-bt-parent")));
                        requestLatch.countDown();
                        exchange.sendResponseHeaders(200, 0);
                    }
                });

        server.start();
    }

    @AfterEach
    void tearDown() throws Exception {
        if (server != null) {
            server.stop(0);
        }
        // Clean up the temporary keystore file
        if (keystoreFile != null) {
            Files.deleteIfExists(keystoreFile);
        }
    }

    @Test
    void testCustomSslContextAndTrustManager() throws Exception {
        var exported =
                exportSpan(
                        configBuilder().build(),
                        Attributes.builder().put("test-attr", "test-value").build());

        assertStringAttribute(exported.span(), "test-attr", "test-value");
    }

    @Test
    void spanExportAddsAttribute() throws Exception {
        var config =
                configBuilder()
                        .addSpanCustomizer(
                                new SpanCustomizer() {
                                    @Override
                                    public SpanData onSpanExport(SpanData span) {
                                        var attributes =
                                                span.getAttributes().toBuilder()
                                                        .put("custom.added", "new-value")
                                                        .build();
                                        return withAttributes(span, attributes);
                                    }
                                })
                        .build();

        var exported = exportSpan(config, Attributes.of(KEEP, "unchanged"));
        var protoSpan = exported.span();
        assertStringAttribute(protoSpan, "custom.added", "new-value");
        assertStringAttribute(protoSpan, KEEP.getKey(), "unchanged");
        assertEquals(
                exported.originalContext().getTraceId(),
                HexFormat.of().formatHex(protoSpan.getTraceId().toByteArray()));
        assertEquals(
                exported.originalContext().getSpanId(),
                HexFormat.of().formatHex(protoSpan.getSpanId().toByteArray()));
        assertEquals(
                PARENT_CONTEXT.getSpanId(),
                HexFormat.of().formatHex(protoSpan.getParentSpanId().toByteArray()));
    }

    @Test
    void spanExportMutatesExistingAttribute() throws Exception {
        var config =
                configBuilder()
                        .addSpanCustomizer(
                                new SpanCustomizer() {
                                    @Override
                                    public SpanData onSpanExport(SpanData span) {
                                        var attributes =
                                                span.getAttributes().toBuilder()
                                                        .put("custom.label", "after")
                                                        .build();
                                        return withAttributes(span, attributes);
                                    }
                                })
                        .build();

        var exported =
                exportSpan(
                        config,
                        Attributes.builder()
                                .put("custom.label", "before")
                                .put(KEEP, "unchanged")
                                .build());
        assertStringAttribute(exported.span(), "custom.label", "after");
        assertStringAttribute(exported.span(), KEEP.getKey(), "unchanged");
    }

    @Test
    void spanExportDeletesAttribute() throws Exception {
        var config =
                configBuilder()
                        .addSpanCustomizer(
                                new SpanCustomizer() {
                                    @Override
                                    public SpanData onSpanExport(SpanData span) {
                                        var attributes =
                                                span.getAttributes().toBuilder()
                                                        .remove(
                                                                AttributeKey.stringKey(
                                                                        "custom.secret"))
                                                        .build();
                                        return withAttributes(span, attributes);
                                    }
                                })
                        .build();

        var exported =
                exportSpan(
                        config,
                        Attributes.builder()
                                .put("custom.secret", "remove-me")
                                .put(KEEP, "unchanged")
                                .build());
        assertNoAttribute(exported.span(), "custom.secret");
        assertStringAttribute(exported.span(), KEEP.getKey(), "unchanged");
        assertEquals(0, exported.span().getDroppedAttributesCount());
    }

    @Test
    void spanExportComposesCustomizersWithoutChangingBuiltConfigs() throws Exception {
        var chain = AttributeKey.stringKey("custom.chain");
        var builder =
                configBuilder()
                        .addSpanCustomizer(
                                new SpanCustomizer() {
                                    @Override
                                    public SpanData onSpanExport(SpanData span) {
                                        return withAttributes(
                                                span,
                                                span.getAttributes().toBuilder()
                                                        .put(chain, "A")
                                                        .build());
                                    }
                                });
        var configA = builder.build();
        var configB =
                builder.addSpanCustomizer(
                                new SpanCustomizer() {
                                    @Override
                                    public SpanData onSpanExport(SpanData span) {
                                        return withAttributes(
                                                span,
                                                span.getAttributes().toBuilder()
                                                        .put(
                                                                chain,
                                                                span.getAttributes().get(chain)
                                                                        + "B")
                                                        .build());
                                    }
                                })
                        .build();

        assertStringAttribute(exportSpan(configA, Attributes.empty()).span(), chain.getKey(), "A");
        assertStringAttribute(exportSpan(configB, Attributes.empty()).span(), chain.getKey(), "AB");
    }

    @Test
    void spanExportRoutesUsingCustomizedParent() throws Exception {
        var config =
                configBuilder()
                        .addSpanCustomizer(
                                new SpanCustomizer() {
                                    @Override
                                    public SpanData onSpanExport(SpanData span) {
                                        return withAttributes(
                                                span,
                                                span.getAttributes().toBuilder()
                                                        .put(PARENT, "project_name:customized")
                                                        .build());
                                    }
                                })
                        .build();

        var exported = exportSpan(config, Attributes.of(PARENT, "project_name:original"));
        assertStringAttribute(exported.span(), PARENT.getKey(), "project_name:customized");
        assertEquals("project_name:customized", exported.parentHeader());
    }

    @Test
    void spanExportUsesConfiguredParentWhenCustomizerDeletesRoutingAttribute() throws Exception {
        var config =
                configBuilder()
                        .addSpanCustomizer(
                                new SpanCustomizer() {
                                    @Override
                                    public SpanData onSpanExport(SpanData span) {
                                        return withAttributes(
                                                span,
                                                span.getAttributes().toBuilder()
                                                        .remove(PARENT)
                                                        .build());
                                    }
                                })
                        .build();

        var exported = exportSpan(config, Attributes.of(PARENT, "project_name:original"));
        assertNoAttribute(exported.span(), PARENT.getKey());
        assertEquals("project_name:test-project", exported.parentHeader());
    }

    @Test
    void replacementSnapshotPreservesCustomizedContent() throws Exception {
        var config =
                configBuilder()
                        .addSpanCustomizer(
                                new SpanCustomizer() {
                                    @Override
                                    public SpanData onSpanExport(SpanData span) {
                                        return new DelegatingSpanData(span) {
                                            @Override
                                            public String getName() {
                                                return "customized";
                                            }

                                            @Override
                                            public SpanKind getKind() {
                                                return SpanKind.CLIENT;
                                            }

                                            @Override
                                            public StatusData getStatus() {
                                                return StatusData.create(
                                                        StatusCode.ERROR, "custom error");
                                            }

                                            @Override
                                            public List<EventData> getEvents() {
                                                return List.of(
                                                        EventData.create(
                                                                123,
                                                                "custom event",
                                                                Attributes.of(KEEP, "event value"),
                                                                3));
                                            }

                                            @Override
                                            public int getTotalRecordedEvents() {
                                                return 4;
                                            }

                                            @Override
                                            public List<LinkData> getLinks() {
                                                return List.of(
                                                        LinkData.create(
                                                                PARENT_CONTEXT,
                                                                Attributes.of(KEEP, "link value"),
                                                                2));
                                            }

                                            @Override
                                            public int getTotalRecordedLinks() {
                                                return 5;
                                            }
                                        };
                                    }
                                })
                        .build();
        var span = exportSpan(config, Attributes.of(KEEP, "span value")).span();
        assertEquals("customized", span.getName());
        assertEquals(
                io.opentelemetry.proto.trace.v1.Span.SpanKind.SPAN_KIND_CLIENT, span.getKind());
        assertEquals(
                io.opentelemetry.proto.trace.v1.Status.StatusCode.STATUS_CODE_ERROR,
                span.getStatus().getCode());
        assertEquals("custom error", span.getStatus().getMessage());
        assertStringAttribute(span, KEEP.getKey(), "span value");
        assertEquals(1, span.getEventsCount());
        assertEquals(3, span.getDroppedEventsCount());
        var event = span.getEvents(0);
        assertEquals(123, event.getTimeUnixNano());
        assertEquals("custom event", event.getName());
        assertEquals("event value", event.getAttributes(0).getValue().getStringValue());
        assertEquals(2, event.getDroppedAttributesCount());
        assertEquals(1, span.getLinksCount());
        assertEquals(4, span.getDroppedLinksCount());
        var link = span.getLinks(0);
        assertEquals(
                PARENT_CONTEXT.getTraceId(),
                HexFormat.of().formatHex(link.getTraceId().toByteArray()));
        assertEquals(
                PARENT_CONTEXT.getSpanId(),
                HexFormat.of().formatHex(link.getSpanId().toByteArray()));
        assertEquals("link value", link.getAttributes(0).getValue().getStringValue());
        assertEquals(1, link.getDroppedAttributesCount());
    }

    @ParameterizedTest
    @EnumSource(InvalidCustomization.class)
    void invalidCustomizerStripsOnlyAffectedSpan(InvalidCustomization invalid) throws Exception {
        var laterCalls = new AtomicInteger();
        var config =
                configBuilder()
                        .addSpanCustomizer(
                                new SpanCustomizer() {
                                    @Override
                                    public SpanData onSpanExport(SpanData span) {
                                        var attributes =
                                                span.getAttributes().toBuilder()
                                                        .put(PARENT, "project_name:customized")
                                                        .put(
                                                                "braintrust.context_json",
                                                                "{\"secret\":\"partial\"}")
                                                        .put("partial", "must not survive")
                                                        .build();
                                        return new DelegatingSpanData(
                                                withAttributes(span, attributes)) {
                                            @Override
                                            public long getStartEpochNanos() {
                                                return 1;
                                            }

                                            @Override
                                            public long getEndEpochNanos() {
                                                return 2;
                                            }
                                        };
                                    }
                                })
                        .addSpanCustomizer(
                                new SpanCustomizer() {
                                    @Override
                                    public SpanData onSpanExport(SpanData span) {
                                        return span.getName().equals("invalid")
                                                ? invalid.customize(span)
                                                : span;
                                    }
                                })
                        .addSpanCustomizer(
                                new SpanCustomizer() {
                                    @Override
                                    public SpanData onSpanExport(SpanData span) {
                                        laterCalls.incrementAndGet();
                                        return span;
                                    }
                                })
                        .build();

        try (var provider =
                        SdkTracerProvider.builder()
                                .setResource(
                                        Resource.create(Attributes.of(KEEP, "secret resource")))
                                .build();
                var exporter = new BraintrustSpanExporter(config)) {
            var tracer =
                    provider.tracerBuilder("secret-scope")
                            .setInstrumentationVersion("secret-version")
                            .setSchemaUrl("https://secret-schema")
                            .build();
            var first =
                    tracer.spanBuilder("valid")
                            .setParent(parentContext())
                            .setAttribute(KEEP, "keep me")
                            .startSpan();
            var second =
                    tracer.spanBuilder("invalid")
                            .setParent(parentContext())
                            .setSpanKind(SpanKind.CLIENT)
                            .addLink(PARENT_CONTEXT, Attributes.of(KEEP, "secret link"))
                            .setAttribute(PARENT, "experiment_id:original")
                            .setAttribute("braintrust.input_json", "{\"secret\":true}")
                            .setAttribute("braintrust.context_json", "{\"secret\":\"original\"}")
                            .setAttribute(KEEP, "secret attribute")
                            .startSpan();
            second.addEvent("secret event", Attributes.of(KEEP, "secret event attribute"));
            second.setStatus(StatusCode.ERROR, "secret status");
            first.end();
            second.end();
            var original = ((ReadableSpan) second).toSpanData();
            var batch = List.of(((ReadableSpan) first).toSpanData(), original);

            // Each submission runs the hooks again, including previously stripped records.
            for (int attempt = 0; attempt < 2; attempt++) {
                var result = exporter.export(batch).join(10, TimeUnit.SECONDS);
                assertTrue(result.isDone(), "Export must finish");
                assertTrue(result.isSuccess(), "A hook failure must not fail the batch");
                for (int group = 0; group < 2; group++) {
                    var request = requests.poll(10, TimeUnit.SECONDS);
                    assertNotNull(request);
                    var exported = request.decodeSpans();
                    assertEquals(1, exported.size());
                    var span = exported.get(0);
                    if (span.getName().equals("valid")) {
                        assertEquals("project_name:customized", request.parentHeader());
                        assertStringAttribute(span, KEEP.getKey(), "keep me");
                    } else {
                        assertEquals("experiment_id:original", request.parentHeader());
                        assertEquals("", span.getName());
                        assertEquals(
                                original.getTraceId(),
                                HexFormat.of().formatHex(span.getTraceId().toByteArray()));
                        assertEquals(
                                original.getSpanId(),
                                HexFormat.of().formatHex(span.getSpanId().toByteArray()));
                        assertEquals(
                                original.getParentSpanId(),
                                HexFormat.of().formatHex(span.getParentSpanId().toByteArray()));
                        assertEquals(original.getStartEpochNanos(), span.getStartTimeUnixNano());
                        assertEquals(original.getEndEpochNanos(), span.getEndTimeUnixNano());
                        assertEquals("", span.getTraceState());
                        assertEquals(
                                io.opentelemetry.proto.trace.v1.Span.SpanKind.SPAN_KIND_INTERNAL,
                                span.getKind());
                        assertEquals(0, span.getEventsCount());
                        assertEquals(0, span.getLinksCount());
                        assertEquals(0, span.getDroppedEventsCount());
                        assertEquals(0, span.getDroppedLinksCount());
                        assertEquals(0, span.getDroppedAttributesCount());
                        assertEquals("", span.getStatus().getMessage());
                        assertEquals(
                                io.opentelemetry.proto.trace.v1.Status.StatusCode.STATUS_CODE_UNSET,
                                span.getStatus().getCode());
                        assertEquals(2, span.getAttributesCount());
                        assertStringAttribute(span, PARENT.getKey(), "experiment_id:original");
                        assertStringAttribute(
                                span, "braintrust.context_json", "{\"customizer_error\":true}");
                        var resource = request.decode().getResourceSpans(0);
                        assertEquals(0, resource.getResource().getAttributesCount());
                        assertEquals("", resource.getSchemaUrl());
                        var scope = resource.getScopeSpans(0);
                        assertEquals("", scope.getSchemaUrl());
                        assertEquals("", scope.getScope().getName());
                        assertEquals("", scope.getScope().getVersion());
                        assertEquals(0, scope.getScope().getAttributesCount());
                    }
                }
            }
            assertEquals(2, laterCalls.get(), "Later hooks run only on the healthy record");
        }
        assertTrue(requests.isEmpty());
    }

    private BraintrustConfig.Builder configBuilder() {
        return BraintrustConfig.builder()
                .apiKey("test-key")
                .apiUrl("https://localhost:" + port)
                .filterAISpans(false)
                .defaultProjectId(null)
                .defaultProjectName("test-project")
                .requestTimeout(Duration.ofSeconds(5))
                .sslContext(clientSslContext)
                .x509TrustManager(clientTrustManager);
    }

    private ExportedSpan exportSpan(BraintrustConfig config, Attributes attributes)
            throws Exception {
        var tracerBuilder = SdkTracerProvider.builder();
        var loggerBuilder = SdkLoggerProvider.builder();
        var meterBuilder = SdkMeterProvider.builder();
        Braintrust.of(config).openTelemetryEnable(tracerBuilder, loggerBuilder, meterBuilder);

        try (var openTelemetry =
                OpenTelemetrySdk.builder()
                        .setTracerProvider(tracerBuilder.build())
                        .setLoggerProvider(loggerBuilder.build())
                        .setMeterProvider(meterBuilder.build())
                        .build()) {
            var span =
                    openTelemetry
                            .getTracer("test-tracer")
                            .spanBuilder("test-span")
                            .setParent(parentContext())
                            .setAllAttributes(attributes)
                            .startSpan();
            var originalContext = span.getSpanContext();
            span.end();

            var result =
                    openTelemetry.getSdkTracerProvider().forceFlush().join(10, TimeUnit.SECONDS);
            assertTrue(result.isDone(), "Force flush must finish");
            assertTrue(result.isSuccess(), "Force flush must succeed");
            assertTrue(
                    requestLatch.await(10, TimeUnit.SECONDS), "Expected an HTTPS export request");
            var request = requests.poll(10, TimeUnit.SECONDS);
            assertNotNull(request, "Expected a complete OTLP request body");
            var spans = request.decodeSpans();
            assertEquals(1, spans.size(), "Expected exactly the manually created span");
            return new ExportedSpan(spans.get(0), originalContext, request.parentHeader());
        }
    }

    private static Context parentContext() {
        return Context.root().with(Span.wrap(PARENT_CONTEXT));
    }

    private static SpanData withAttributes(SpanData span, Attributes attributes) {
        var totalAttributeCount =
                attributes.size()
                        + Math.max(0, span.getTotalAttributeCount() - span.getAttributes().size());
        return new DelegatingSpanData(span) {
            @Override
            public Attributes getAttributes() {
                return attributes;
            }

            @Override
            public int getTotalAttributeCount() {
                return totalAttributeCount;
            }
        };
    }

    private static void assertStringAttribute(
            io.opentelemetry.proto.trace.v1.Span span, String key, String expected) {
        var values =
                span.getAttributesList().stream()
                        .filter(attribute -> attribute.getKey().equals(key))
                        .map(attribute -> attribute.getValue().getStringValue())
                        .toList();
        assertEquals(List.of(expected), values, "Unexpected exported attribute " + key);
    }

    private static void assertNoAttribute(io.opentelemetry.proto.trace.v1.Span span, String key) {
        assertTrue(
                span.getAttributesList().stream()
                        .noneMatch(attribute -> attribute.getKey().equals(key)),
                "Attribute must not be exported: " + key);
    }

    private record ExportedSpan(
            io.opentelemetry.proto.trace.v1.Span span,
            SpanContext originalContext,
            String parentHeader) {}

    private record CapturedRequest(byte[] body, String contentEncoding, String parentHeader) {
        ExportTraceServiceRequest decode() throws Exception {
            byte[] payload = body;
            if ("gzip".equalsIgnoreCase(contentEncoding)) {
                try (var gzip = new GZIPInputStream(new ByteArrayInputStream(body))) {
                    payload = gzip.readAllBytes();
                }
            }
            return ExportTraceServiceRequest.parseFrom(payload);
        }

        List<io.opentelemetry.proto.trace.v1.Span> decodeSpans() throws Exception {
            return decode().getResourceSpansList().stream()
                    .flatMap(resource -> resource.getScopeSpansList().stream())
                    .flatMap(scope -> scope.getSpansList().stream())
                    .toList();
        }
    }

    private enum InvalidCustomization {
        THROWS,
        RETURNS_NULL,
        ATTRIBUTES_THROWS,
        NAME_THROWS,
        LAZY_EVENT_LIST_THROWS,
        EVENT_DATA_THROWS,
        TRACE_ID,
        SPAN_ID,
        PARENT_SPAN_ID,
        CONTEXT_TRACE_ID,
        CONTEXT_SPAN_ID,
        CONTEXT_PARENT_TRACE_ID,
        CONTEXT_PARENT_SPAN_ID;

        SpanData customize(SpanData span) {
            return switch (this) {
                case THROWS -> throw new IllegalStateException("customizer failed");
                case RETURNS_NULL -> null;
                case ATTRIBUTES_THROWS ->
                        new DelegatingSpanData(span) {
                            @Override
                            public Attributes getAttributes() {
                                throw new IllegalStateException("sensitive attributes");
                            }
                        };
                case NAME_THROWS ->
                        new DelegatingSpanData(span) {
                            @Override
                            public String getName() {
                                throw new IllegalStateException("sensitive name");
                            }
                        };
                case LAZY_EVENT_LIST_THROWS ->
                        new DelegatingSpanData(span) {
                            @Override
                            public List<EventData> getEvents() {
                                return new AbstractList<>() {
                                    @Override
                                    public EventData get(int index) {
                                        throw new IllegalStateException("sensitive event list");
                                    }

                                    @Override
                                    public int size() {
                                        return 1;
                                    }
                                };
                            }
                        };
                case EVENT_DATA_THROWS ->
                        new DelegatingSpanData(span) {
                            @Override
                            public List<EventData> getEvents() {
                                return List.of(
                                        new EventData() {
                                            @Override
                                            public String getName() {
                                                throw new IllegalStateException("sensitive event");
                                            }

                                            @Override
                                            public Attributes getAttributes() {
                                                return Attributes.empty();
                                            }

                                            @Override
                                            public long getEpochNanos() {
                                                return 123;
                                            }

                                            @Override
                                            public int getTotalAttributeCount() {
                                                return 0;
                                            }
                                        });
                            }
                        };
                case TRACE_ID ->
                        new DelegatingSpanData(span) {
                            @Override
                            public String getTraceId() {
                                return "ffffffffffffffffffffffffffffffff";
                            }
                        };
                case SPAN_ID ->
                        new DelegatingSpanData(span) {
                            @Override
                            public String getSpanId() {
                                return differentSpanId(span.getSpanId());
                            }
                        };
                case PARENT_SPAN_ID ->
                        new DelegatingSpanData(span) {
                            @Override
                            public String getParentSpanId() {
                                return differentSpanId(span.getParentSpanId());
                            }
                        };
                case CONTEXT_TRACE_ID ->
                        new DelegatingSpanData(span) {
                            @Override
                            public SpanContext getSpanContext() {
                                return SpanContext.create(
                                        "ffffffffffffffffffffffffffffffff",
                                        span.getSpanId(),
                                        span.getSpanContext().getTraceFlags(),
                                        span.getSpanContext().getTraceState());
                            }
                        };
                case CONTEXT_SPAN_ID ->
                        new DelegatingSpanData(span) {
                            @Override
                            public SpanContext getSpanContext() {
                                return SpanContext.create(
                                        span.getTraceId(),
                                        differentSpanId(span.getSpanId()),
                                        span.getSpanContext().getTraceFlags(),
                                        span.getSpanContext().getTraceState());
                            }
                        };
                case CONTEXT_PARENT_TRACE_ID ->
                        new DelegatingSpanData(span) {
                            @Override
                            public SpanContext getParentSpanContext() {
                                return SpanContext.create(
                                        "ffffffffffffffffffffffffffffffff",
                                        span.getParentSpanId(),
                                        TraceFlags.getSampled(),
                                        TraceState.getDefault());
                            }
                        };
                case CONTEXT_PARENT_SPAN_ID ->
                        new DelegatingSpanData(span) {
                            @Override
                            public SpanContext getParentSpanContext() {
                                var parent = span.getParentSpanContext();
                                return SpanContext.create(
                                        parent.getTraceId(),
                                        differentSpanId(parent.getSpanId()),
                                        parent.getTraceFlags(),
                                        parent.getTraceState());
                            }
                        };
            };
        }

        private static String differentSpanId(String original) {
            return original.equals("ffffffffffffffff") ? "eeeeeeeeeeeeeeee" : "ffffffffffffffff";
        }
    }
}
