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
import io.opentelemetry.api.trace.TraceFlags;
import io.opentelemetry.api.trace.TraceState;
import io.opentelemetry.context.Context;
import io.opentelemetry.proto.collector.trace.v1.ExportTraceServiceRequest;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.logs.SdkLoggerProvider;
import io.opentelemetry.sdk.metrics.SdkMeterProvider;
import io.opentelemetry.sdk.trace.ReadableSpan;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.data.DelegatingSpanData;
import io.opentelemetry.sdk.trace.data.SpanData;
import java.io.ByteArrayInputStream;
import java.io.FileInputStream;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.security.*;
import java.time.Duration;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
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

    @ParameterizedTest
    @EnumSource(InvalidCustomization.class)
    void invalidCustomizerFailsEntireBatchBeforeTransport(InvalidCustomization invalid)
            throws Exception {
        var config =
                configBuilder()
                        .addSpanCustomizer(
                                new SpanCustomizer() {
                                    @Override
                                    public SpanData onSpanExport(SpanData span) {
                                        if (span.getName().equals("invalid")) {
                                            return invalid.customize(span);
                                        }
                                        return withAttributes(
                                                span,
                                                span.getAttributes().toBuilder()
                                                        .put(PARENT, "project_name:customized")
                                                        .build());
                                    }
                                })
                        .build();

        try (var provider = SdkTracerProvider.builder().build();
                var exporter = new BraintrustSpanExporter(config)) {
            var tracer = provider.get("test-tracer");
            var first = tracer.spanBuilder("valid").setParent(parentContext()).startSpan();
            var second = tracer.spanBuilder("invalid").setParent(parentContext()).startSpan();
            first.end();
            second.end();

            var result =
                    exporter.export(
                                    List.of(
                                            ((ReadableSpan) first).toSpanData(),
                                            ((ReadableSpan) second).toSpanData()))
                            .join(10, TimeUnit.SECONDS);
            assertTrue(result.isDone(), "Export must finish");
            assertFalse(result.isSuccess(), "Invalid customization must fail the entire batch");
        }
        assertNull(
                requests.poll(300, TimeUnit.MILLISECONDS),
                "No span from a failed batch may reach HTTP transport");
        assertTrue(requests.isEmpty(), "No additional export requests are allowed");
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
        List<io.opentelemetry.proto.trace.v1.Span> decodeSpans() throws Exception {
            byte[] payload = body;
            if ("gzip".equalsIgnoreCase(contentEncoding)) {
                try (var gzip = new GZIPInputStream(new ByteArrayInputStream(body))) {
                    payload = gzip.readAllBytes();
                }
            }
            return ExportTraceServiceRequest.parseFrom(payload).getResourceSpansList().stream()
                    .flatMap(resource -> resource.getScopeSpansList().stream())
                    .flatMap(scope -> scope.getSpansList().stream())
                    .toList();
        }
    }

    private enum InvalidCustomization {
        THROWS,
        RETURNS_NULL,
        TRACE_ID,
        SPAN_ID,
        PARENT_SPAN_ID,
        CONTEXT_TRACE_ID,
        CONTEXT_SPAN_ID,
        CONTEXT_PARENT_SPAN_ID;

        SpanData customize(SpanData span) {
            return switch (this) {
                case THROWS -> throw new IllegalStateException("customizer failed");
                case RETURNS_NULL -> null;
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
