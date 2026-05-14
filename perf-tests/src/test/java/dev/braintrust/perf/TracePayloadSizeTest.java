package dev.braintrust.perf;

import static org.junit.jupiter.api.Assertions.*;

import com.sun.net.httpserver.HttpServer;
import dev.braintrust.TestHarness;
import dev.braintrust.instrumentation.Instrumenter;
import dev.langchain4j.data.message.*;
import dev.langchain4j.memory.chat.MessageWindowChatMemory;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.openai.OpenAiChatModel;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import javax.imageio.ImageIO;
import net.bytebuddy.agent.ByteBuddyAgent;
import org.junit.jupiter.api.*;

/**
 * Measures the wire-format (OTLP protobuf over HTTP) payload size produced by {@code
 * BraintrustSpanExporter} for realistic multi-turn LangChain4j conversations.
 *
 * <p>The test stands up a plain HTTP capture server on {@code /otel/v1/traces}, configures the
 * Braintrust SDK to export there (while OpenAI calls go through the TestHarness VCR proxy), runs a
 * multi-turn conversation, flushes, and records how many bytes the capture server received.
 *
 * <p>To add new scenarios, create additional {@link PerfRunConfig} instances and call {@link
 * #runScenario}.
 */
public class TracePayloadSizeTest {

    /**
     * Base64-encoded JPEG generated at class load time. 512x512 with random pixel noise to simulate
     * a realistic photo payload (~150KB raw, ~210K base64 chars).
     */
    private static final String TEST_IMAGE_BASE64 = generateTestImageBase64(512, 512);

    private HttpServer captureServer;
    private int capturePort;

    /** Accumulated byte count across all requests for a single scenario. */
    private AtomicLong capturedBytes;

    /** Number of HTTP requests received. */
    private AtomicInteger requestCount;

    /**
     * Latch that fires when at least one export request arrives. We use this to know when the
     * exporter has sent data, then wait a grace period for any trailing batches.
     */
    private CountDownLatch exportLatch;

    /** All results collected during the test class, printed in @AfterEach for comparison. */
    private final List<PerfResult> results = new ArrayList<>();

    @BeforeAll
    static void installInstrumentation() {
        var instrumentation = ByteBuddyAgent.install();
        Instrumenter.install(instrumentation, TracePayloadSizeTest.class.getClassLoader());
    }

    @BeforeEach
    void setUp() throws IOException {
        capturedBytes = new AtomicLong();
        requestCount = new AtomicInteger();
        exportLatch = new CountDownLatch(1);

        captureServer = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        capturePort = captureServer.getAddress().getPort();

        // ── OTLP trace export endpoint (the one we're measuring) ──
        captureServer.createContext(
                "/otel/v1/traces",
                exchange -> {
                    try {
                        byte[] body = exchange.getRequestBody().readAllBytes();
                        capturedBytes.addAndGet(body.length);
                        requestCount.incrementAndGet();
                        exportLatch.countDown();
                        exchange.sendResponseHeaders(200, 0);
                    } finally {
                        exchange.close();
                    }
                });

        // ── Attachment upload flow stubs ──
        // The SDK's AttachmentProcessor extracts base64 data URIs from span attributes,
        // replaces them with attachment references, and uploads the data via:
        //   1. POST /api/apikey/login       -> resolve org ID
        //   2. POST /attachment             -> get a signed upload URL
        //   3. PUT  /s3-upload              -> upload data to the signed URL
        //   4. POST /attachment/status      -> report upload status

        var loginResponse =
                "{\"org_info\": [{\"id\": \"00000000-0000-0000-0000-000000000000\","
                        + " \"name\": \"perf-test-org\"}]}";
        captureServer.createContext(
                "/api/apikey/login",
                exchange -> {
                    try {
                        exchange.getRequestBody().readAllBytes(); // drain
                        var body = loginResponse.getBytes();
                        exchange.getResponseHeaders().set("Content-Type", "application/json");
                        exchange.sendResponseHeaders(200, body.length);
                        exchange.getResponseBody().write(body);
                    } finally {
                        exchange.close();
                    }
                });

        var signedUrl = "http://localhost:" + capturePort + "/s3-upload";
        var attachmentResponse = "{\"signedUrl\": \"" + signedUrl + "\", \"headers\": {}}";
        captureServer.createContext(
                "/attachment",
                exchange -> {
                    try {
                        exchange.getRequestBody().readAllBytes(); // drain
                        var body = attachmentResponse.getBytes();
                        exchange.getResponseHeaders().set("Content-Type", "application/json");
                        exchange.sendResponseHeaders(200, body.length);
                        exchange.getResponseBody().write(body);
                    } finally {
                        exchange.close();
                    }
                });

        captureServer.createContext(
                "/s3-upload",
                exchange -> {
                    try {
                        exchange.getRequestBody().readAllBytes(); // drain
                        exchange.sendResponseHeaders(200, -1);
                    } finally {
                        exchange.close();
                    }
                });

        captureServer.start();
    }

    @AfterEach
    void tearDown() {
        if (captureServer != null) {
            captureServer.stop(0);
        }
        if (!results.isEmpty()) {
            System.out.println("\n=== Perf Results ===");
            for (PerfResult r : results) {
                System.out.println(r.summary());
            }
            System.out.println("====================\n");
        }
    }

