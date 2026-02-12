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
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanContext;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.propagation.TextMapGetter;
import io.opentelemetry.context.propagation.TextMapPropagator;
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
 *
 * <p>Run via: {@code ./gradlew :braintrust-java-agent:smoke-test:dd-agent:smokeTest}
 *
 * <p>This test is intentionally a standalone main() (not JUnit) so that it runs in a
 * clean JVM with both agents attached as -javaagent. It mirrors the pattern of
 * {@code GlobalLoggerTest}.
 *
 * <p>The OTel SDK is constructed via autoconfigure (triggered by {@code GlobalOpenTelemetry.get()}).
 * The Braintrust agent detects DD and installs the DD bridge exporter automatically.
 * {@link SmokeTestAutoConfiguration} adds collecting exporters for test assertions.
 */
public class DdAgentSmokeTest {

    /** Every trace that DD completes ends up here (each trace = list of MutableSpan). */
    static final CopyOnWriteArrayList<List<MutableSpan>> ddTraces = new CopyOnWriteArrayList<>();

    /** Set by SmokeTestAutoConfiguration so we can read collected data. */
    static volatile CollectingSpanExporter collectingSpanExporter;
    static volatile CollectingLogRecordExporter collectingLogExporter;
    static volatile CollectingMetricExporter collectingMetricExporter;

    /** Latch so we can wait for DD to finish processing the bridged trace. */
    private static final CountDownLatch ddTraceLatch = new CountDownLatch(1);

    /** True if the Braintrust agent is attached (its bootstrap class is on the classpath). */
    static final boolean BT_AGENT_ENABLED = isBraintrustAgentPresent();

    private static boolean isBraintrustAgentPresent() {
        try {
            Class.forName("dev.braintrust.bootstrap.OtelAutoConfiguration", false, null);
            return true;
        } catch (ClassNotFoundException e) {
            return false;
        }
    }

