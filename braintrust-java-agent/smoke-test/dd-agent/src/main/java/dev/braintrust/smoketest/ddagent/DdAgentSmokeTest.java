package dev.braintrust.smoketest.ddagent;

import datadog.trace.api.CorrelationIdentifier;
import datadog.trace.api.GlobalTracer;
import datadog.trace.api.interceptor.MutableSpan;
import datadog.trace.api.interceptor.TraceInterceptor;
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
import dev.braintrust.system.DDBridge;
import io.opentelemetry.sdk.trace.data.SpanData;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Smoke test that runs with both the Braintrust agent and the Datadog agent attached.
 */
public class DdAgentSmokeTest {
    static final CopyOnWriteArrayList<List<MutableSpan>> ddTraces = new CopyOnWriteArrayList<>();

    /** Latch so we can wait for DD to finish processing the bridged trace. */
    private static final CountDownLatch ddTraceLatch = new CountDownLatch(1);

    /** True if the Braintrust agent is attached (its bootstrap class is on the classpath). */
    static final boolean BT_AGENT_ENABLED = isBraintrustAgentPresent();

    public static void main(String[] args) throws Exception {
        System.out.println("[smoke-test] Braintrust agent: " + (BT_AGENT_ENABLED ? "ENABLED" : "NOT DETECTED — skipping braintrust assertions"));

        boolean registered = GlobalTracer.get().addTraceInterceptor(new TraceInterceptor() {
            @Override
            public Collection<? extends MutableSpan> onTraceComplete(
                    Collection<? extends MutableSpan> trace) {
                List<MutableSpan> snapshot = List.copyOf(trace);
                ddTraces.add(snapshot);
                ddTraceLatch.countDown();
                return trace;
            }

            @Override
            public int priority() {
                return 998;
            }
        });
        assertTrue(registered, "failed to register dd trace interceptor");

        OpenTelemetry otel = GlobalOpenTelemetry.get();
        assertDdShimInstalled(otel);

        assertOtelTraces(otel);
        assertOtelLogs(otel);
        assertOtelMetrics(otel);

        System.out.println("=== Smoke test passed ===");
    }

    private static void assertOtelTraces(OpenTelemetry otel) throws Exception {
        Tracer instTracer = otel.getTracer("braintrust-dd-smoke-test");
        Span root = instTracer.spanBuilder("smoke-test-span")
                .setSpanKind(SpanKind.INTERNAL)
                .setAttribute("test.source", "braintrust-dd-smoke-test")
                .startSpan();
        try (var ignored = root.makeCurrent()) {
            Span child = instTracer.spanBuilder("smoke-test-child")
                    .setSpanKind(SpanKind.CLIENT)
                    .setAttribute("test.child", true)
                    .startSpan();
            try (var ignored2 = child.makeCurrent()) {
                assertContextPropagation(otel, child.getSpanContext().getTraceId(), child.getSpanContext().getSpanId());
                assertDdLogCorrelationActive(child.getSpanContext().getTraceId(), child.getSpanContext().getSpanId());
            } finally {
                child.end();
            }
        } finally {
            root.end();
        }
        boolean received = ddTraceLatch.await(5, TimeUnit.SECONDS);
        assertTrue(received, "timed out waiting for DD trace");
        var numExpectedTraces = 1;
        assertTrue(ddTraces.size() == numExpectedTraces, "invalid num dd traces: " + ddTraces.size());
        var ddTrace = ddTraces.get(0);
        var numExpectedSpansPerTrace = 2;
        assertTrue(ddTrace.size() == numExpectedSpansPerTrace, "invalid num dd spans: " + ddTrace.size());
        if (BT_AGENT_ENABLED) {
            assertBridgedSpans(ddTrace, numExpectedTraces, numExpectedSpansPerTrace);
        }
        assertDdSpanMetadata(ddTrace);
        assertDdLogCorrelationInactive(); // no trace, no log correlation
    }

    private static void assertOtelLogs(OpenTelemetry otel) {
        // dd does not have an interceptor for this at the moment, so we won't do anything other than just use the api
        Logger logger = otel.getLogsBridge().get("braintrust-dd-smoke-test");
        logger.logRecordBuilder()
                .setBody("smoke-test log record")
                .setAttribute(AttributeKey.stringKey("test.source"), "smoke-test")
                .emit();
    }