    @Test
    void multiTurnWithAttachment() throws Exception {
        var result = runScenario(PerfRunConfig.multiTurnWithAttachment());
        results.add(result);
        System.out.println(result.summary());

        assertTrue(result.requestCount() > 0, "Expected at least one HTTP request");
        assertTrue(result.payloadBytes() > 0, "Expected non-empty payload");
        assertTrue(result.spanCount() > 0, "Expected at least one span");
    }

    @Test
    @Disabled
    void multiTurnTextOnly() throws Exception {
        var result = runScenario(PerfRunConfig.multiTurnTextOnly());
        results.add(result);
        System.out.println(result.summary());

        assertTrue(result.requestCount() > 0, "Expected at least one HTTP request");
        assertTrue(result.payloadBytes() > 0, "Expected non-empty payload");
        assertTrue(result.spanCount() > 0, "Expected at least one span");
    }

    // ─── Core ───────────────────────────────────────────────────────────────────

    /**
     * Runs a single scenario: sets up the SDK with the capture server as the Braintrust API
     * endpoint, builds a LangChain4j ChatModel via the VCR-proxied OpenAI, runs a multi-turn
     * conversation, flushes, and returns the measured result.
     */
    private PerfResult runScenario(PerfRunConfig config) throws Exception {
        capturedBytes.set(0);
        requestCount.set(0);
        exportLatch = new CountDownLatch(1);

        // TestHarness sets up:
        //   - VCR proxy for OpenAI (testHarness.openAiBaseUrl())
        //   - Braintrust SDK with BraintrustSpanExporter pointing at config.apiUrl()
        //   - UnitTestSpanExporter for in-memory span capture
        //
        // We override apiUrl to point at our capture server so the exporter sends there.
        var testHarness =
                TestHarness.setup(
                        cfg ->
                                cfg.apiUrl("http://localhost:" + capturePort)
                                        .autoConvertAIAttachments(true)
                                        .compressOtelPayload(true));

        // Build the OpenAI-backed ChatModel. ByteBuddy auto-instrumentation intercepts
        // OpenAiChatModel.Builder.build() and wraps the internal HttpClient with
        // WrappedHttpClient, which creates OTel spans for each LLM call.
        ChatModel model =
                OpenAiChatModel.builder()
                        .apiKey(testHarness.openAiApiKey())
                        .baseUrl(testHarness.openAiBaseUrl())
                        .modelName("gpt-4o-mini")
                        .temperature(0.0)
                        .build();

        // Chat memory to accumulate conversation history across turns
        var memory = MessageWindowChatMemory.withMaxMessages(20);

        try {
            runConversation(testHarness, model, memory, config);

            // Flush spans through BatchSpanProcessor → BraintrustSpanExporter → capture server
            var flushResult =
                    testHarness
                            .openTelemetry()
                            .getSdkTracerProvider()
                            .forceFlush()
                            .join(30, TimeUnit.SECONDS);

            assertTrue(flushResult.isDone());
            assertTrue(flushResult.isSuccess());

            boolean received = exportLatch.await(15, TimeUnit.SECONDS);
            assertTrue(received, "Timed out waiting for span export for: " + config.name());

            // Grace period for any trailing batch exports to be seen by the server
            Thread.sleep(1_000);

            // Get span count from the in-memory exporter
            var spans = testHarness.awaitExportedSpans();
            int spanCount = spans.size();

            return new PerfResult(config, capturedBytes.get(), spanCount, requestCount.get());
        } finally {
            testHarness.openTelemetry().getSdkTracerProvider().shutdown().join(5, TimeUnit.SECONDS);
        }
    }

    /** Runs a multi-turn conversation under a single root span. */
    private void runConversation(
            TestHarness testHarness,
            ChatModel model,
            MessageWindowChatMemory memory,
            PerfRunConfig config) {
        var tracer = testHarness.openTelemetry().getTracer("perf-test");
        var rootSpan = tracer.spanBuilder("multi-turn-conversation").startSpan();

        try (var ignored = rootSpan.makeCurrent()) {
            String[] userPrompts = {
                "Tell me a story about a wise cracking talking dog.", "tell me another story",
            };

            for (int turn = 0; turn < config.turns(); turn++) {
                UserMessage userMessage;
                var userPrompt = userPrompts[Math.min(turn, userPrompts.length - 1)];

                if (turn == 0 && config.includeImageAttachment()) {
                    // First turn includes an image attachment alongside the text
                    userMessage =
                            UserMessage.from(
                                    TextContent.from(
                                            userPrompt + " -- take inspiration from this picture"),
                                    ImageContent.from(TEST_IMAGE_BASE64, "image/jpeg"));
                } else {
                    userMessage = UserMessage.from(userPrompt);
                }

                memory.add(userMessage);

                var response = model.chat(memory.messages());
                var aiMessage = response.aiMessage();

                memory.add(aiMessage);
            }
        } finally {
            rootSpan.end();
        }
    }

    /**
     * Generates a JPEG image with random pixel noise and returns it as a base64 string. A fixed
     * seed ensures the output is deterministic across runs.
     */
    private static String generateTestImageBase64(int width, int height) {
        var img = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        var rng = new java.util.Random(42);
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                img.setRGB(x, y, rng.nextInt(0xFFFFFF));
            }
        }
        try {
            var baos = new ByteArrayOutputStream();
            ImageIO.write(img, "JPEG", baos);
            return Base64.getEncoder().encodeToString(baos.toByteArray());
        } catch (IOException e) {
            throw new RuntimeException("Failed to generate test image", e);
        }
    }
}
