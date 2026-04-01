package dev.braintrust.smoketest.ddagent;

import dev.braintrust.smoketest.ddagent.MockBraintrustBackend.OtlpSpan;
import dev.braintrust.smoketest.ddagent.MockDdAgentServer.DdSpan;
import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.logs.Logger;
import io.opentelemetry.api.metrics.LongCounter;
import io.opentelemetry.api.metrics.Meter;
import io.opentelemetry.api.trace.*;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.propagation.TextMapGetter;
import io.opentelemetry.context.propagation.TextMapPropagator;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/**
 * Smoke test that runs with both the Braintrust agent and the Datadog agent attached. A mock DD
 * Agent HTTP server captures trace payloads for assertion instead of using in-process interceptors.
 */
public class DdAgentSmokeTest {

    /**
     * Port the mock DD agent server listens on (must match -Ddd.trace.agent.url in build.gradle).
     */
    static final int MOCK_DD_AGENT_PORT =
            Integer.getInteger("braintrust.smoketest.dd.mock.port", 18126);

    /**
     * Port the mock Braintrust backend listens on (must match BRAINTRUST_API_URL in build.gradle).
     */
    static final int MOCK_BT_BACKEND_PORT =
            Integer.getInteger("braintrust.smoketest.bt.mock.port", 18127);

    /** True if the Braintrust agent is attached (its bootstrap class is on the classpath). */
    static final boolean BT_AGENT_ENABLED = isBraintrustAgentPresent();

    /**
     * If set, asserts that the BT agent detection result matches the expected value. This catches
     * bugs in the detection logic itself — e.g., if the bootstrap class is renamed or the
     * classloader probe breaks.
     */
    static final Boolean BT_AGENT_EXPECTED = parseBooleanEnv("BT_AGENT_EXPECTED");

    public static void main(String[] args) throws Exception {
        assertBtAgentDetection();

        System.out.println(
                "[smoke-test] Braintrust agent: "
                        + (BT_AGENT_ENABLED
                                ? "ENABLED"
                                : "NOT DETECTED — skipping braintrust assertions"));

        // Start mock DD agent server to capture DD trace payloads.
        var mockDdServer = new MockDdAgentServer(1, MOCK_DD_AGENT_PORT);
        mockDdServer.start();

        // Start mock Braintrust backend to capture OTLP trace exports.
        // When the BT agent is present, we expect at least 1 trace export request.
        var mockBtBackend =
                new MockBraintrustBackend(BT_AGENT_ENABLED ? 1 : 0, MOCK_BT_BACKEND_PORT);
        mockBtBackend.start();

        try {
            OpenTelemetry otel = GlobalOpenTelemetry.get();
            assertDdShimInstalled(otel);

            assertOtelTraces(otel, mockDdServer, mockBtBackend);
            assertDistributedTrace(otel, mockDdServer);
            assertOtelLogs(otel);
            assertOtelMetrics(otel);

            System.out.println("=== Smoke test passed ===");
        } finally {
            mockDdServer.stop();
            mockBtBackend.stop();
        }
    }

    private static void assertOtelTraces(
            OpenTelemetry otel, MockDdAgentServer mockServer, MockBraintrustBackend mockBtBackend)
            throws Exception {
        Tracer instTracer = otel.getTracer("braintrust-dd-smoke-test");
        Span root =
                instTracer
                        .spanBuilder("smoke-test-span")
                        .setSpanKind(SpanKind.INTERNAL)
                        .setAttribute("braintrust.whatever", 1)
                        .setAttribute("test.source", "braintrust-dd-smoke-test")
                        .startSpan();
        try (var ignored = root.makeCurrent()) {
            Span child =
                    instTracer
                            .spanBuilder("smoke-test-child")
                            .setSpanKind(SpanKind.CLIENT)
                            .setAttribute("braintrust.whatever", 2)
                            .setAttribute("test.child", true)
                            .startSpan();
            try (var ignored2 = child.makeCurrent()) {
                assertContextPropagation(
                        otel,
                        child.getSpanContext().getTraceId(),
                        child.getSpanContext().getSpanId());
            } finally {
                child.end();
            }
        } finally {
            root.end();
        }

        // Wait for DD to flush traces to our mock server.
        boolean received = mockServer.awaitTraces(30, TimeUnit.SECONDS);
        assertTrue(received, "timed out waiting for DD trace payload on mock server");

        var allTraces = mockServer.getReceivedTraces();
        assertTrue(!allTraces.isEmpty(), "no traces received by mock DD agent server");

        // DD may batch multiple traces into one payload or send multiple payloads.
        // Flatten and find our smoke-test spans.
        var allSpans = mockServer.getAllSpans();
        System.out.println(
                "[smoke-test] Mock DD agent received %d trace(s), %d total span(s)"
                        .formatted(allTraces.size(), allSpans.size()));

        DdSpan ddRoot = findSpanByResource(allSpans, "smoke-test-span");
        DdSpan ddChild = findSpanByResource(allSpans, "smoke-test-child");
        assertNotNull(ddRoot, "missing DD root span (resource='smoke-test-span')");
        assertNotNull(ddChild, "missing DD child span (resource='smoke-test-child')");

        assertDdSpanMetadata(ddRoot, ddChild);

        if (BT_AGENT_ENABLED) {
            assertBraintrustSpans(mockBtBackend);
        } else {
            System.out.println(
                    "[smoke-test] Braintrust backend received %d trace request(s), %d log request(s) (no assertions)"
                            .formatted(
                                    mockBtBackend.traceRequestCount(),
                                    mockBtBackend.logRequestCount()));
        }
    }