    private static void assertOtelMetrics(OpenTelemetry otel) throws Exception {
        // dd does not have an interceptor for this at the moment, so we won't do anything other than just use the api
        Meter meter = otel.getMeter("braintrust-dd-smoke-test");
        LongCounter counter = meter.counterBuilder("smoke_test.dd_counter")
                .setDescription("DD smoke test counter")
                .build();
        counter.add(42);
    }

    private static void assertDdLogCorrelationInactive() {
        String traceId = CorrelationIdentifier.getTraceId();
        String spanId = CorrelationIdentifier.getSpanId();
        assertTrue("0".equals(traceId), "expected traceId='0' with no active span, got: " + traceId);
        assertTrue("0".equals(spanId), "expected spanId='0' with no active span, got: " + spanId);

        System.out.println("[smoke-test] DD log correlation: OK");
    }

    private static void assertDdLogCorrelationActive(String traceId, String spanId) {
        var errors = new ArrayList<String>();

        String ddTraceId = CorrelationIdentifier.getTraceId();
        String ddSpanId = CorrelationIdentifier.getSpanId();

        // CorrelationIdentifier.getTraceId() returns the full 128-bit hex trace ID
        if (!ddTraceId.equals(traceId)) {
            errors.add("traceId: expected=%s actual=%s".formatted(traceId, ddTraceId));
        }

        // CorrelationIdentifier.getSpanId() returns decimal of the 64-bit span ID
        long expectedDdSpanId = Long.parseUnsignedLong(spanId, 16);
        if (!ddSpanId.equals(Long.toUnsignedString(expectedDdSpanId))) {
            errors.add("spanId: expected=%s actual=%s (otel=%s)".formatted(
                    Long.toUnsignedString(expectedDdSpanId), ddSpanId, spanId));
        }

        if (!errors.isEmpty()) {
            throw new RuntimeException("DD log correlation (active span):\n  " + String.join("\n  ", errors));
        }
        System.out.println("[smoke-test] DD log correlation (active span): OK (ddTraceId=%s ddSpanId=%s)".formatted(ddTraceId, ddSpanId));
    }

    /**
     * Verifies DD's OTel shim is installed as the GlobalOpenTelemetry implementation.
     * The TracerProvider, MeterProvider, and ContextPropagators should all be DD shim classes.
     */
    private static void assertDdShimInstalled(OpenTelemetry otel) {
        var errors = new ArrayList<String>();

        String tpClass = otel.getTracerProvider().getClass().getName();
        if (!tpClass.contains("datadog")) {
            errors.add("TracerProvider is not DD shim: %s".formatted(tpClass));
        }

        // NOTE: DD does NOT expose its MeterProvider shim via GlobalOpenTelemetry —
        // otel.getMeterProvider() returns the default noop. DD's OtelMeterProvider.INSTANCE
        // is only accessible via reflection on the DD classloader.
        String mpClass = otel.getMeterProvider().getClass().getName();

        String propClass = otel.getPropagators().getClass().getName();
        if (!propClass.contains("datadog")) {
            errors.add("ContextPropagators is not DD shim: %s".formatted(propClass));
        }

        if (!errors.isEmpty()) {
            throw new RuntimeException("DD shim installation:\n  " + String.join("\n  ", errors));
        }
        System.out.println("[smoke-test] DD shim installed: OK (TracerProvider=%s, MeterProvider=%s, Propagators=%s)".formatted(
                tpClass, mpClass, propClass));
    }

    private static final TextMapGetter<Map<String, String>> MAP_GETTER = new TextMapGetter<>() {
        @Override public Iterable<String> keys(Map<String, String> carrier) { return carrier.keySet(); }
        @Override public String get(Map<String, String> carrier, String key) { return carrier.get(key); }
    };

