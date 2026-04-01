package dev.braintrust.smoketest.ddagent;

import datadog.trace.api.Trace;
import dev.braintrust.smoketest.ddagent.MockBraintrustBackend.OtlpSpan;
import dev.braintrust.smoketest.ddagent.MockDdAgentServer.DdSpan;
import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.Tracer;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Smoke test that runs with the Datadog agent (WITHOUT OTel compatibility mode) and the Braintrust
 * agent. Verifies that:
 *
 * <ol>
 *   <li>DD-native spans ({@code @Trace}) are sent to the DD backend only
 *   <li>OTel API spans are sent to the Braintrust backend only
 *   <li>Neither system receives the other's spans
 * </ol>
 */
public class DdNoOtelSmokeTest {

    static final int MOCK_DD_AGENT_PORT =
            Integer.getInteger("braintrust.smoketest.dd.mock.port", 18126);

    static final int MOCK_BT_BACKEND_PORT =
            Integer.getInteger("braintrust.smoketest.bt.mock.port", 18127);

    /** True if the Braintrust agent is attached. */
    static final boolean BT_AGENT_ENABLED = isBraintrustAgentPresent();

    /** If set, asserts that BT agent detection matches the expected value. */
    static final Boolean BT_AGENT_EXPECTED = parseBooleanEnv("BT_AGENT_EXPECTED");

    /** DD @Trace span resource names — used to verify isolation. */
    private static final String DD_ROOT_RESOURCE = "dd-traced-root";

    private static final String DD_CHILD_RESOURCE = "dd-traced-child";

    /** OTel span names — used to verify isolation. */
    private static final String OTEL_ROOT_NAME = "otel-root-span";

    private static final String OTEL_CHILD_NAME = "otel-child-span";

    public static void main(String[] args) throws Exception {
        assertBtAgentDetection();

        System.out.println(
                "[no-otel-smoke-test] Braintrust agent: "
                        + (BT_AGENT_ENABLED
                                ? "ENABLED"
                                : "NOT DETECTED — skipping braintrust assertions"));

        var mockDdServer = new MockDdAgentServer(1, MOCK_DD_AGENT_PORT);
        mockDdServer.start();

        var mockBtBackend = new MockBraintrustBackend(0, MOCK_BT_BACKEND_PORT);
        mockBtBackend.start();

        try {
            assertOtelShimNotInstalled();

            // Create DD-native spans via @Trace
            exerciseTracedMethods();

            // Create OTel API spans
            exerciseOtelSpans();

            // Wait for DD to flush
            boolean ddReceived = mockDdServer.awaitTraces(30, TimeUnit.SECONDS);
            assertTrue(ddReceived, "timed out waiting for DD trace payload on mock server");

            var ddSpans = mockDdServer.getAllSpans();
            System.out.println(
                    "[no-otel-smoke-test] DD mock received %d span(s)".formatted(ddSpans.size()));

            // Assert DD got the @Trace spans
            assertDdTracedSpans(ddSpans);

            // Assert DD did NOT get the OTel spans
            assertDdDoesNotHaveOtelSpans(ddSpans);

            if (BT_AGENT_ENABLED) {
                // Poll until the expected OTel spans arrive (the BT exporter batches
                // and may send multiple requests, so a simple latch count isn't reliable).
                List<OtlpSpan> btSpans = pollForSpan(mockBtBackend, OTEL_ROOT_NAME, 10);

                System.out.println(
                        "[no-otel-smoke-test] BT mock received %d span(s)"
                                .formatted(btSpans.size()));

                // Assert BT got the OTel spans
                assertBtOtelSpans(btSpans);

                // Assert BT did NOT get the DD @Trace spans
                assertBtDoesNotHaveDdSpans(btSpans);
            } else {
                System.out.println(
                        "[no-otel-smoke-test] BT backend received %d trace request(s) (no assertions)"
                                .formatted(mockBtBackend.traceRequestCount()));
            }

            System.out.println("=== No-OTel smoke test passed ===");
        } finally {
            mockDdServer.stop();
            mockBtBackend.stop();
        }
    }

    /**
     * Verify that without {@code dd.trace.otel.enabled=true}, the DD OTel shim is NOT installed.
     */
    private static void assertOtelShimNotInstalled() {
        OpenTelemetry otel = GlobalOpenTelemetry.get();
        String tpClass = otel.getTracerProvider().getClass().getName();
        if (tpClass.contains("datadog")) {
            throw new RuntimeException(
                    "DD OTel shim should NOT be installed in no-otel mode, but TracerProvider is: "
                            + tpClass);
        }
        System.out.println(
                "[no-otel-smoke-test] OTel shim not installed: OK (TracerProvider=%s)"
                        .formatted(tpClass));
    }

    // ── DD @Trace-annotated methods ────────────────────────────────────────────