    /**
     * Simulates a distributed trace: creates a span under a fake remote parent context and verifies
     * the DD span preserves the parent ID linkage.
     */
    private static void assertDistributedTrace(OpenTelemetry otel, MockDdAgentServer mockServer)
            throws Exception {
        Tracer tracer = otel.getTracer("braintrust-dd-smoke-test");

        // Simulate an incoming remote parent (e.g., from a traceparent header).
        SpanContext remoteParent =
                SpanContext.createFromRemoteParent(
                        "aabbccddaabbccddaabbccddaabbccdd",
                        "1234567812345678",
                        TraceFlags.getSampled(),
                        TraceState.getDefault());

        Context parentContext = Context.root().with(Span.wrap(remoteParent));

        Span localRoot =
                tracer.spanBuilder("distributed-trace-entry")
                        .setParent(parentContext)
                        .setSpanKind(SpanKind.SERVER)
                        .setAttribute("braintrust.whatever", 1)
                        .setAttribute("test.distributed", true)
                        .startSpan();
        try (var ignored = localRoot.makeCurrent()) {
            Span child =
                    tracer.spanBuilder("distributed-trace-child")
                            .setSpanKind(SpanKind.INTERNAL)
                            .setAttribute("braintrust.whatever", 2)
                            .startSpan();
            child.end();
        } finally {
            localRoot.end();
        }

        // Wait for the distributed trace spans to arrive at the DD mock server.
        // DD may batch them with the earlier trace or send a separate flush.
        DdSpan ddEntry = pollForDdSpan(mockServer, "distributed-trace-entry", 30);
        DdSpan ddChild = pollForDdSpan(mockServer, "distributed-trace-child", 30);

        var errors = new ArrayList<String>();

        // The local root should have a non-zero parent ID pointing to the remote parent.
        // The remote parent span ID "1234567812345678" in hex = 0x1234567812345678 in decimal.
        long expectedParentId = Long.parseUnsignedLong("1234567812345678", 16);
        if (ddEntry.parentId != expectedParentId) {
            errors.add(
                    "distributed trace entry parentId: expected=%d (remote parent) actual=%d"
                            .formatted(expectedParentId, ddEntry.parentId));
        }

        // The child should be parented to the local root.
        if (ddChild.parentId != ddEntry.spanId) {
            errors.add(
                    "distributed trace child parentId: expected=%d (entry spanId) actual=%d"
                            .formatted(ddEntry.spanId, ddChild.parentId));
        }

        // Both should share the same trace ID.
        if (ddEntry.traceId != ddChild.traceId) {
            errors.add(
                    "distributed trace traceId mismatch: entry=%d child=%d"
                            .formatted(ddEntry.traceId, ddChild.traceId));
        }

        if (!errors.isEmpty()) {
            throw new RuntimeException(
                    "Distributed trace assertions:\n  " + String.join("\n  ", errors));
        }
        System.out.println(
                "[smoke-test] Distributed trace (remote parent linkage, parent-child, traceId):"
                        + " OK");
    }

    private static void assertOtelLogs(OpenTelemetry otel) {
        // DD does not have an interceptor for this at the moment, so we just exercise the API.
        Logger logger = otel.getLogsBridge().get("braintrust-dd-smoke-test");
        logger.logRecordBuilder()
                .setBody("smoke-test log record")
                .setAttribute(AttributeKey.stringKey("test.source"), "smoke-test")
                .emit();
    }