    private static void assertContextPropagation(OpenTelemetry otel, String traceId, String spanId) {
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
            errors.add("traceparent header missing from injected headers (got: %s)".formatted(carrier.keySet()));
        } else {
            String[] parts = traceparent.split("-");
            if (parts.length != 4) {
                errors.add("traceparent malformed: '%s'".formatted(traceparent));
            } else {
                if (!parts[1].equals(traceId)) {
                    errors.add("traceparent traceId: expected=%s actual=%s".formatted(traceId, parts[1]));
                }
                if (!parts[2].equals(spanId)) {
                    errors.add("traceparent spanId: expected=%s actual=%s".formatted(spanId, parts[2]));
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
                errors.add("extracted traceId: expected=%s actual=%s".formatted(traceId, extractedCtx.getTraceId()));
            }
            if (!extractedCtx.getSpanId().equals(spanId)) {
                errors.add("extracted spanId: expected=%s actual=%s".formatted(spanId, extractedCtx.getSpanId()));
            }
        }

        if (!errors.isEmpty()) {
            throw new RuntimeException("context propagation:\n  " + String.join("\n  ", errors));
        }
        System.out.println("[smoke-test] Context propagation (inject/extract): OK (headers: %s)".formatted(carrier.keySet()));
    }

    /**
     * Asserts DD-specific propagation headers are present and contain correct values.
     * DD's AgentTextMapPropagator injects x-datadog-trace-id (decimal, lower 64 bits of
     * OTel trace ID) and x-datadog-parent-id (decimal of OTel span ID).
     */
    private static void assertDdPropagationHeaders(Map<String, String> carrier, String traceId, String spanId, List<String> errors) {
        // x-datadog-trace-id = decimal of lower 64 bits of the 128-bit trace ID
        String ddTraceId = carrier.get("x-datadog-trace-id");
        if (ddTraceId == null) {
            errors.add("x-datadog-trace-id header missing");
        } else {
            String otelTraceIdHex = traceId;
            // Lower 64 bits = last 16 hex chars
            long expectedDdTraceId = Long.parseUnsignedLong(otelTraceIdHex.substring(16), 16);
            if (!ddTraceId.equals(Long.toUnsignedString(expectedDdTraceId))) {
                errors.add("x-datadog-trace-id: expected=%s actual=%s (otel traceId=%s)".formatted(
                        Long.toUnsignedString(expectedDdTraceId), ddTraceId, otelTraceIdHex));
            }
        }

        // x-datadog-parent-id = decimal of the 64-bit span ID
        String ddParentId = carrier.get("x-datadog-parent-id");
        if (ddParentId == null) {
            errors.add("x-datadog-parent-id header missing");
        } else {
            long expectedDdSpanId = Long.parseUnsignedLong(spanId, 16);
            if (!ddParentId.equals(Long.toUnsignedString(expectedDdSpanId))) {
                errors.add("x-datadog-parent-id: expected=%s actual=%s (otel spanId=%s)".formatted(
                        Long.toUnsignedString(expectedDdSpanId), ddParentId, spanId));
            }
        }

        // x-datadog-sampling-priority should be present
        if (!carrier.containsKey("x-datadog-sampling-priority")) {
            errors.add("x-datadog-sampling-priority header missing");
        }
    }

    /**
     * Asserts DD-specific metadata on the bridged spans:
     * - Service name matches dd.service config
     * - Operation name derived from span kind (INTERNAL → "internal", CLIENT → "client.request")
     * - Parent-child structure is preserved (child's localRootSpan is the root)
     */
    private static void assertDdSpanMetadata(List<MutableSpan> ddTrace) {
        var errors = new ArrayList<String>();

        MutableSpan ddRoot = null;
        MutableSpan ddChild = null;
        for (var span : ddTrace) {
            if ("smoke-test-span".contentEquals(span.getResourceName())) {
                ddRoot = span;
            } else if ("smoke-test-child".contentEquals(span.getResourceName())) {
                ddChild = span;
            }
        }
        if (ddRoot == null) errors.add("missing DD root span (resourceName='smoke-test-span')");
        if (ddChild == null) errors.add("missing DD child span (resourceName='smoke-test-child')");
        if (!errors.isEmpty()) {
            throw new RuntimeException("DD span metadata:\n  " + String.join("\n  ", errors));
        }

        // Service name
        if (!"bt-dd-smoke-test".equals(ddRoot.getServiceName())) {
            errors.add("root service: expected='bt-dd-smoke-test' actual='%s'".formatted(ddRoot.getServiceName()));
        }
        if (!"bt-dd-smoke-test".equals(ddChild.getServiceName())) {
            errors.add("child service: expected='bt-dd-smoke-test' actual='%s'".formatted(ddChild.getServiceName()));
        }

        // Operation name
        if (!"internal".contentEquals(ddRoot.getOperationName())) {
            errors.add("root operation: expected='internal' actual='%s'".formatted(ddRoot.getOperationName()));
        }
        if (!"client.request".contentEquals(ddChild.getOperationName())) {
            errors.add("child operation: expected='client.request' actual='%s'".formatted(ddChild.getOperationName()));
        }

        // Parent-child linking
        MutableSpan childLocalRoot = ddChild.getLocalRootSpan();
        if (childLocalRoot == null) {
            errors.add("child has no localRootSpan");
        } else if (childLocalRoot != ddRoot) {
            errors.add("child localRootSpan != root (localRoot resourceName='%s')".formatted(
                    childLocalRoot.getResourceName()));
        }

        MutableSpan rootLocalRoot = ddRoot.getLocalRootSpan();
        if (rootLocalRoot == null) {
            errors.add("root has no localRootSpan");
        } else if (rootLocalRoot != ddRoot) {
            errors.add("root localRootSpan != self (localRoot resourceName='%s')".formatted(
                    rootLocalRoot.getResourceName()));
        }

        if (!errors.isEmpty()) {
            throw new RuntimeException("DD span metadata:\n  " + String.join("\n  ", errors));
        }
        System.out.println("[smoke-test] DD span metadata (service, operation, parent-child): OK");
    }