    public static void main(String[] args) throws Exception {
        System.out.println("[smoke-test] Braintrust agent: " + (BT_AGENT_ENABLED ? "ENABLED" : "NOT DETECTED — skipping OTel assertions"));

        // Register DD trace interceptor to capture completed DD traces
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
                return 999;
            }
        });
        assertTrue(registered, "failed to register dd trace interceptor");

        // ── 1. DD log correlation ──────────────────────────────────────────────
        assertDdLogCorrelation();

        // ── 2. Create spans via DD's OTel shim (or real OTel SDK if BT agent is present) ──
        OpenTelemetry otel = GlobalOpenTelemetry.get();

        // DD-only: verify GlobalOpenTelemetry returns DD's shim, not noop
        if (!BT_AGENT_ENABLED) {
            assertDdShimInstalled(otel);
        }

        if (BT_AGENT_ENABLED) {
            assertNotNull(collectingSpanExporter, "SmokeTestAutoConfiguration didn't run — collectingSpanExporter is null");
            assertNotNull(collectingLogExporter, "SmokeTestAutoConfiguration didn't run — collectingLogExporter is null");
            assertNotNull(collectingMetricExporter, "SmokeTestAutoConfiguration didn't run — collectingMetricExporter is null");
        }

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
                // Assert with the child span active — exercises parent-child context,
                // not just root. DD must see the child's span ID and the root's trace ID.
                assertContextPropagation(otel, child);

                // When BT agent is present, this currently FAILS because OTel SDK spans
                // (SdkSpan) are not visible to DD's CorrelationIdentifier — it returns "0".
                // Fixing this requires creating a corresponding DD AgentSpan while the
                // OTel span is active.
                assertDdLogCorrelationActive(child);
            } finally {
                child.end();
            }
        } finally {
            root.end();
        }

        // ── 3. OTel logs (only with BT agent) ─────────────────────────────────
        if (BT_AGENT_ENABLED) {
            assertOtelLogs(otel);
        }

        // ── 4. OTel metrics (only with BT agent) ──────────────────────────────
        if (BT_AGENT_ENABLED) {
            assertOtelMetrics(otel);
        }

        // ── 5. DD metrics (only with BT agent — bridge required) ───────────────
        if (BT_AGENT_ENABLED) {
            assertDdMetrics();
        }

        // ── 6. Wait for DD to process the trace ───────────────────────────────
        boolean received = ddTraceLatch.await(5, TimeUnit.SECONDS);
        assertTrue(received, "timed out waiting for DD trace");

        // ── 7. DD trace count ──────────────────────────────────────────────────
        var numExpectedTraces = 1;
        assertTrue(ddTraces.size() == numExpectedTraces, "invalid num dd traces: " + ddTraces.size());
        var ddTrace = ddTraces.get(0);
        var numExpectedSpansPerTrace = 2;
        assertTrue(ddTrace.size() == numExpectedSpansPerTrace, "invalid num dd spans: " + ddTrace.size());

        // ── 8. OTel ↔ DD span parity (only with BT agent) ─────────────────────
        if (BT_AGENT_ENABLED) {
            var otelTracesByTraceId = collectingSpanExporter.getTracesByTraceId();
            assertTrue(otelTracesByTraceId.size() == numExpectedTraces, "invalid num otel traces: " + otelTracesByTraceId.size());
            var otelTrace = otelTracesByTraceId.values().iterator().next();
            assertTrue(otelTrace.size() == numExpectedSpansPerTrace, "invalid num otel spans: " + otelTrace.size());
            assertEquals(otelTrace, ddTrace);
        }

        // ── 9. DD-specific span assertions (service, operation, parent-child) ──
        assertDdSpanMetadata(ddTrace);

        System.out.println("=== Smoke test passed ===");
    }

    // ── DD log correlation ──────────────────────────────────────────────────────

    /**
     * Verifies DD's CorrelationIdentifier API works — this is the mechanism DD uses
     * to inject trace/span IDs into logging frameworks (SLF4J MDC, Log4j2 ThreadContext).
     * We check that the API returns "0" when no span is active, proving the
     * infrastructure is functional and not broken by Braintrust's agent.
     */
    private static void assertDdLogCorrelation() {
        String traceId = CorrelationIdentifier.getTraceId();
        String spanId = CorrelationIdentifier.getSpanId();
        assertTrue("0".equals(traceId), "expected traceId='0' with no active span, got: " + traceId);
        assertTrue("0".equals(spanId), "expected spanId='0' with no active span, got: " + spanId);

        System.out.println("[smoke-test] DD log correlation: OK");
    }

    /**
     * Verifies DD's CorrelationIdentifier returns real trace/span IDs while
     * an OTel span is active. This proves DD's context bridge is working —
     * spans created via the OTel API are visible to DD's correlation system.
     */
    private static void assertDdLogCorrelationActive(Span activeSpan) {
        var errors = new ArrayList<String>();
        SpanContext spanCtx = activeSpan.getSpanContext();

        String ddTraceId = CorrelationIdentifier.getTraceId();
        String ddSpanId = CorrelationIdentifier.getSpanId();

        // CorrelationIdentifier.getTraceId() returns the full 128-bit hex trace ID
        String otelTraceId = spanCtx.getTraceId();
        if (!ddTraceId.equals(otelTraceId)) {
            errors.add("traceId: expected=%s actual=%s".formatted(otelTraceId, ddTraceId));
        }

        // CorrelationIdentifier.getSpanId() returns decimal of the 64-bit span ID
        long expectedDdSpanId = Long.parseUnsignedLong(spanCtx.getSpanId(), 16);
        if (!ddSpanId.equals(Long.toUnsignedString(expectedDdSpanId))) {
            errors.add("spanId: expected=%s actual=%s (otel=%s)".formatted(
                    Long.toUnsignedString(expectedDdSpanId), ddSpanId, spanCtx.getSpanId()));
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

    // ── Context propagation ────────────────────────────────────────────────────

    private static final TextMapGetter<Map<String, String>> MAP_GETTER = new TextMapGetter<>() {
        @Override public Iterable<String> keys(Map<String, String> carrier) { return carrier.keySet(); }
        @Override public String get(Map<String, String> carrier, String key) { return carrier.get(key); }
    };

    /**
     * Verifies OTel context propagation (inject + extract round-trip).
     * With DD's shim this exercises {@code AgentTextMapPropagator};
     * with the real OTel SDK it exercises W3C TraceContext propagator.
     * Both should inject a {@code traceparent} header and extract matching trace/span IDs.
     */
    private static void assertContextPropagation(OpenTelemetry otel, Span activeSpan) {
        var errors = new ArrayList<String>();
        TextMapPropagator propagator = otel.getPropagators().getTextMapPropagator();
        SpanContext spanCtx = activeSpan.getSpanContext();

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
                if (!parts[1].equals(spanCtx.getTraceId())) {
                    errors.add("traceparent traceId: expected=%s actual=%s".formatted(spanCtx.getTraceId(), parts[1]));
                }
                if (!parts[2].equals(spanCtx.getSpanId())) {
                    errors.add("traceparent spanId: expected=%s actual=%s".formatted(spanCtx.getSpanId(), parts[2]));
                }
            }
        }

        // Verify Datadog propagation headers (x-datadog-trace-id, etc.)
        // When BT agent is present, this currently FAILS because OTel SDK's W3C
        // propagator doesn't inject DD headers.
        assertDdPropagationHeaders(carrier, spanCtx, errors);

        // Extract
        Context extracted = propagator.extract(Context.root(), carrier, MAP_GETTER);
        Span extractedSpan = Span.fromContext(extracted);
        SpanContext extractedCtx = extractedSpan.getSpanContext();

        if (!extractedCtx.isValid()) {
            errors.add("extracted context is invalid");
        } else {
            if (!extractedCtx.getTraceId().equals(spanCtx.getTraceId())) {
                errors.add("extracted traceId: expected=%s actual=%s".formatted(spanCtx.getTraceId(), extractedCtx.getTraceId()));
            }
            if (!extractedCtx.getSpanId().equals(spanCtx.getSpanId())) {
                errors.add("extracted spanId: expected=%s actual=%s".formatted(spanCtx.getSpanId(), extractedCtx.getSpanId()));
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
    private static void assertDdPropagationHeaders(Map<String, String> carrier, SpanContext spanCtx,
                                                    List<String> errors) {
        // x-datadog-trace-id = decimal of lower 64 bits of the 128-bit trace ID
        String ddTraceId = carrier.get("x-datadog-trace-id");
        if (ddTraceId == null) {
            errors.add("x-datadog-trace-id header missing");
        } else {
            String otelTraceIdHex = spanCtx.getTraceId();
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
            long expectedDdSpanId = Long.parseUnsignedLong(spanCtx.getSpanId(), 16);
            if (!ddParentId.equals(Long.toUnsignedString(expectedDdSpanId))) {
                errors.add("x-datadog-parent-id: expected=%s actual=%s (otel spanId=%s)".formatted(
                        Long.toUnsignedString(expectedDdSpanId), ddParentId, spanCtx.getSpanId()));
            }
        }

        // x-datadog-sampling-priority should be present
        if (!carrier.containsKey("x-datadog-sampling-priority")) {
            errors.add("x-datadog-sampling-priority header missing");
        }
    }

    // ── OTel logs ───────────────────────────────────────────────────────────────

    /**
     * Verifies OTel log records flow through the SDK pipeline by emitting a log
     * and checking that the collecting exporter received it.
     */
    private static void assertOtelLogs(OpenTelemetry otel) {
        Logger logger = otel.getLogsBridge().get("braintrust-dd-smoke-test");

        logger.logRecordBuilder()
                .setBody("smoke-test log record")
                .setAttribute(AttributeKey.stringKey("test.source"), "smoke-test")
                .emit();

        var logRecords = collectingLogExporter.getLogRecords();
        assertTrue(!logRecords.isEmpty(),
                "OTel logs: no log records collected — LoggerProvider pipeline is broken");

        boolean found = false;
        for (var record : logRecords) {
            if ("smoke-test log record".equals(record.getBody().asString())) {
                found = true;
                break;
            }
        }
        assertTrue(found, "OTel logs: emitted log record not found in collected records (got %d records)".formatted(logRecords.size()));

        System.out.println("[smoke-test] OTel logs: OK (%d records collected)".formatted(logRecords.size()));
    }

    // ── OTel metrics ────────────────────────────────────────────────────────────

    /**
     * Verifies OTel metrics flow through the SDK pipeline by recording a counter
     * value and waiting for the PeriodicMetricReader to export it.
     */
    private static void assertOtelMetrics(OpenTelemetry otel) throws InterruptedException {
        Meter meter = otel.getMeter("braintrust-dd-smoke-test");
        LongCounter counter = meter.counterBuilder("smoke_test.counter")
                .setDescription("Smoke test counter")
                .build();
        counter.add(42);

        // PeriodicMetricReader is configured with 100ms interval in SmokeTestAutoConfiguration.
        // Wait specifically for smoke_test.counter to appear (other metrics may arrive first).
        boolean found = false;
        for (int i = 0; i < 30; i++) {
            for (var metric : collectingMetricExporter.getMetrics()) {
                if ("smoke_test.counter".equals(metric.getName())) {
                    found = true;
                    break;
                }
            }
            if (found) break;
            Thread.sleep(100);
        }

        var metrics = collectingMetricExporter.getMetrics();
        assertTrue(found, "OTel metrics: smoke_test.counter not found in collected metrics after 3s (got %d metrics)".formatted(metrics.size()));

        System.out.println("[smoke-test] OTel metrics: OK (%d metrics collected)".formatted(metrics.size()));
    }

    /**
     * Verifies DD's OTel MeterProvider shim received metrics. DD has an
     * {@code OtelMeterProvider.INSTANCE} with a {@code meters} map that tracks
     * all meters created through its shim. If we bridge metrics to DD, this map
     * should contain our "braintrust-dd-smoke-test" meter with the counter we recorded.
     */
    @SuppressWarnings("unchecked")
    private static void assertDdMetrics() throws Exception {
        ClassLoader ddCL = getDatadogClassLoader();
        Class<?> meterProviderClass = Class.forName(
                "datadog.opentelemetry.shim.metrics.OtelMeterProvider", true, ddCL);
        Object ddMeterProvider = meterProviderClass.getField("INSTANCE").get(null);

        // Reflectively read the internal 'meters' map to see if DD received any metrics.
        // The DD bridge uses a PeriodicMetricReader, so we need to wait for it to fire.
        var metersField = meterProviderClass.getDeclaredField("meters");
        metersField.setAccessible(true);

        java.util.Map<?, ?> ddMeters = null;
        for (int i = 0; i < 30; i++) {
            ddMeters = (java.util.Map<?, ?>) metersField.get(ddMeterProvider);
            if (!ddMeters.isEmpty()) break;
            Thread.sleep(100);
        }

        assertTrue(ddMeters != null && !ddMeters.isEmpty(),
                "DD metrics: OtelMeterProvider.meters is empty — no metrics were bridged to DD");

        System.out.println("[smoke-test] DD metrics: OK (%d meters in DD shim)".formatted(ddMeters.size()));
    }

    // ── DD classloader access ───────────────────────────────────────────────────

    private static ClassLoader getDatadogClassLoader() throws Exception {
        Class<?> agentClass = Class.forName("datadog.trace.bootstrap.Agent");
        var field = agentClass.getDeclaredField("AGENT_CLASSLOADER");
        field.setAccessible(true);
        return (ClassLoader) field.get(null);
    }

    // ── DD span metadata ────────────────────────────────────────────────────────

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
}
