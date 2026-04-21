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

    /**
     * Regression test for the three-span DD trace scenario:
     *
     * <pre>
     *   span0 (root):  servlet.request/GET /hello          p_id=0
     *   span1:         spring.handler/HelloController.hello p_id=span0
     *   span2:         internal/bt-hello-endpoint           p_id=span1  (has braintrust.* tags)
     * </pre>
     *
     * <p>All three spans must be replayed, and the parent-child chain must be intact so that span2
     * is a grandchild of the root (span0 → span1 → span2). Previously, the stored context for span1
     * was not nested within span0's context, causing grandchild context propagation to break.
     */
    @Test
    void replayTraceThreeSpanChainPreservesFullAncestorChain() {
        var exporter = InMemorySpanExporter.create();
        var tracerProvider =
                SdkTracerProvider.builder()
                        .setIdGenerator(OverridableIdGenerator.INSTANCE)
                        .addSpanProcessor(SimpleSpanProcessor.create(exporter))
                        .build();
        var tracer = tracerProvider.get("test");

        // IDs derived from the reported DD trace (converted to OTel hex format)
        String traceId = "36a36890bcfbfa000000000000000000"; // t_id=3947296854096260224 (padded)
        String span0Id = "2440eba0d8e703fa"; // s_id=2613412480130368506
        String span1Id = "595e2b47e54cac29"; // s_id=6434253942510509801
        String span2Id = "2590fcf5a2b71400"; // s_id=2707212214605045248

        long now = 1_700_000_000_000_000_000L;

        var ctx0 =
                io.opentelemetry.api.trace.SpanContext.create(
                        traceId,
                        span0Id,
                        io.opentelemetry.api.trace.TraceFlags.getSampled(),
                        io.opentelemetry.api.trace.TraceState.getDefault());
        var ctx1 =
                io.opentelemetry.api.trace.SpanContext.create(
                        traceId,
                        span1Id,
                        io.opentelemetry.api.trace.TraceFlags.getSampled(),
                        io.opentelemetry.api.trace.TraceState.getDefault());
        var ctx2 =
                io.opentelemetry.api.trace.SpanContext.create(
                        traceId,
                        span2Id,
                        io.opentelemetry.api.trace.TraceFlags.getSampled(),
                        io.opentelemetry.api.trace.TraceState.getDefault());

        Map<String, Object> braintrustTags = new HashMap<>();
        braintrustTags.put("braintrust.input", "GET /hello");
        braintrustTags.put("braintrust.name", "hello-request");
        braintrustTags.put("braintrust.output", "Hello, World!");

        // span0: root servlet span (no braintrust tags)
        var span0Data =
                new ImmutableSpanData(
                        ctx0,
                        io.opentelemetry.api.trace.SpanContext.getInvalid(),
                        io.opentelemetry.sdk.resources.Resource.getDefault(),
                        io.opentelemetry.sdk.common.InstrumentationScopeInfo.create("test"),
                        "GET /hello",
                        SpanKind.SERVER,
                        now,
                        now + 2_174_213_333L,
                        io.opentelemetry.api.common.Attributes.empty(),
                        io.opentelemetry.sdk.trace.data.StatusData.unset());
        // span1: spring handler span, child of span0
        var span1Data =
                new ImmutableSpanData(
                        ctx1,
                        ctx0,
                        io.opentelemetry.sdk.resources.Resource.getDefault(),
                        io.opentelemetry.sdk.common.InstrumentationScopeInfo.create("test"),
                        "HelloController.hello",
                        SpanKind.SERVER,
                        now + 1_000_000L,
                        now + 1_000_000L + 7_459_500L,
                        io.opentelemetry.api.common.Attributes.empty(),
                        io.opentelemetry.sdk.trace.data.StatusData.unset());
        // span2: braintrust internal span, grandchild (child of span1)
        var span2Attrs =
                io.opentelemetry.api.common.Attributes.builder()
                        .put("braintrust.input", "GET /hello")
                        .put("braintrust.name", "hello-request")
                        .put("braintrust.output", "Hello, World!")
                        .build();
        var span2Data =
                new ImmutableSpanData(
                        ctx2,
                        ctx1,
                        io.opentelemetry.sdk.resources.Resource.getDefault(),
                        io.opentelemetry.sdk.common.InstrumentationScopeInfo.create("test"),
                        "hello-request",
                        SpanKind.INTERNAL,
                        now + 2_000_000L,
                        now + 2_000_000L + 35_666L,
                        span2Attrs,
                        io.opentelemetry.sdk.trace.data.StatusData.unset());

        // Provide spans in original order (0, 1, 2) — topological sort must handle this correctly.
        DDSpanConverter.replayTrace(tracer, List.of(span0Data, span1Data, span2Data));

        var exported = exporter.getFinishedSpanItems();
        assertEquals(3, exported.size(), "all three spans must be replayed");

        var replayedSpan0 =
                exported.stream().filter(s -> s.getName().equals("GET /hello")).findFirst().get();
        var replayedSpan1 =
                exported.stream()
                        .filter(s -> s.getName().equals("HelloController.hello"))
                        .findFirst()
                        .get();
        var replayedSpan2 =
                exported.stream()
                        .filter(s -> s.getName().equals("hello-request"))
                        .findFirst()
                        .get();

        // All spans share the same trace ID.
        assertEquals(traceId, replayedSpan0.getSpanContext().getTraceId(), "span0 traceId");
        assertEquals(traceId, replayedSpan1.getSpanContext().getTraceId(), "span1 traceId");
        assertEquals(traceId, replayedSpan2.getSpanContext().getTraceId(), "span2 traceId");

        // Original span IDs are preserved.
        assertEquals(span0Id, replayedSpan0.getSpanContext().getSpanId(), "span0 spanId");
        assertEquals(span1Id, replayedSpan1.getSpanContext().getSpanId(), "span1 spanId");
        assertEquals(span2Id, replayedSpan2.getSpanContext().getSpanId(), "span2 spanId");

        // span0 is the root — no parent.
        assertFalse(replayedSpan0.getParentSpanContext().isValid(), "span0 must have no parent");

        // span1's parent is span0.
        assertTrue(replayedSpan1.getParentSpanContext().isValid(), "span1 must have a parent");
        assertEquals(
                span0Id,
                replayedSpan1.getParentSpanContext().getSpanId(),
                "span1 parent must be span0");

        // span2's parent is span1 (grandchild of span0).
        assertTrue(replayedSpan2.getParentSpanContext().isValid(), "span2 must have a parent");
        assertEquals(
                span1Id,
                replayedSpan2.getParentSpanContext().getSpanId(),
                "span2 parent must be span1");

        // span2's braintrust attributes must be preserved.
        var attrs2 = replayedSpan2.getAttributes();
        assertEquals("GET /hello", attrs2.get(AttributeKey.stringKey("braintrust.input")));
        assertEquals("hello-request", attrs2.get(AttributeKey.stringKey("braintrust.name")));
        assertEquals("Hello, World!", attrs2.get(AttributeKey.stringKey("braintrust.output")));

        tracerProvider.close();
    }

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

    // ── replayTrace edge cases ─────────────────────────────────────────────────

    @Test
    void replayTraceNullListIsNoop() {
        var exporter = InMemorySpanExporter.create();
        var tracerProvider =
                SdkTracerProvider.builder()
                        .setIdGenerator(OverridableIdGenerator.INSTANCE)
                        .addSpanProcessor(SimpleSpanProcessor.create(exporter))
                        .build();
        var tracer = tracerProvider.get("test");

        assertDoesNotThrow(() -> DDSpanConverter.replayTrace(tracer, null));
        assertEquals(0, exporter.getFinishedSpanItems().size());

        tracerProvider.close();
    }

    @Test
    void replayTraceEmptyListIsNoop() {
        var exporter = InMemorySpanExporter.create();
        var tracerProvider =
                SdkTracerProvider.builder()
                        .setIdGenerator(OverridableIdGenerator.INSTANCE)
                        .addSpanProcessor(SimpleSpanProcessor.create(exporter))
                        .build();
        var tracer = tracerProvider.get("test");

        assertDoesNotThrow(() -> DDSpanConverter.replayTrace(tracer, List.of()));
        assertEquals(0, exporter.getFinishedSpanItems().size());

        tracerProvider.close();
    }

    @Test
    void replayTraceSingleSpanNoParent() {
        var exporter = InMemorySpanExporter.create();
        var tracerProvider =
                SdkTracerProvider.builder()
                        .setIdGenerator(OverridableIdGenerator.INSTANCE)
                        .addSpanProcessor(SimpleSpanProcessor.create(exporter))
                        .build();
        var tracer = tracerProvider.get("test");

        var spanCtx =
                io.opentelemetry.api.trace.SpanContext.create(
                        "abcdef1234567890abcdef1234567890",
                        "1234567890abcdef",
                        io.opentelemetry.api.trace.TraceFlags.getSampled(),
                        io.opentelemetry.api.trace.TraceState.getDefault());
        long now = 1_700_000_000_000_000_000L;
        var spanData =
                new ImmutableSpanData(
                        spanCtx,
                        io.opentelemetry.api.trace.SpanContext.getInvalid(),
                        io.opentelemetry.sdk.resources.Resource.getDefault(),
                        io.opentelemetry.sdk.common.InstrumentationScopeInfo.create("test"),
                        "lone-span",
                        SpanKind.INTERNAL,
                        now,
                        now + 1_000_000L,
                        io.opentelemetry.api.common.Attributes.empty(),
                        io.opentelemetry.sdk.trace.data.StatusData.unset());

        DDSpanConverter.replayTrace(tracer, List.of(spanData));

        var exported = exporter.getFinishedSpanItems();
        assertEquals(1, exported.size());
        assertEquals("lone-span", exported.get(0).getName());
        assertEquals(
                "abcdef1234567890abcdef1234567890", exported.get(0).getSpanContext().getTraceId());
        assertEquals("1234567890abcdef", exported.get(0).getSpanContext().getSpanId());
        assertFalse(exported.get(0).getParentSpanContext().isValid());

        tracerProvider.close();
    }

    /**
     * A batch may contain two unrelated root spans (e.g. from concurrent requests). Both must be
     * replayed independently with no cross-contamination of trace IDs or parent links.
     */
    @Test
    void replayTraceMultipleIndependentRoots() {
        var exporter = InMemorySpanExporter.create();
        var tracerProvider =
                SdkTracerProvider.builder()
                        .setIdGenerator(OverridableIdGenerator.INSTANCE)
                        .addSpanProcessor(SimpleSpanProcessor.create(exporter))
                        .build();
        var tracer = tracerProvider.get("test");

        String traceIdA = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";
        String traceIdB = "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb";

        var ctxA =
                io.opentelemetry.api.trace.SpanContext.create(
                        traceIdA,
                        "aaaaaaaaaaaaaaaa",
                        io.opentelemetry.api.trace.TraceFlags.getSampled(),
                        io.opentelemetry.api.trace.TraceState.getDefault());
        var ctxAChild =
                io.opentelemetry.api.trace.SpanContext.create(
                        traceIdA,
                        "aaaaaaaaaaaaaabb",
                        io.opentelemetry.api.trace.TraceFlags.getSampled(),
                        io.opentelemetry.api.trace.TraceState.getDefault());
        var ctxB =
                io.opentelemetry.api.trace.SpanContext.create(
                        traceIdB,
                        "bbbbbbbbbbbbbbbb",
                        io.opentelemetry.api.trace.TraceFlags.getSampled(),
                        io.opentelemetry.api.trace.TraceState.getDefault());

        long now = 1_700_000_000_000_000_000L;
        var spanA =
                new ImmutableSpanData(
                        ctxA,
                        io.opentelemetry.api.trace.SpanContext.getInvalid(),
                        io.opentelemetry.sdk.resources.Resource.getDefault(),
                        io.opentelemetry.sdk.common.InstrumentationScopeInfo.create("test"),
                        "root-A",
                        SpanKind.SERVER,
                        now,
                        now + 1_000_000L,
                        io.opentelemetry.api.common.Attributes.empty(),
                        io.opentelemetry.sdk.trace.data.StatusData.unset());
        var spanAChild =
                new ImmutableSpanData(
                        ctxAChild,
                        ctxA,
                        io.opentelemetry.sdk.resources.Resource.getDefault(),
                        io.opentelemetry.sdk.common.InstrumentationScopeInfo.create("test"),
                        "child-A",
                        SpanKind.INTERNAL,
                        now + 100_000L,
                        now + 900_000L,
                        io.opentelemetry.api.common.Attributes.empty(),
                        io.opentelemetry.sdk.trace.data.StatusData.unset());
        var spanB =
                new ImmutableSpanData(
                        ctxB,
                        io.opentelemetry.api.trace.SpanContext.getInvalid(),
                        io.opentelemetry.sdk.resources.Resource.getDefault(),
                        io.opentelemetry.sdk.common.InstrumentationScopeInfo.create("test"),
                        "root-B",
                        SpanKind.SERVER,
                        now + 500_000L,
                        now + 2_000_000L,
                        io.opentelemetry.api.common.Attributes.empty(),
                        io.opentelemetry.sdk.trace.data.StatusData.unset());

        DDSpanConverter.replayTrace(tracer, List.of(spanA, spanAChild, spanB));

        var exported = exporter.getFinishedSpanItems();
        assertEquals(3, exported.size(), "all three spans must be replayed");

        var replayedA =
                exported.stream().filter(s -> s.getName().equals("root-A")).findFirst().get();
        var replayedAChild =
                exported.stream().filter(s -> s.getName().equals("child-A")).findFirst().get();
        var replayedB =
                exported.stream().filter(s -> s.getName().equals("root-B")).findFirst().get();

        // Each root keeps its own trace ID.
        assertEquals(traceIdA, replayedA.getSpanContext().getTraceId(), "root-A traceId");
        assertEquals(traceIdA, replayedAChild.getSpanContext().getTraceId(), "child-A traceId");
        assertEquals(traceIdB, replayedB.getSpanContext().getTraceId(), "root-B traceId");

        // root-B must have no parent — it is independent of trace A.
        assertFalse(replayedB.getParentSpanContext().isValid(), "root-B must have no parent");

        // child-A's parent is root-A.
        assertTrue(replayedAChild.getParentSpanContext().isValid());
        assertEquals("aaaaaaaaaaaaaaaa", replayedAChild.getParentSpanContext().getSpanId());

        tracerProvider.close();
    }

    /**
     * When spans arrive in fully reversed order (grandchild, child, root), the topological sort
     * must still produce the correct parent-before-child ordering.
     */
    @Test
    void replayTraceReversedInputOrderSortedCorrectly() {
        var exporter = InMemorySpanExporter.create();
        var tracerProvider =
                SdkTracerProvider.builder()
                        .setIdGenerator(OverridableIdGenerator.INSTANCE)
                        .addSpanProcessor(SimpleSpanProcessor.create(exporter))
                        .build();
        var tracer = tracerProvider.get("test");

        String traceId = "cccccccccccccccccccccccccccccccc";
        var ctxRoot =
                io.opentelemetry.api.trace.SpanContext.create(
                        traceId,
                        "1111111111111111",
                        io.opentelemetry.api.trace.TraceFlags.getSampled(),
                        io.opentelemetry.api.trace.TraceState.getDefault());
        var ctxChild =
                io.opentelemetry.api.trace.SpanContext.create(
                        traceId,
                        "2222222222222222",
                        io.opentelemetry.api.trace.TraceFlags.getSampled(),
                        io.opentelemetry.api.trace.TraceState.getDefault());
        var ctxGrandchild =
                io.opentelemetry.api.trace.SpanContext.create(
                        traceId,
                        "3333333333333333",
                        io.opentelemetry.api.trace.TraceFlags.getSampled(),
                        io.opentelemetry.api.trace.TraceState.getDefault());

        long now = 1_700_000_000_000_000_000L;
        var root =
                new ImmutableSpanData(
                        ctxRoot,
                        io.opentelemetry.api.trace.SpanContext.getInvalid(),
                        io.opentelemetry.sdk.resources.Resource.getDefault(),
                        io.opentelemetry.sdk.common.InstrumentationScopeInfo.create("test"),
                        "root",
                        SpanKind.SERVER,
                        now,
                        now + 3_000_000L,
                        io.opentelemetry.api.common.Attributes.empty(),
                        io.opentelemetry.sdk.trace.data.StatusData.unset());
        var child =
                new ImmutableSpanData(
                        ctxChild,
                        ctxRoot,
                        io.opentelemetry.sdk.resources.Resource.getDefault(),
                        io.opentelemetry.sdk.common.InstrumentationScopeInfo.create("test"),
                        "child",
                        SpanKind.INTERNAL,
                        now + 500_000L,
                        now + 2_500_000L,
                        io.opentelemetry.api.common.Attributes.empty(),
                        io.opentelemetry.sdk.trace.data.StatusData.unset());
        var grandchild =
                new ImmutableSpanData(
                        ctxGrandchild,
                        ctxChild,
                        io.opentelemetry.sdk.resources.Resource.getDefault(),
                        io.opentelemetry.sdk.common.InstrumentationScopeInfo.create("test"),
                        "grandchild",
                        SpanKind.CLIENT,
                        now + 1_000_000L,
                        now + 2_000_000L,
                        io.opentelemetry.api.common.Attributes.empty(),
                        io.opentelemetry.sdk.trace.data.StatusData.unset());

        // Provide in fully reversed order: grandchild, child, root.
        DDSpanConverter.replayTrace(tracer, List.of(grandchild, child, root));

        var exported = exporter.getFinishedSpanItems();
        assertEquals(3, exported.size(), "all three spans must be replayed");

        var replayedRoot =
                exported.stream().filter(s -> s.getName().equals("root")).findFirst().get();
        var replayedChild =
                exported.stream().filter(s -> s.getName().equals("child")).findFirst().get();
        var replayedGrandchild =
                exported.stream().filter(s -> s.getName().equals("grandchild")).findFirst().get();

        // IDs preserved.
        assertEquals("1111111111111111", replayedRoot.getSpanContext().getSpanId());
        assertEquals("2222222222222222", replayedChild.getSpanContext().getSpanId());
        assertEquals("3333333333333333", replayedGrandchild.getSpanContext().getSpanId());

        // All share the same trace ID.
        assertEquals(traceId, replayedRoot.getSpanContext().getTraceId());
        assertEquals(traceId, replayedChild.getSpanContext().getTraceId());
        assertEquals(traceId, replayedGrandchild.getSpanContext().getTraceId());

        // Parent links correct.
        assertFalse(replayedRoot.getParentSpanContext().isValid(), "root has no parent");
        assertEquals(
                "1111111111111111",
                replayedChild.getParentSpanContext().getSpanId(),
                "child parent = root");
        assertEquals(
                "2222222222222222",
                replayedGrandchild.getParentSpanContext().getSpanId(),
                "grandchild parent = child");

        tracerProvider.close();
    }

    /**
     * When a child's parent span is NOT in the current batch (distributed/remote parent), the
     * replayed child must still carry the remote parent's span ID in its parentSpanContext.
     */
    @Test
    void replayTraceRemoteParentNotInBatch() {
        var exporter = InMemorySpanExporter.create();
        var tracerProvider =
                SdkTracerProvider.builder()
                        .setIdGenerator(OverridableIdGenerator.INSTANCE)
                        .addSpanProcessor(SimpleSpanProcessor.create(exporter))
                        .build();
        var tracer = tracerProvider.get("test");

        String traceId = "dddddddddddddddddddddddddddddddd";
        // remoteParentCtx represents a span from an upstream service — not in this batch.
        var remoteParentCtx =
                io.opentelemetry.api.trace.SpanContext.create(
                        traceId,
                        "eeeeeeeeeeeeeeee",
                        io.opentelemetry.api.trace.TraceFlags.getSampled(),
                        io.opentelemetry.api.trace.TraceState.getDefault());
        var localSpanCtx =
                io.opentelemetry.api.trace.SpanContext.create(
                        traceId,
                        "ffffffffffffffff",
                        io.opentelemetry.api.trace.TraceFlags.getSampled(),
                        io.opentelemetry.api.trace.TraceState.getDefault());

        long now = 1_700_000_000_000_000_000L;
        var localSpan =
                new ImmutableSpanData(
                        localSpanCtx,
                        remoteParentCtx, // parent is NOT in this batch
                        io.opentelemetry.sdk.resources.Resource.getDefault(),
                        io.opentelemetry.sdk.common.InstrumentationScopeInfo.create("test"),
                        "local-root",
                        SpanKind.SERVER,
                        now,
                        now + 1_000_000L,
                        io.opentelemetry.api.common.Attributes.empty(),
                        io.opentelemetry.sdk.trace.data.StatusData.unset());

        DDSpanConverter.replayTrace(tracer, List.of(localSpan));

        var exported = exporter.getFinishedSpanItems();
        assertEquals(1, exported.size());
        var replayed = exported.get(0);

        assertEquals("ffffffffffffffff", replayed.getSpanContext().getSpanId(), "spanId preserved");
        assertEquals(traceId, replayed.getSpanContext().getTraceId(), "traceId preserved");
        // The remote parent context must still be attached.
        assertTrue(replayed.getParentSpanContext().isValid(), "remote parent must be valid");
        assertEquals(
                "eeeeeeeeeeeeeeee",
                replayed.getParentSpanContext().getSpanId(),
                "remote parent spanId preserved");

        tracerProvider.close();
    }

    /**
     * Error status on a span must survive the full replayTrace round-trip and appear on the
     * exported span.
     */
    @Test
    void replayTraceErrorStatusPreserved() {
        var exporter = InMemorySpanExporter.create();
        var tracerProvider =
                SdkTracerProvider.builder()
                        .setIdGenerator(OverridableIdGenerator.INSTANCE)
                        .addSpanProcessor(SimpleSpanProcessor.create(exporter))
                        .build();
        var tracer = tracerProvider.get("test");

        var spanCtx =
                io.opentelemetry.api.trace.SpanContext.create(
                        "eeeeeeeeeeeeeeeeeeeeeeeeeeeeeeee",
                        "eeeeeeeeeeeeeeee",
                        io.opentelemetry.api.trace.TraceFlags.getSampled(),
                        io.opentelemetry.api.trace.TraceState.getDefault());
        long now = 1_700_000_000_000_000_000L;
        var spanData =
                new ImmutableSpanData(
                        spanCtx,
                        io.opentelemetry.api.trace.SpanContext.getInvalid(),
                        io.opentelemetry.sdk.resources.Resource.getDefault(),
                        io.opentelemetry.sdk.common.InstrumentationScopeInfo.create("test"),
                        "error-span",
                        SpanKind.INTERNAL,
                        now,
                        now + 500_000L,
                        io.opentelemetry.api.common.Attributes.empty(),
                        io.opentelemetry.sdk.trace.data.StatusData.create(
                                StatusCode.ERROR, "something broke"));

        DDSpanConverter.replayTrace(tracer, List.of(spanData));

        var exported = exporter.getFinishedSpanItems();
        assertEquals(1, exported.size());
        assertEquals(StatusCode.ERROR, exported.get(0).getStatus().getStatusCode());

        tracerProvider.close();
    }

    // ── Tag edge cases ─────────────────────────────────────────────────────────

    @Test
    void nullTagValueIsSkipped() {
        Map<String, Object> tags = new HashMap<>();
        tags.put("null.tag", null);
        tags.put("keep.tag", "present");
        var ddSpan = stubSpan("span", "internal", 1_000_000_000L, 0L, tags, false);
        DDTraceId traceId = DDTraceId.from(1L);

        SpanData result =
                assertDoesNotThrow(() -> DDSpanConverter.convertSpan(ddSpan, traceId, 1L, 0L));

        var attrs = result.getAttributes();
        // null-valued tag must not appear (and must not throw)
        assertNull(attrs.get(AttributeKey.stringKey("null.tag")));
        assertEquals("present", attrs.get(AttributeKey.stringKey("keep.tag")));
    }

    @Test
    void unknownTagTypeConvertedViaToString() {
        // A custom object type falls through to the toString() fallback.
        Object customValue =
                new Object() {
                    @Override
                    public String toString() {
                        return "custom-value";
                    }
                };
        Map<String, Object> tags = new HashMap<>();
        tags.put("custom.tag", customValue);
        var ddSpan = stubSpan("span", "internal", 1_000_000_000L, 0L, tags, false);
        DDTraceId traceId = DDTraceId.from(1L);

        SpanData result = DDSpanConverter.convertSpan(ddSpan, traceId, 1L, 0L);

        assertEquals(
                "custom-value", result.getAttributes().get(AttributeKey.stringKey("custom.tag")));
    }

    @Test
    void nullTagsMapProducesEmptyAttributes() {
        // MutableSpan.getTags() returning null must not throw.
        var ddSpan = stubSpan("span", "internal", 1_000_000_000L, 0L, null, false);
        DDTraceId traceId = DDTraceId.from(1L);

        SpanData result =
                assertDoesNotThrow(() -> DDSpanConverter.convertSpan(ddSpan, traceId, 1L, 0L));

        assertEquals(0, result.getAttributes().size());
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