    // ── Bridged span assertions ────────────────────────────────────────────────

    /**
     * Verifies that the DD bridge converted DD spans to OTel SpanData and that
     * the bridged spans match the DD trace (same names, timing, error status).
     */
    private static void assertBridgedSpans(List<MutableSpan> ddTrace, int numExpectedTraces, int numExpectedSpans) {
        var braintrustSpans = DDBridge.bridgedSpans;
        assertTrue(braintrustSpans.size() == numExpectedTraces,
                "bridged trace count: expected=%d actual=%d (keys=%s)".formatted(numExpectedTraces, braintrustSpans.size(), braintrustSpans.keySet()));

        var braintrustTrace = braintrustSpans.values().iterator().next();
        assertTrue(braintrustTrace.size() == numExpectedSpans,
                "bridged span count: expected=%d actual=%d".formatted(numExpectedSpans, braintrustTrace.size()));

        // Match bridged spans to DD spans by name (resourceName)
        for (var braintrustSpan : braintrustTrace) {
            MutableSpan matchingDd = null;
            for (var ddSpan : ddTrace) {
                if (braintrustSpan.getName().contentEquals(ddSpan.getResourceName())) {
                    matchingDd = ddSpan;
                    break;
                }
            }
            assertNotNull(matchingDd, "no DD span matches bridged span '%s'".formatted(braintrustSpan.getName()));
            assertBridgedSpanMatchesDd(braintrustSpan, matchingDd);
        }

        System.out.println("[smoke-test] Bridged spans (DD→OTel conversion): OK (%d spans)".formatted(braintrustTrace.size()));
    }

    private static void assertBridgedSpanMatchesDd(SpanData bridged, MutableSpan dd) {
        var errors = new ArrayList<String>();

        if (!bridged.getName().contentEquals(dd.getResourceName())) {
            errors.add("name: bridged=%s dd=%s".formatted(bridged.getName(), dd.getResourceName()));
        }

        long bridgedStartNanos = bridged.getStartEpochNanos();
        long ddStartNanos = dd.getStartTime();
        if (bridgedStartNanos != ddStartNanos) {
            errors.add("startTime(nanos): bridged=%d dd=%d".formatted(bridgedStartNanos, ddStartNanos));
        }

        long bridgedDurationNanos = bridged.getEndEpochNanos() - bridged.getStartEpochNanos();
        long ddDurationNanos = dd.getDurationNano();
        if (Math.abs(bridgedDurationNanos - ddDurationNanos) > 1000) {
            errors.add("duration(nanos): bridged=%d dd=%d".formatted(bridgedDurationNanos, ddDurationNanos));
        }

        boolean bridgedError = bridged.getStatus().getStatusCode() == StatusCode.ERROR;
        if (bridgedError != dd.isError()) {
            errors.add("error: bridged=%s dd=%s".formatted(bridgedError, dd.isError()));
        }

        assertTrue(!bridged.getTraceId().equals("00000000000000000000000000000000"),
                "bridged span '%s' has zero trace ID".formatted(bridged.getName()));
        assertTrue(!bridged.getSpanId().equals("0000000000000000"),
                "bridged span '%s' has zero span ID".formatted(bridged.getName()));

        if (!errors.isEmpty()) {
            throw new RuntimeException("bridged span mismatch for '%s':\n  ".formatted(bridged.getName())
                    + String.join("\n  ", errors));
        }
    }