    private static void exerciseTracedMethods() {
        tracedRootOperation();
    }

    @Trace(operationName = "root.operation", resourceName = DD_ROOT_RESOURCE)
    private static void tracedRootOperation() {
        tracedChildOperation();
    }

    @Trace(operationName = "child.operation", resourceName = DD_CHILD_RESOURCE)
    private static void tracedChildOperation() {
        try {
            Thread.sleep(5);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    // ── OTel API spans ─────────────────────────────────────────────────────────

    private static void exerciseOtelSpans() {
        OpenTelemetry otel = GlobalOpenTelemetry.get();
        Tracer tracer = otel.getTracer("braintrust-no-otel-smoke-test");

        Span root =
                tracer.spanBuilder(OTEL_ROOT_NAME)
                        .setSpanKind(SpanKind.INTERNAL)
                        .setAttribute("test.source", "no-otel-smoke-test")
                        .startSpan();
        try (var ignored = root.makeCurrent()) {
            Span child =
                    tracer.spanBuilder(OTEL_CHILD_NAME)
                            .setSpanKind(SpanKind.CLIENT)
                            .setAttribute("test.child", true)
                            .startSpan();
            try (var ignored2 = child.makeCurrent()) {
                try {
                    Thread.sleep(5);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            } finally {
                child.end();
            }
        } finally {
            root.end();
        }
    }

    // ── DD assertions ──────────────────────────────────────────────────────────

    private static void assertDdTracedSpans(List<DdSpan> ddSpans) {
        var errors = new ArrayList<String>();

        DdSpan root = findDdSpanByResource(ddSpans, DD_ROOT_RESOURCE);
        DdSpan child = findDdSpanByResource(ddSpans, DD_CHILD_RESOURCE);

        if (root == null) errors.add("missing DD span with resource='" + DD_ROOT_RESOURCE + "'");
        if (child == null) errors.add("missing DD span with resource='" + DD_CHILD_RESOURCE + "'");
        if (!errors.isEmpty()) {
            throw new RuntimeException(
                    "DD @Trace span assertions:\n  "
                            + String.join("\n  ", errors)
                            + "\n  available: "
                            + ddSpans);
        }

        if (!"root.operation".equals(root.name)) {
            errors.add(
                    "root operation: expected='root.operation' actual='%s'".formatted(root.name));
        }
        if (!"child.operation".equals(child.name)) {
            errors.add(
                    "child operation: expected='child.operation' actual='%s'"
                            .formatted(child.name));
        }
        if (!"bt-dd-smoke-test".equals(root.service)) {
            errors.add(
                    "root service: expected='bt-dd-smoke-test' actual='%s'"
                            .formatted(root.service));
        }
        if (child.traceId != root.traceId) {
            errors.add("traceId mismatch: root=%d child=%d".formatted(root.traceId, child.traceId));
        }
        if (child.parentId != root.spanId) {
            errors.add(
                    "child parentId: expected=%d actual=%d".formatted(root.spanId, child.parentId));
        }
        if (root.duration <= 0) {
            errors.add("root duration <= 0: %d".formatted(root.duration));
        }
        if (child.duration <= 0) {
            errors.add("child duration <= 0: %d".formatted(child.duration));
        }

        if (!errors.isEmpty()) {
            throw new RuntimeException(
                    "DD @Trace span assertions:\n  " + String.join("\n  ", errors));
        }
        System.out.println("[no-otel-smoke-test] DD @Trace spans: OK");
    }

    /** Verify DD mock did NOT receive any OTel-originated spans. */
    private static void assertDdDoesNotHaveOtelSpans(List<DdSpan> ddSpans) {
        for (var span : ddSpans) {
            if (OTEL_ROOT_NAME.equals(span.resource) || OTEL_CHILD_NAME.equals(span.resource)) {
                throw new RuntimeException(
                        "DD mock should NOT contain OTel span, but found: " + span);
            }
            // Also check the name field (DD operation name)
            if (OTEL_ROOT_NAME.equals(span.name) || OTEL_CHILD_NAME.equals(span.name)) {
                throw new RuntimeException(
                        "DD mock should NOT contain OTel span, but found: " + span);
            }
        }
        System.out.println("[no-otel-smoke-test] DD isolation (no OTel spans leaked to DD): OK");
    }

    // ── Braintrust assertions ──────────────────────────────────────────────────

    private static void assertBtOtelSpans(List<OtlpSpan> btSpans) {
        var errors = new ArrayList<String>();

        OtlpSpan root = findOtlpSpanByName(btSpans, OTEL_ROOT_NAME);
        OtlpSpan child = findOtlpSpanByName(btSpans, OTEL_CHILD_NAME);

        if (root == null) errors.add("missing BT span with name='" + OTEL_ROOT_NAME + "'");
        if (child == null) errors.add("missing BT span with name='" + OTEL_CHILD_NAME + "'");
        if (!errors.isEmpty()) {
            throw new RuntimeException(
                    "BT OTel span assertions:\n  "
                            + String.join("\n  ", errors)
                            + "\n  available: "
                            + btSpans);
        }

        if (!"no-otel-smoke-test".equals(root.stringAttr("test.source"))) {
            errors.add(
                    "root test.source: expected='no-otel-smoke-test' actual='%s'"
                            .formatted(root.stringAttr("test.source")));
        }
        if (!Boolean.TRUE.equals(child.boolAttr("test.child"))) {
            errors.add(
                    "child test.child: expected=true actual=%s"
                            .formatted(child.boolAttr("test.child")));
        }
        if (!child.traceId.equals(root.traceId)) {
            errors.add("traceId mismatch: root=%s child=%s".formatted(root.traceId, child.traceId));
        }
        if (!child.parentSpanId.equals(root.spanId)) {
            errors.add(
                    "child parentSpanId: expected=%s actual=%s"
                            .formatted(root.spanId, child.parentSpanId));
        }
        if (root.startTimeUnixNano <= 0) {
            errors.add("root startTime <= 0");
        }
        if (child.startTimeUnixNano <= 0) {
            errors.add("child startTime <= 0");
        }

        if (!errors.isEmpty()) {
            throw new RuntimeException(
                    "BT OTel span assertions:\n  " + String.join("\n  ", errors));
        }
        System.out.println("[no-otel-smoke-test] BT OTel spans: OK");
    }

    /** Verify Braintrust mock did NOT receive any DD @Trace-originated spans. */
    private static void assertBtDoesNotHaveDdSpans(List<OtlpSpan> btSpans) {
        for (var span : btSpans) {
            if (DD_ROOT_RESOURCE.equals(span.name) || DD_CHILD_RESOURCE.equals(span.name)) {
                throw new RuntimeException(
                        "BT mock should NOT contain DD @Trace span, but found: " + span);
            }
        }
        System.out.println("[no-otel-smoke-test] BT isolation (no DD spans leaked to BT): OK");
    }

    // ── Helpers ─────────────────────────────────────────────────────────────────

    private static DdSpan findDdSpanByResource(List<DdSpan> spans, String resource) {
        for (var span : spans) {
            if (resource.equals(span.resource)) {
                return span;
            }
        }
        return null;
    }

    private static OtlpSpan findOtlpSpanByName(List<OtlpSpan> spans, String name) {
        for (var span : spans) {
            if (name.equals(span.name)) {
                return span;
            }
        }
        return null;
    }

    /**
     * Polls the BT backend until a span with the given name appears, or timeout. Returns all spans
     * collected at that point.
     */
    private static List<OtlpSpan> pollForSpan(
            MockBraintrustBackend backend, String spanName, int timeoutSeconds) throws Exception {
        long deadline = System.currentTimeMillis() + timeoutSeconds * 1000L;
        while (System.currentTimeMillis() < deadline) {
            var spans = backend.getAllSpans();
            if (findOtlpSpanByName(spans, spanName) != null) {
                return spans;
            }
            Thread.sleep(200);
        }
        throw new RuntimeException(
                "timed out waiting for BT span '%s' (received %d spans so far: %s)"
                        .formatted(spanName, backend.getAllSpans().size(), backend.getAllSpans()));
    }

    private static void assertTrue(boolean condition, String failMessage) {
        if (!condition) {
            throw new RuntimeException(failMessage);
        }
    }

    private static void assertBtAgentDetection() {
        if (BT_AGENT_EXPECTED != null) {
            if (BT_AGENT_EXPECTED && !BT_AGENT_ENABLED) {
                throw new RuntimeException(
                        "BT_AGENT_EXPECTED=true but agent was NOT detected."
                                + " Detection logic may be broken.");
            }
            if (!BT_AGENT_EXPECTED && BT_AGENT_ENABLED) {
                throw new RuntimeException(
                        "BT_AGENT_EXPECTED=false but agent WAS detected."
                                + " Agent may be leaking onto the classpath.");
            }
            System.out.println(
                    "[no-otel-smoke-test] BT agent detection: OK (expected=%s, detected=%s)"
                            .formatted(BT_AGENT_EXPECTED, BT_AGENT_ENABLED));
        }
    }

    private static Boolean parseBooleanEnv(String name) {
        String value = System.getenv(name);
        return value != null ? Boolean.parseBoolean(value) : null;
    }

    private static boolean isBraintrustAgentPresent() {
        try {
            Class.forName(
                    "dev.braintrust.system.AgentBootstrap",
                    false,
                    ClassLoader.getSystemClassLoader());
            return true;
        } catch (Exception e) {
            return false;
        }
    }
}