    private static void assertOtelMetrics(OpenTelemetry otel) {
        // DD does not have an interceptor for this at the moment, so we just exercise the API.
        Meter meter = otel.getMeter("braintrust-dd-smoke-test");
        LongCounter counter =
                meter.counterBuilder("smoke_test.dd_counter")
                        .setDescription("DD smoke test counter")
                        .build();
        counter.add(42);
    }

    /**
     * Verifies DD's OTel shim is installed as the GlobalOpenTelemetry implementation. The
     * TracerProvider and ContextPropagators should be DD shim classes.
     */
    private static void assertDdShimInstalled(OpenTelemetry otel) {
        var errors = new ArrayList<String>();

        String tpClass = otel.getTracerProvider().getClass().getName();
        if (!tpClass.contains("datadog")) {
            errors.add("TracerProvider is not DD shim: %s".formatted(tpClass));
        }

        String mpClass = otel.getMeterProvider().getClass().getName();

        String propClass = otel.getPropagators().getClass().getName();
        if (!propClass.contains("datadog")) {
            errors.add("ContextPropagators is not DD shim: %s".formatted(propClass));
        }

        if (!errors.isEmpty()) {
            throw new RuntimeException("DD shim installation:\n  " + String.join("\n  ", errors));
        }
        System.out.println(
                "[smoke-test] DD shim installed: OK (TracerProvider=%s, MeterProvider=%s, Propagators=%s)"
                        .formatted(tpClass, mpClass, propClass));
    }

    private static final TextMapGetter<Map<String, String>> MAP_GETTER =
            new TextMapGetter<>() {
                @Override
                public Iterable<String> keys(Map<String, String> carrier) {
                    return carrier.keySet();
                }

                @Override
                public String get(Map<String, String> carrier, String key) {
                    return carrier.get(key);
                }
            };

    private static void assertContextPropagation(
            OpenTelemetry otel, String traceId, String spanId) {
        var errors = new ArrayList<String>();
        TextMapPropagator propagator = otel.getPropagators().getTextMapPropagator();

        // Inject
        Map<String, String> carrier = new HashMap<>();
        propagator.inject(Context.current(), carrier, Map::put);

        if (carrier.isEmpty()) {
            throw new RuntimeException("context propagation: inject produced no headers");
        }

        // Verify traceparent header (W3C format: 00-<traceId>-<spanId>-<flags>)
        String traceparent = carrier.get("traceparent");
        if (traceparent == null) {
            errors.add(
                    "traceparent header missing from injected headers (got: %s)"
                            .formatted(carrier.keySet()));
        } else {
            String[] parts = traceparent.split("-");
            if (parts.length != 4) {
                errors.add("traceparent malformed: '%s'".formatted(traceparent));
            } else {
                if (!parts[1].equals(traceId)) {
                    errors.add(
                            "traceparent traceId: expected=%s actual=%s"
                                    .formatted(traceId, parts[1]));
                }
                if (!parts[2].equals(spanId)) {
                    errors.add(
                            "traceparent spanId: expected=%s actual=%s"
                                    .formatted(spanId, parts[2]));
                }
            }
        }

        assertDdPropagationHeaders(carrier, traceId, spanId, errors);

        Context extracted = propagator.extract(Context.root(), carrier, MAP_GETTER);
        Span extractedSpan = Span.fromContext(extracted);
        SpanContext extractedCtx = extractedSpan.getSpanContext();

        if (!extractedCtx.isValid()) {
            errors.add("extracted context is invalid");
        } else {
            if (!extractedCtx.getTraceId().equals(traceId)) {
                errors.add(
                        "extracted traceId: expected=%s actual=%s"
                                .formatted(traceId, extractedCtx.getTraceId()));
            }
            if (!extractedCtx.getSpanId().equals(spanId)) {
                errors.add(
                        "extracted spanId: expected=%s actual=%s"
                                .formatted(spanId, extractedCtx.getSpanId()));
            }
        }

        if (!errors.isEmpty()) {
            throw new RuntimeException("context propagation:\n  " + String.join("\n  ", errors));
        }
        System.out.println(
                "[smoke-test] Context propagation (inject/extract): OK (headers: %s)"
                        .formatted(carrier.keySet()));
    }

