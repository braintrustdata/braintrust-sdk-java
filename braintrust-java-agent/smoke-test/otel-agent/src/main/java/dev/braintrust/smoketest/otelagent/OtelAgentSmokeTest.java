package dev.braintrust.smoketest.otelagent;

import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.Tracer;
import java.util.concurrent.TimeUnit;

/** Smoke test for running the Braintrust agent alongside the OTel Java agent. */
public class OtelAgentSmokeTest {

    static final int OTEL_MOCK_PORT =
            Integer.getInteger("braintrust.smoketest.otel.mock.port", 18240);
    static final int BT_MOCK_PORT = Integer.getInteger("braintrust.smoketest.bt.mock.port", 18241);

    static final String SPAN_NAME = "smoke-test-span";

    public static void main(String[] args) throws Exception {
        final boolean isRunningWithBraintrustJavaagent = checkIsRunningWithBraintrustJavaagent();
        System.out.println("[otel-agent-smoke-test] starting");
        System.out.println(
                "[otel-agent-smoke-test] GlobalOpenTelemetry impl: "
                        + GlobalOpenTelemetry.get().getClass().getName()
                        + ", TracerProvider: "
                        + GlobalOpenTelemetry.get().getTracerProvider().getClass().getName());

        // Start mock collectors before creating any spans so nothing is missed.
        // OTel agent exports to /v1/traces; BT agent exports to /otel/v1/traces.
        var otelCollector =
                new MockOtlpCollector("otel-collector", "/v1/traces", 1, OTEL_MOCK_PORT);
        otelCollector.start();
        var btCollector = new MockOtlpCollector("bt-backend", "/otel/v1/traces", 1, BT_MOCK_PORT);
        if (isRunningWithBraintrustJavaagent) {
            btCollector.start();
        }
        Runtime.getRuntime()
                .addShutdownHook(
                        new Thread(
                                () -> {
                                    try {
                                        // sleep is not necessary, but this reduces the chances of a
                                        // misleading stack trace being printed due to the otel
                                        // agent trying to send spans after the collector has shut
                                        // down
                                        Thread.sleep(100);
                                    } catch (InterruptedException e) {
                                    }
                                    otelCollector.stop();
                                    if (isRunningWithBraintrustJavaagent) {
                                        btCollector.stop();
                                    }
                                },
                                "collector-shutdown"));
        assertTrue(
                GlobalOpenTelemetry.get()
                        .getClass()
                        .getName()
                        .startsWith("io.opentelemetry.javaagent.instrumentation.opentelemetryapi."),
                "global otel must use agent's otel instance");
        createSpan();
        if (isRunningWithBraintrustJavaagent) {
            assertBraintrustBackend(btCollector);
        }
        assertOtelCollector(otelCollector);
        System.out.println("[otel-agent-smoke-test] PASSED");
        System.exit(0);
    }

    private static void createSpan() {
        Tracer tracer = GlobalOpenTelemetry.get().getTracer("otel-agent-smoke-test");
        Span span =
                tracer.spanBuilder(SPAN_NAME)
                        .setSpanKind(SpanKind.INTERNAL)
                        .setAttribute("test.source", "otel-agent-smoke-test")
                        // set bt attribute to pass ai spans filter
                        .setAttribute("braintrust.whatever", "foobar")
                        .startSpan();
        try (var ignored = span.makeCurrent()) {
            System.out.println(
                    "[otel-agent-smoke-test] span started: traceId="
                            + span.getSpanContext().getTraceId()
                            + " spanId="
                            + span.getSpanContext().getSpanId());
        } finally {
            span.end();
        }
        System.out.println("[otel-agent-smoke-test] span ended");
    }

    // ── OTel collector assertions ────────────────────────────────────────────

    private static void assertOtelCollector(MockOtlpCollector collector) throws Exception {
        System.out.println("[otel-agent-smoke-test] waiting for OTel collector...");
        boolean received = collector.awaitSpans(30, TimeUnit.SECONDS);
        assertTrue(received, "timed out waiting for OTel agent to export spans to mock collector");

        var spans = collector.getAllSpans();
        System.out.println(
                "[otel-agent-smoke-test] OTel collector received "
                        + collector.batchCount()
                        + " batch(es), "
                        + spans.size()
                        + " span(s)");
        assertTrue(!spans.isEmpty(), "OTel collector received no spans");

        var span = collector.findSpanByName(SPAN_NAME);
        assertNotNull(span, "OTel collector missing span with name '" + SPAN_NAME + "'");

        assertTrue(
                span.traceId != null && !span.traceId.replace("0", "").isEmpty(),
                "span has zero/empty traceId: " + span.traceId);
        assertTrue(span.startTimeUnixNano > 0, "span startTimeUnixNano <= 0");
        assertTrue(
                span.endTimeUnixNano > span.startTimeUnixNano,
                "span endTimeUnixNano <= startTimeUnixNano");

        System.out.println("[otel-agent-smoke-test] OTel collector assertions: OK");
    }

    // ── Braintrust backend assertions (not yet enabled) ──────────────────────

    private static boolean checkIsRunningWithBraintrustJavaagent() {
        try {
            ClassLoader.getSystemClassLoader().loadClass("dev.braintrust.system.AgentBootstrap");
            return true;
        } catch (ClassNotFoundException e) {
            return false;
        }
    }

    private static void assertBraintrustBackend(MockOtlpCollector backend) throws Exception {
        System.out.println("[otel-agent-smoke-test] waiting for Braintrust backend...");
        boolean received = backend.awaitSpans(30, TimeUnit.SECONDS);
        assertTrue(received, "timed out waiting for Braintrust agent to export spans");

        var spans = backend.getAllSpans();
        assertTrue(!spans.isEmpty(), "Braintrust backend received no spans");

        var span = backend.findSpanByName(SPAN_NAME);
        assertNotNull(span, "Braintrust backend missing span with name '" + SPAN_NAME + "'");

        System.out.println("[otel-agent-smoke-test] Braintrust backend assertions: OK");
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private static void assertTrue(boolean condition, String message) {
        if (!condition) {
            var e = new RuntimeException(message);
            e.printStackTrace();
            System.exit(1);
        }
    }

    private static void assertNotNull(Object obj, String message) {
        assertTrue(obj != null, message);
    }
}
