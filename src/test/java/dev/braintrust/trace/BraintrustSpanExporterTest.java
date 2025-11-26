package dev.braintrust.trace;

import static org.junit.jupiter.api.Assertions.*;

import com.sun.net.httpserver.HttpsConfigurator;
import com.sun.net.httpserver.HttpsServer;
import dev.braintrust.Braintrust;
import dev.braintrust.config.BraintrustConfig;
import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.logs.SdkLoggerProvider;
import io.opentelemetry.sdk.metrics.SdkMeterProvider;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import java.io.FileInputStream;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.security.*;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.net.ssl.*;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

public class BraintrustSpanExporterTest {
    private HttpsServer server;
    private SSLContext serverSslContext;
    private SSLContext clientSslContext;
    private X509TrustManager clientTrustManager;
    private int port;
    private AtomicBoolean requestReceived;
    private CountDownLatch requestLatch;
    private java.nio.file.Path keystoreFile;

    @BeforeEach
    void setUp() throws Exception {
        GlobalOpenTelemetry.resetForTest();

        requestReceived = new AtomicBoolean(false);
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
        int exitCode = keytoolProcess.waitFor();
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
                    requestReceived.set(true);
                    requestLatch.countDown();
                    // Return 200 OK
                    exchange.sendResponseHeaders(200, 0);
                    exchange.getResponseBody().close();
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
        // Create config with custom SSL context
        var config =
                BraintrustConfig.builder()
                        .apiKey("test-key")
                        .apiUrl("https://localhost:" + port)
                        .defaultProjectName("test-project")
                        .requestTimeout(Duration.ofSeconds(5))
                        .sslContext(clientSslContext)
                        .x509TrustManager(clientTrustManager)
                        .build();

        // Verify config stores the SSL context and trust manager
        assertNotNull(config.sslContext());
        assertNotNull(config.x509TrustManager());
        assertSame(clientSslContext, config.sslContext());
        assertSame(clientTrustManager, config.x509TrustManager());

        // Set up OpenTelemetry with Braintrust
        var tracerBuilder = SdkTracerProvider.builder();
        var loggerBuilder = SdkLoggerProvider.builder();
        var meterBuilder = SdkMeterProvider.builder();

        var braintrust = Braintrust.of(config);
        braintrust.openTelemetryEnable(tracerBuilder, loggerBuilder, meterBuilder);

        var openTelemetry =
                OpenTelemetrySdk.builder()
                        .setTracerProvider(tracerBuilder.build())
                        .setLoggerProvider(loggerBuilder.build())
                        .setMeterProvider(meterBuilder.build())
                        .build();

        // Create and export a span
        Tracer tracer = openTelemetry.getTracer("test-tracer");
        var span = tracer.spanBuilder("test-span").startSpan();
        try {
            span.setAttribute("test-attr", "test-value");
        } finally {
            span.end();
        }

        // Force flush to ensure span is exported
        openTelemetry.getSdkTracerProvider().forceFlush().join(10, TimeUnit.SECONDS);

        // Wait for the request to reach our test server
        boolean received = requestLatch.await(10, TimeUnit.SECONDS);
        assertTrue(
                received,
                "Expected span export request to reach test HTTPS server with custom SSL context");
        assertTrue(
                requestReceived.get(),
                "Expected request handler to be invoked on test HTTPS server");
    }
}