    /** Asserts DD-specific propagation headers are present and contain correct values. */
    private static void assertDdPropagationHeaders(
            Map<String, String> carrier, String traceId, String spanId, List<String> errors) {
        String ddTraceId = carrier.get("x-datadog-trace-id");
        if (ddTraceId == null) {
            errors.add("x-datadog-trace-id header missing");
        } else {
            long expectedDdTraceId = Long.parseUnsignedLong(traceId.substring(16), 16);
            if (!ddTraceId.equals(Long.toUnsignedString(expectedDdTraceId))) {
                errors.add(
                        "x-datadog-trace-id: expected=%s actual=%s (otel traceId=%s)"
                                .formatted(
                                        Long.toUnsignedString(expectedDdTraceId),
                                        ddTraceId,
                                        traceId));
            }
        }

        String ddParentId = carrier.get("x-datadog-parent-id");
        if (ddParentId == null) {
            errors.add("x-datadog-parent-id header missing");
        } else {
            long expectedDdSpanId = Long.parseUnsignedLong(spanId, 16);
            if (!ddParentId.equals(Long.toUnsignedString(expectedDdSpanId))) {
                errors.add(
                        "x-datadog-parent-id: expected=%s actual=%s (otel spanId=%s)"
                                .formatted(
                                        Long.toUnsignedString(expectedDdSpanId),
                                        ddParentId,
                                        spanId));
            }
        }

        if (!carrier.containsKey("x-datadog-sampling-priority")) {
            errors.add("x-datadog-sampling-priority header missing");
        }
    }

    /**
     * Asserts DD-specific metadata on the captured spans from the mock server: service name,
     * operation name, and parent-child structure.
     */
    private static void assertDdSpanMetadata(DdSpan ddRoot, DdSpan ddChild) {
        var errors = new ArrayList<String>();

        // Service name
        if (!"bt-dd-smoke-test".equals(ddRoot.service)) {
            errors.add(
                    "root service: expected='bt-dd-smoke-test' actual='%s'"
                            .formatted(ddRoot.service));
        }
        if (!"bt-dd-smoke-test".equals(ddChild.service)) {
            errors.add(
                    "child service: expected='bt-dd-smoke-test' actual='%s'"
                            .formatted(ddChild.service));
        }

        // Operation name
        if (!"internal".equals(ddRoot.name)) {
            errors.add("root operation: expected='internal' actual='%s'".formatted(ddRoot.name));
        }
        if (!"client.request".equals(ddChild.name)) {
            errors.add(
                    "child operation: expected='client.request' actual='%s'"
                            .formatted(ddChild.name));
        }

        // Parent-child linking: child's parentId should be root's spanId
        if (ddChild.parentId != ddRoot.spanId) {
            errors.add(
                    "child parentId: expected=%d (root spanId) actual=%d"
                            .formatted(ddRoot.spanId, ddChild.parentId));
        }

        // Same trace ID
        if (ddChild.traceId != ddRoot.traceId) {
            errors.add(
                    "traceId mismatch: root=%d child=%d"
                            .formatted(ddRoot.traceId, ddChild.traceId));
        }

        // Duration should be positive
        if (ddRoot.duration <= 0) {
            errors.add("root duration <= 0: %d".formatted(ddRoot.duration));
        }
        if (ddChild.duration <= 0) {
            errors.add("child duration <= 0: %d".formatted(ddChild.duration));
        }

        if (!errors.isEmpty()) {
            throw new RuntimeException("DD span metadata:\n  " + String.join("\n  ", errors));
        }
        System.out.println(
                "[smoke-test] DD span metadata (service, operation, parent-child, timing): OK");
    }

    // ── Braintrust backend assertions ─────────────────────────────────────────

    /** DD internal tags that should NOT appear on bridged Braintrust spans. */
    private static final Set<String> DROPPED_DD_TAGS =
            Set.of(
                    "_dd.agent_psr",
                    "_dd.profiling.enabled",
                    "_dd.trace_span_attribute_schema",
                    "_sample_rate");