    // ── Assertion helpers ───────────────────────────────────────────────────────

    private static void assertNotNull(Object object, String msg) {
        assertTrue(object != null, msg);
    }

    private static void assertTrue(boolean condition, String failMessage) {
        if (!condition) {
            throw new RuntimeException(failMessage);
        }
    }

    private static void assertEquals(Collection<SpanData> otelTrace, List<MutableSpan> ddTrace) {
        assertTrue(otelTrace.size() == ddTrace.size(), "trace span count mismatch %s - %s".formatted(otelTrace.size(), ddTrace.size()));
        List<MutableSpan> unmatchedDDSpans = new ArrayList<>(ddTrace);
        for (var otelSpan : otelTrace) {
            MutableSpan matchingDDSpan = null;
            for (var ddSpan : unmatchedDDSpans) {
                String ddOtelSpanId = (String) ddSpan.getTag("otel.span_id");
                if (otelSpan.getSpanContext().getSpanId().equals(ddOtelSpanId)) {
                    matchingDDSpan = ddSpan;
                    break;
                }
            }
            assertTrue(matchingDDSpan != null, "unable to find matching dd span for: " + otelSpan);
            unmatchedDDSpans.remove(matchingDDSpan);
            assertEquals(otelSpan, matchingDDSpan);
        }
        assertTrue(unmatchedDDSpans.isEmpty(), "failed to match dd spans: " + unmatchedDDSpans);
    }

    private static void assertEquals(SpanData otelSpan, MutableSpan ddSpan) {
        var errors = new ArrayList<String>();

        if (!otelSpan.getName().contentEquals(ddSpan.getResourceName())) {
            errors.add("name: otel=%s dd.resourceName=%s".formatted(
                    otelSpan.getName(), ddSpan.getResourceName()));
        }

        long otelStartMicros = otelSpan.getStartEpochNanos() / 1000;
        long ddStartMicros = ddSpan.getStartTime() / 1000;
        if (otelStartMicros != ddStartMicros) {
            errors.add("startTime(micros): otel=%d dd=%d".formatted(otelStartMicros, ddStartMicros));
        }

        long otelDurationMicros = (otelSpan.getEndEpochNanos() - otelSpan.getStartEpochNanos()) / 1000;
        long ddDurationMicros = ddSpan.getDurationNano() / 1000;
        if (Math.abs(otelDurationMicros - ddDurationMicros) > 1) {
            errors.add("duration(micros): otel=%d dd=%d".formatted(otelDurationMicros, ddDurationMicros));
        }

        var ddTags = ddSpan.getTags();
        otelSpan.getAttributes().forEach((key, value) -> {
            Object ddValue = ddTags.get(key.getKey());
            if (ddValue == null) {
                errors.add("missing dd tag for otel attribute: %s=%s".formatted(key.getKey(), value));
            } else if (!ddValue.toString().equals(value.toString())) {
                errors.add("tag mismatch for %s: otel=%s dd=%s".formatted(
                        key.getKey(), value, ddValue));
            }
        });

        boolean otelError = otelSpan.getStatus().getStatusCode() == io.opentelemetry.api.trace.StatusCode.ERROR;
        if (otelError != ddSpan.isError()) {
            errors.add("error: otel=%s dd=%s".formatted(otelError, ddSpan.isError()));
        }

        assertTrue(errors.isEmpty(), "span mismatch:\n  " + String.join("\n  ", errors));
    }

    private static boolean isBraintrustAgentPresent() {
        try {
            Class<?> bridgeClass = Class.forName("dev.braintrust.system.DDBridge");
            var field = bridgeClass.getField("tracerProvider");
            var ref = (java.util.concurrent.atomic.AtomicReference<?>) field.get(null);
            return ref.get() != null;
        } catch (Exception e) {
            return false;
        }
    }
}
