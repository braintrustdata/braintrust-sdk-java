package dev.braintrust.smoketest.otelagent;

import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.sdk.trace.data.SpanData;

import java.util.ArrayList;
import java.util.List;

/**
 * Smoke test that runs with both the Braintrust agent and the OTel Java agent attached.
 *
 * <p>Run via: {@code ./gradlew :braintrust-java-agent:smoke-test:otel-agent:smokeTest}
 *
 * <p>This test verifies that the Braintrust agent and the OTel Java agent can coexist
 * in the same JVM, and that spans created through the OTel API are captured by the
 * collecting exporter (installed via {@link SmokeTestAutoConfiguration}).
 */
public class OtelAgentSmokeTest {

    /** Set by SmokeTestAutoConfiguration so we can read collected spans. */
    static volatile CollectingSpanExporter collectingExporter;

    public static void main(String[] args) throws Exception {
        // Triggers autoconfigure — both the OTel agent and Braintrust agent
        // participate in SDK setup.
        OpenTelemetry otel = GlobalOpenTelemetry.get();
        assertNotNull(collectingExporter, "SmokeTestAutoConfiguration didn't run — collectingExporter is null");

        Tracer tracer = otel.getTracer("braintrust-otel-smoke-test");

        // ── Create a root span with a child ──
        Span root = tracer.spanBuilder("smoke-test-root")
                .setSpanKind(SpanKind.INTERNAL)
                .setAttribute("test.source", "braintrust-otel-smoke-test")
                .startSpan();
        try (var ignored = root.makeCurrent()) {
            Span child = tracer.spanBuilder("smoke-test-child")
                    .setSpanKind(SpanKind.CLIENT)
                    .setAttribute("test.child", true)
                    .startSpan();
            child.end();
        } finally {
            root.end();
        }

        // Give SimpleSpanProcessor a moment to export
        Thread.sleep(500);

        // ── Assertions ──
        var tracesByTraceId = collectingExporter.getTracesByTraceId();
        assertEqual(1, tracesByTraceId.size(), "expected 1 trace, got " + tracesByTraceId.size());

        List<SpanData> spans = tracesByTraceId.values().iterator().next();
        assertEqual(2, spans.size(), "expected 2 spans, got " + spans.size());

        // Find root and child
        SpanData rootSpan = null;
        SpanData childSpan = null;
        for (SpanData sd : spans) {
            if (!sd.getParentSpanContext().isValid()) {
                rootSpan = sd;
            } else {
                childSpan = sd;
            }
        }
        assertNotNull(rootSpan, "root span not found");
        assertNotNull(childSpan, "child span not found");

        // ── Root span assertions ──
        var errors = new ArrayList<String>();
        assertSpanField(errors, "root.name", "smoke-test-root", rootSpan.getName());
        assertSpanField(errors, "root.kind", SpanKind.INTERNAL.toString(), rootSpan.getKind().toString());
        assertSpanField(errors, "root.attr[test.source]", "braintrust-otel-smoke-test",
                rootSpan.getAttributes().get(io.opentelemetry.api.common.AttributeKey.stringKey("test.source")));
        assertSpanField(errors, "root.status", StatusCode.UNSET.toString(),
                rootSpan.getStatus().getStatusCode().toString());

        // ── Child span assertions ──
        assertSpanField(errors, "child.name", "smoke-test-child", childSpan.getName());
        assertSpanField(errors, "child.kind", SpanKind.CLIENT.toString(), childSpan.getKind().toString());
        assertSpanField(errors, "child.attr[test.child]", "true",
                String.valueOf(childSpan.getAttributes().get(io.opentelemetry.api.common.AttributeKey.booleanKey("test.child"))));

        // ── Parent-child relationship ──
        assertSpanField(errors, "child.parentSpanId", rootSpan.getSpanContext().getSpanId(),
                childSpan.getParentSpanContext().getSpanId());
        assertSpanField(errors, "child.traceId", rootSpan.getSpanContext().getTraceId(),
                childSpan.getSpanContext().getTraceId());

        // ── Timestamps ──
        assertTrue(rootSpan.getStartEpochNanos() > 0, "root startTime should be > 0");
        assertTrue(rootSpan.getEndEpochNanos() >= rootSpan.getStartEpochNanos(),
                "root endTime should be >= startTime");
        assertTrue(childSpan.getStartEpochNanos() >= rootSpan.getStartEpochNanos(),
                "child startTime should be >= root startTime");
        assertTrue(childSpan.getEndEpochNanos() <= rootSpan.getEndEpochNanos(),
                "child endTime should be <= root endTime");

        if (!errors.isEmpty()) {
            throw new RuntimeException("Span assertion failures:\n  " + String.join("\n  ", errors));
        }

        System.out.println("=== Smoke test passed ===");
    }

    private static void assertSpanField(List<String> errors, String field, String expected, String actual) {
        if (expected == null && actual == null) return;
        if (expected != null && expected.equals(actual)) return;
        errors.add("%s: expected=%s actual=%s".formatted(field, expected, actual));
    }

    private static void assertNotNull(Object object, String msg) {
        if (object == null) {
            throw new RuntimeException(msg);
        }
    }

    private static void assertEqual(int expected, int actual, String msg) {
        if (expected != actual) {
            throw new RuntimeException(msg);
        }
    }

    private static void assertTrue(boolean condition, String msg) {
        if (!condition) {
            throw new RuntimeException(msg);
        }
    }
}