    /**
     * Asserts that the Braintrust mock backend received the expected spans with correct structure
     * and attributes.
     */
    private static void assertBraintrustSpans(MockBraintrustBackend mockBtBackend)
            throws Exception {
        boolean received = mockBtBackend.awaitTraces(30, TimeUnit.SECONDS);
        assertTrue(received, "timed out waiting for Braintrust trace export");

        var allSpans = mockBtBackend.getAllSpans();
        System.out.println(
                "[smoke-test] Braintrust backend received %d trace request(s), %d span(s)"
                        .formatted(mockBtBackend.traceRequestCount(), allSpans.size()));

        assertTrue(
                allSpans.size() >= 2,
                "expected at least 2 Braintrust spans, got " + allSpans.size());

        OtlpSpan btRoot = mockBtBackend.findSpanByName("smoke-test-span");
        OtlpSpan btChild = mockBtBackend.findSpanByName("smoke-test-child");
        assertNotNull(btRoot, "missing Braintrust root span (name='smoke-test-span')");
        assertNotNull(btChild, "missing Braintrust child span (name='smoke-test-child')");

        var errors = new ArrayList<String>();

        // ── Root span assertions ──
        if (btRoot.kind != io.opentelemetry.proto.trace.v1.Span.SpanKind.SPAN_KIND_INTERNAL) {
            errors.add("root kind: expected=INTERNAL actual=%s".formatted(btRoot.kind));
        }
        if (!"braintrust-dd-smoke-test".equals(btRoot.stringAttr("test.source"))) {
            errors.add(
                    "root test.source: expected='braintrust-dd-smoke-test' actual='%s'"
                            .formatted(btRoot.stringAttr("test.source")));
        }

        // ── Child span assertions ──
        if (btChild.kind != io.opentelemetry.proto.trace.v1.Span.SpanKind.SPAN_KIND_CLIENT) {
            errors.add("child kind: expected=CLIENT actual=%s".formatted(btChild.kind));
        }
        if (!Boolean.TRUE.equals(btChild.boolAttr("test.child"))) {
            errors.add(
                    "child test.child: expected=true actual=%s"
                            .formatted(btChild.boolAttr("test.child")));
        }
        Long whateverVal = btChild.longAttr("braintrust.whatever");
        if (whateverVal == null || whateverVal != 2L) {
            errors.add("child braintrust.whatever: expected=2 actual=%s".formatted(whateverVal));
        }

        // ── Parent-child relationship ──
        if (!btChild.traceId.equals(btRoot.traceId)) {
            errors.add(
                    "traceId mismatch: root=%s child=%s"
                            .formatted(btRoot.traceId, btChild.traceId));
        }
        if (!btChild.parentSpanId.equals(btRoot.spanId)) {
            errors.add(
                    "child parentSpanId: expected=%s (root spanId) actual=%s"
                            .formatted(btRoot.spanId, btChild.parentSpanId));
        }
        if (btRoot.traceId.replace("0", "").isEmpty()) {
            errors.add("root has zero/empty traceId: " + btRoot.traceId);
        }

        // ── Timing ──
        if (btRoot.startTimeUnixNano <= 0) {
            errors.add("root startTime <= 0");
        }
        if (btRoot.endTimeUnixNano <= btRoot.startTimeUnixNano) {
            errors.add("root endTime <= startTime");
        }
        if (btChild.startTimeUnixNano <= 0) {
            errors.add("child startTime <= 0");
        }
        if (btChild.endTimeUnixNano <= btChild.startTimeUnixNano) {
            errors.add("child endTime <= startTime");
        }

        // ── Dropped DD tags should not be present ──
        for (OtlpSpan span : List.of(btRoot, btChild)) {
            for (String droppedTag : DROPPED_DD_TAGS) {
                if (span.attributes.containsKey(droppedTag)) {
                    errors.add(
                            "span '%s' has dropped DD tag '%s'".formatted(span.name, droppedTag));
                }
            }
        }

        if (!errors.isEmpty()) {
            throw new RuntimeException(
                    "Braintrust span assertions:\n  " + String.join("\n  ", errors));
        }
        System.out.println(
                "[smoke-test] Braintrust spans (structure, attributes, timing, no DD internal"
                        + " tags): OK");
    }

    // ── Helpers ─────────────────────────────────────────────────────────────────

    private static DdSpan findSpanByResource(List<DdSpan> spans, String resource) {
        for (var span : spans) {
            if (resource.equals(span.resource)) {
                return span;
            }
        }
        return null;
    }

    private static DdSpan pollForDdSpan(
            MockDdAgentServer server, String resource, int timeoutSeconds) throws Exception {
        long deadline = System.currentTimeMillis() + timeoutSeconds * 1000L;
        while (System.currentTimeMillis() < deadline) {
            DdSpan span = findSpanByResource(server.getAllSpans(), resource);
            if (span != null) {
                return span;
            }
            Thread.sleep(200);
        }
        throw new RuntimeException(
                "timed out waiting for DD span with resource='%s' (received %d spans: %s)"
                        .formatted(resource, server.getAllSpans().size(), server.getAllSpans()));
    }

    private static void assertNotNull(Object object, String msg) {
        assertTrue(object != null, msg);
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
                    "[smoke-test] BT agent detection: OK (expected=%s, detected=%s)"
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
