package dev.braintrust.agent.dd;

import static org.junit.jupiter.api.Assertions.*;

import datadog.trace.api.DDTraceId;
import datadog.trace.api.interceptor.MutableSpan;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class DDSpanConverterTest {

    // ── Trace ID conversion ────────────────────────────────────────────────────

    @Test
    void traceIdConvertedTo32CharHex() {
        var ddSpan = stubSpan("test-span", "internal", 1_000_000_000L, 500_000_000L);
        DDTraceId traceId = DDTraceId.fromHex("0123456789abcdef0123456789abcdef");

        SpanData result = DDSpanConverter.convertSpan(ddSpan, traceId, 1L, 0L);

        assertEquals("0123456789abcdef0123456789abcdef", result.getSpanContext().getTraceId());
    }

    @Test
    void traceIdZeroPaddedWhenSmall() {
        var ddSpan = stubSpan("test-span", "internal", 1_000_000_000L, 500_000_000L);
        // DDTraceId.from(long) creates a 64-bit trace ID — upper 64 bits are zero
        DDTraceId traceId = DDTraceId.from(0x1234L);

        SpanData result = DDSpanConverter.convertSpan(ddSpan, traceId, 1L, 0L);

        assertEquals("00000000000000000000000000001234", result.getSpanContext().getTraceId());
    }

    // ── Span ID conversion ─────────────────────────────────────────────────────

    @Test
    void spanIdConvertedTo16CharHex() {
        var ddSpan = stubSpan("test-span", "internal", 1_000_000_000L, 500_000_000L);
        DDTraceId traceId = DDTraceId.from(1L);

        SpanData result = DDSpanConverter.convertSpan(ddSpan, traceId, 0xdeadbeefcafebabeL, 0L);

        assertEquals("deadbeefcafebabe", result.getSpanContext().getSpanId());
    }

    @Test
    void spanIdZeroPaddedWhenSmall() {
        var ddSpan = stubSpan("test-span", "internal", 1_000_000_000L, 500_000_000L);
        DDTraceId traceId = DDTraceId.from(1L);

        SpanData result = DDSpanConverter.convertSpan(ddSpan, traceId, 0x00ffL, 0L);

        assertEquals("00000000000000ff", result.getSpanContext().getSpanId());
    }

    // ── Parent-child linking ───────────────────────────────────────────────────

    @Test
    void rootSpanHasInvalidParentContext() {
        var ddSpan = stubSpan("root-span", "internal", 1_000_000_000L, 500_000_000L);
        DDTraceId traceId = DDTraceId.from(42L);

        SpanData result = DDSpanConverter.convertSpan(ddSpan, traceId, 1L, 0L);

        assertFalse(result.getParentSpanContext().isValid());
    }

    @Test
    void childSpanHasValidParentContextWithMatchingTraceId() {
        var ddSpan = stubSpan("child-span", "client.request", 2_000_000_000L, 300_000_000L);
        DDTraceId traceId = DDTraceId.fromHex("aabbccdd11223344aabbccdd11223344");
        long spanId = 0x1111L;
        long parentId = 0x2222L;

        SpanData result = DDSpanConverter.convertSpan(ddSpan, traceId, spanId, parentId);

        assertTrue(result.getParentSpanContext().isValid());
        assertEquals(
                "aabbccdd11223344aabbccdd11223344", result.getParentSpanContext().getTraceId());
        assertEquals("0000000000002222", result.getParentSpanContext().getSpanId());
    }

    @Test
    void distributedTraceLocalRootPreservesParent() {
        // A local root span continuing a distributed trace has parentId != 0
        // (the parent lives in a remote service). The converter must still attach it.
        var ddSpan = stubSpan("local-root", "server.request", 1_000_000_000L, 500_000_000L);
        DDTraceId traceId = DDTraceId.fromHex("aabbccdd11223344aabbccdd11223344");
        long spanId = 0x1111L;
        long remoteParentId = 0x9999L; // parent span from upstream service

        SpanData result = DDSpanConverter.convertSpan(ddSpan, traceId, spanId, remoteParentId);

        assertTrue(
                result.getParentSpanContext().isValid(),
                "local root with remote parent should have valid parent context");
        assertEquals(
                "aabbccdd11223344aabbccdd11223344", result.getParentSpanContext().getTraceId());
        assertEquals("0000000000009999", result.getParentSpanContext().getSpanId());
    }

    // ── Timestamps ─────────────────────────────────────────────────────────────

    @Test
    void timestampsPreserved() {
        long startNanos = 1_700_000_000_000_000_000L; // ~2023-11-14
        long durationNanos = 123_456_789L;
        var ddSpan = stubSpan("timed-span", "internal", startNanos, durationNanos);
        DDTraceId traceId = DDTraceId.from(1L);

        SpanData result = DDSpanConverter.convertSpan(ddSpan, traceId, 1L, 0L);

        assertEquals(startNanos, result.getStartEpochNanos());
        assertEquals(startNanos + durationNanos, result.getEndEpochNanos());
    }

    @Test
    void zeroDurationProducesEqualStartAndEnd() {
        long startNanos = 5_000_000_000L;
        var ddSpan = stubSpan("instant-span", "internal", startNanos, 0L);
        DDTraceId traceId = DDTraceId.from(1L);

        SpanData result = DDSpanConverter.convertSpan(ddSpan, traceId, 1L, 0L);

        assertEquals(startNanos, result.getStartEpochNanos());
        assertEquals(startNanos, result.getEndEpochNanos());
    }

    // ── Tag conversion ─────────────────────────────────────────────────────────

    @Test
    void tagsConvertedToAttributes() {
        Map<String, Object> tags = new HashMap<>();
        tags.put("string.tag", "hello");
        tags.put("long.tag", 42L);
        tags.put("int.tag", 7);
        tags.put("double.tag", 3.14);
        tags.put("float.tag", 2.5f);
        tags.put("bool.tag", true);
        var ddSpan = stubSpan("tagged-span", "internal", 1_000_000_000L, 500_000_000L, tags, false);
        DDTraceId traceId = DDTraceId.from(1L);

        SpanData result = DDSpanConverter.convertSpan(ddSpan, traceId, 1L, 0L);

        var attrs = result.getAttributes();
        assertEquals("hello", attrs.get(AttributeKey.stringKey("string.tag")));
        assertEquals(42L, attrs.get(AttributeKey.longKey("long.tag")));
        assertEquals(7L, attrs.get(AttributeKey.longKey("int.tag"))); // int → long
        assertEquals(3.14, attrs.get(AttributeKey.doubleKey("double.tag")));
        assertEquals(2.5, attrs.get(AttributeKey.doubleKey("float.tag")), 0.01); // float → double
        assertEquals(true, attrs.get(AttributeKey.booleanKey("bool.tag")));
    }

    // ── Dropped DD tags ────────────────────────────────────────────────────────

    @Test
    void ddInternalTagsDropped() {
        Map<String, Object> tags = new HashMap<>();
        tags.put("_dd.agent_psr", 1.0);
        tags.put("_dd.profiling.enabled", "false");
        tags.put("_dd.trace_span_attribute_schema", "v1");
        tags.put("_sample_rate", 1.0);
        tags.put("keep.this", "yes");
        var ddSpan =
                stubSpan("filtered-span", "internal", 1_000_000_000L, 500_000_000L, tags, false);
        DDTraceId traceId = DDTraceId.from(1L);

        SpanData result = DDSpanConverter.convertSpan(ddSpan, traceId, 1L, 0L);

        var attrs = result.getAttributes();
        assertNull(attrs.get(AttributeKey.doubleKey("_dd.agent_psr")));
        assertNull(attrs.get(AttributeKey.stringKey("_dd.profiling.enabled")));
        assertNull(attrs.get(AttributeKey.stringKey("_dd.trace_span_attribute_schema")));
        assertNull(attrs.get(AttributeKey.doubleKey("_sample_rate")));
        assertEquals("yes", attrs.get(AttributeKey.stringKey("keep.this")));
    }

    // ── SpanKind inference ─────────────────────────────────────────────────────

    @Test
    void spanKindInferredFromOperationName() {
        record Case(String opName, SpanKind expected) {}
        var cases =
                List.of(
                        new Case("internal", SpanKind.INTERNAL),
                        new Case("server.request", SpanKind.SERVER),
                        new Case("server", SpanKind.SERVER),
                        new Case("client.request", SpanKind.CLIENT),
                        new Case("client", SpanKind.CLIENT),
                        new Case("producer", SpanKind.PRODUCER),
                        new Case("consumer", SpanKind.CONSUMER),
                        new Case("unknown.op", SpanKind.INTERNAL),
                        new Case("", SpanKind.INTERNAL));

        DDTraceId traceId = DDTraceId.from(1L);
        for (var c : cases) {
            var ddSpan = stubSpan("kind-test", c.opName, 1_000_000_000L, 500_000_000L);
            SpanData result = DDSpanConverter.convertSpan(ddSpan, traceId, 1L, 0L);
            assertEquals(c.expected, result.getKind(), "opName='%s'".formatted(c.opName));
        }
    }

    @Test
    void nullOperationNameDefaultsToInternal() {
        var ddSpan = stubSpan("null-op", null, 1_000_000_000L, 500_000_000L);
        DDTraceId traceId = DDTraceId.from(1L);

        SpanData result = DDSpanConverter.convertSpan(ddSpan, traceId, 1L, 0L);

        assertEquals(SpanKind.INTERNAL, result.getKind());
    }

    // ── Error status ───────────────────────────────────────────────────────────

    @Test
    void errorSpanHasErrorStatus() {
        var ddSpan =
                stubSpan("error-span", "internal", 1_000_000_000L, 500_000_000L, Map.of(), true);
        DDTraceId traceId = DDTraceId.from(1L);

        SpanData result = DDSpanConverter.convertSpan(ddSpan, traceId, 1L, 0L);

        assertEquals(StatusCode.ERROR, result.getStatus().getStatusCode());
    }

    @Test
    void nonErrorSpanHasUnsetStatus() {
        var ddSpan = stubSpan("ok-span", "internal", 1_000_000_000L, 500_000_000L);
        DDTraceId traceId = DDTraceId.from(1L);

        SpanData result = DDSpanConverter.convertSpan(ddSpan, traceId, 1L, 0L);

        assertEquals(StatusCode.UNSET, result.getStatus().getStatusCode());
    }

    // ── Span name ──────────────────────────────────────────────────────────────

    @Test
    void spanNameFromResourceName() {
        var ddSpan = stubSpan("my-resource", "internal", 1_000_000_000L, 500_000_000L);
        DDTraceId traceId = DDTraceId.from(1L);

        SpanData result = DDSpanConverter.convertSpan(ddSpan, traceId, 1L, 0L);

        assertEquals("my-resource", result.getName());
    }

    // ── replayTrace with topological sort ──────────────────────────────────────

    @Test
    void replayTracePreservesParentChildOrder() {
        var exporter = InMemorySpanExporter.create();
        var tracerProvider =
                SdkTracerProvider.builder()
                        .setIdGenerator(OverridableIdGenerator.INSTANCE)
                        .addSpanProcessor(SimpleSpanProcessor.create(exporter))
                        .build();
        var tracer = tracerProvider.get("test");

        // Create SpanData: parent and child (provide child first to test sorting)
        var parentCtx =
                io.opentelemetry.api.trace.SpanContext.create(
                        "0123456789abcdef0123456789abcdef",
                        "aaaaaaaaaaaaaaaa",
                        io.opentelemetry.api.trace.TraceFlags.getSampled(),
                        io.opentelemetry.api.trace.TraceState.getDefault());
        var childCtx =
                io.opentelemetry.api.trace.SpanContext.create(
                        "0123456789abcdef0123456789abcdef",
                        "bbbbbbbbbbbbbbbb",
                        io.opentelemetry.api.trace.TraceFlags.getSampled(),
                        io.opentelemetry.api.trace.TraceState.getDefault());

        long now = System.nanoTime();
        var parentSpan =
                new ImmutableSpanData(
                        parentCtx,
                        io.opentelemetry.api.trace.SpanContext.getInvalid(),
                        io.opentelemetry.sdk.resources.Resource.getDefault(),
                        io.opentelemetry.sdk.common.InstrumentationScopeInfo.create("test"),
                        "parent-span",
                        SpanKind.INTERNAL,
                        now,
                        now + 1_000_000,
                        io.opentelemetry.api.common.Attributes.empty(),
                        io.opentelemetry.sdk.trace.data.StatusData.unset());
        var childSpan =
                new ImmutableSpanData(
                        childCtx,
                        parentCtx,
                        io.opentelemetry.sdk.resources.Resource.getDefault(),
                        io.opentelemetry.sdk.common.InstrumentationScopeInfo.create("test"),
                        "child-span",
                        SpanKind.CLIENT,
                        now + 100_000,
                        now + 900_000,
                        io.opentelemetry.api.common.Attributes.empty(),
                        io.opentelemetry.sdk.trace.data.StatusData.unset());

        // Provide child first to verify topological sort reorders them
        DDSpanConverter.replayTrace(tracer, List.of(childSpan, parentSpan));

        var exported = exporter.getFinishedSpanItems();
        assertEquals(2, exported.size());
        var names = exported.stream().map(SpanData::getName).toList();
        assertTrue(names.contains("parent-span"), "missing parent-span in replay");
        assertTrue(names.contains("child-span"), "missing child-span in replay");

        // The replayed spans must preserve the original trace and span IDs.
        var replayedParent =
                exported.stream().filter(s -> s.getName().equals("parent-span")).findFirst().get();
        var replayedChild =
                exported.stream().filter(s -> s.getName().equals("child-span")).findFirst().get();

        assertEquals(
                "0123456789abcdef0123456789abcdef",
                replayedParent.getSpanContext().getTraceId(),
                "parent traceId must be preserved");
        assertEquals(
                "aaaaaaaaaaaaaaaa",
                replayedParent.getSpanContext().getSpanId(),
                "parent spanId must be preserved");

        assertEquals(
                "0123456789abcdef0123456789abcdef",
                replayedChild.getSpanContext().getTraceId(),
                "child traceId must be preserved");
        assertEquals(
                "bbbbbbbbbbbbbbbb",
                replayedChild.getSpanContext().getSpanId(),
                "child spanId must be preserved");

        // The child's parent must point to the original parent span ID.
        assertTrue(replayedChild.getParentSpanContext().isValid());
        assertEquals(
                "aaaaaaaaaaaaaaaa",
                replayedChild.getParentSpanContext().getSpanId(),
                "child parentSpanId must reference original parent");

        tracerProvider.close();
    }

    // ── Stub helper ────────────────────────────────────────────────────────────

    private static MutableSpan stubSpan(
            String resourceName, String operationName, long startTimeNanos, long durationNanos) {
        return stubSpan(
                resourceName, operationName, startTimeNanos, durationNanos, Map.of(), false);
    }

    private static MutableSpan stubSpan(
            String resourceName,
            String operationName,
            long startTimeNanos,
            long durationNanos,
            Map<String, Object> tags,
            boolean isError) {
        return new MutableSpan() {
            @Override
            public long getStartTime() {
                return startTimeNanos;
            }

            @Override
            public long getDurationNano() {
                return durationNanos;
            }

            @Override
            public CharSequence getOperationName() {
                return operationName;
            }

            @Override
            public CharSequence getResourceName() {
                return resourceName;
            }

            @Override
            public Map<String, Object> getTags() {
                return tags;
            }

            @Override
            public boolean isError() {
                return isError;
            }

            // ── Methods not used by convertSpan(MutableSpan, DDTraceId, long, long) ──
            @Override
            public MutableSpan setOperationName(CharSequence s) {
                throw new UnsupportedOperationException();
            }

            @Override
            public String getServiceName() {
                throw new UnsupportedOperationException();
            }

            @Override
            public MutableSpan setServiceName(String s) {
                throw new UnsupportedOperationException();
            }

            @Override
            public MutableSpan setResourceName(CharSequence s) {
                throw new UnsupportedOperationException();
            }

            @Override
            public Integer getSamplingPriority() {
                throw new UnsupportedOperationException();
            }

            @Override
            public MutableSpan setSamplingPriority(int i) {
                throw new UnsupportedOperationException();
            }

            @Override
            public String getSpanType() {
                throw new UnsupportedOperationException();
            }

            @Override
            public MutableSpan setSpanType(CharSequence s) {
                throw new UnsupportedOperationException();
            }

            @Override
            public MutableSpan setTag(String k, String v) {
                throw new UnsupportedOperationException();
            }

            @Override
            public MutableSpan setTag(String k, boolean v) {
                throw new UnsupportedOperationException();
            }

            @Override
            public MutableSpan setTag(String k, Number v) {
                throw new UnsupportedOperationException();
            }

            @Override
            public MutableSpan setMetric(CharSequence k, int v) {
                throw new UnsupportedOperationException();
            }

            @Override
            public MutableSpan setMetric(CharSequence k, long v) {
                throw new UnsupportedOperationException();
            }

            @Override
            public MutableSpan setMetric(CharSequence k, float v) {
                throw new UnsupportedOperationException();
            }

            @Override
            public MutableSpan setMetric(CharSequence k, double v) {
                throw new UnsupportedOperationException();
            }

            @Override
            public MutableSpan setError(boolean b) {
                throw new UnsupportedOperationException();
            }

            @Override
            public MutableSpan getRootSpan() {
                throw new UnsupportedOperationException();
            }

            @Override
            public MutableSpan getLocalRootSpan() {
                throw new UnsupportedOperationException();
            }
        };
    }
}
