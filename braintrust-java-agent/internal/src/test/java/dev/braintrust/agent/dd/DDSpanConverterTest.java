package dev.braintrust.agent.dd;

import static org.junit.jupiter.api.Assertions.*;

import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.trace.*;
import io.opentelemetry.sdk.common.InstrumentationScopeInfo;
import io.opentelemetry.sdk.resources.Resource;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.data.StatusData;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class DDSpanConverterTest {

    private InMemorySpanExporter exporter;
    private SdkTracerProvider tracerProvider;
    private Tracer tracer;

    @BeforeEach
    void setUp() {
        exporter = InMemorySpanExporter.create();
        tracerProvider =
                SdkTracerProvider.builder()
                        .addSpanProcessor(SimpleSpanProcessor.create(exporter))
                        .build();
        tracer = tracerProvider.get("test");
    }

    @AfterEach
    void tearDown() {
        tracerProvider.close();
    }

    @Test
    void replayTrace_singleSpan() {
        long startNanos = TimeUnit.MILLISECONDS.toNanos(System.currentTimeMillis());
        long endNanos = startNanos + TimeUnit.MILLISECONDS.toNanos(100);

        SpanData input =
                makeSpan(
                        "00000000000000000000000000000001",
                        "0000000000000001",
                        SpanContext.getInvalid(),
                        "root-span",
                        SpanKind.SERVER,
                        startNanos,
                        endNanos,
                        Attributes.builder().put("http.method", "GET").build(),
                        StatusData.unset());

        DDSpanConverter.replayTrace(tracer, List.of(input));

        List<SpanData> exported = exporter.getFinishedSpanItems();
        assertEquals(1, exported.size());

        SpanData out = exported.get(0);
        assertEquals("root-span", out.getName());
        assertEquals(SpanKind.SERVER, out.getKind());
        assertEquals(
                "GET",
                out.getAttributes()
                        .get(io.opentelemetry.api.common.AttributeKey.stringKey("http.method")));
        assertEquals(startNanos, out.getStartEpochNanos());
        assertEquals(endNanos, out.getEndEpochNanos());
    }

    @Test
    void replayTrace_parentChildOrdering() {
        long t0 = TimeUnit.MILLISECONDS.toNanos(System.currentTimeMillis());

        String traceId = "00000000000000000000000000000002";

        SpanContext parentCtx =
                SpanContext.create(
                        traceId,
                        "000000000000000a",
                        TraceFlags.getSampled(),
                        TraceState.getDefault());
        SpanContext childCtx =
                SpanContext.create(
                        traceId,
                        "000000000000000b",
                        TraceFlags.getSampled(),
                        TraceState.getDefault());

        SpanData child =
                makeSpan(
                        traceId,
                        "000000000000000b",
                        parentCtx,
                        "child-span",
                        SpanKind.CLIENT,
                        t0 + 10,
                        t0 + 50,
                        Attributes.empty(),
                        StatusData.unset());

        SpanData parent =
                makeSpan(
                        traceId,
                        "000000000000000a",
                        SpanContext.getInvalid(),
                        "parent-span",
                        SpanKind.SERVER,
                        t0,
                        t0 + 100,
                        Attributes.empty(),
                        StatusData.unset());

        // Provide child first — replayTrace should still set up parent context correctly
        DDSpanConverter.replayTrace(tracer, List.of(child, parent));

        List<SpanData> exported = exporter.getFinishedSpanItems();
        assertEquals(2, exported.size());

        // Find the child in exported spans and verify it has a valid parent
        SpanData exportedChild =
                exported.stream()
                        .filter(s -> s.getName().equals("child-span"))
                        .findFirst()
                        .orElseThrow();
        assertTrue(
                exportedChild.getParentSpanContext().isValid(),
                "child should have a valid parent span context");
    }

    @Test
    void replayTrace_errorStatus() {
        long t0 = TimeUnit.MILLISECONDS.toNanos(System.currentTimeMillis());

        SpanData errorSpan =
                makeSpan(
                        "00000000000000000000000000000003",
                        "0000000000000001",
                        SpanContext.getInvalid(),
                        "error-span",
                        SpanKind.INTERNAL,
                        t0,
                        t0 + 50,
                        Attributes.empty(),
                        StatusData.create(StatusCode.ERROR, "something failed"));

        DDSpanConverter.replayTrace(tracer, List.of(errorSpan));

        List<SpanData> exported = exporter.getFinishedSpanItems();
        assertEquals(1, exported.size());
        assertEquals(StatusCode.ERROR, exported.get(0).getStatus().getStatusCode());
    }

    @Test
    void replayTrace_emptyList() {
        DDSpanConverter.replayTrace(tracer, List.of());
        assertTrue(exporter.getFinishedSpanItems().isEmpty());
    }

    @Test
    void replayTrace_nullList() {
        DDSpanConverter.replayTrace(tracer, null);
        assertTrue(exporter.getFinishedSpanItems().isEmpty());
    }

    @Test
    void replayTrace_attributesPreserved() {
        long t0 = TimeUnit.MILLISECONDS.toNanos(System.currentTimeMillis());

        Attributes attrs =
                Attributes.builder()
                        .put("string.key", "value")
                        .put("long.key", 42L)
                        .put("double.key", 3.14)
                        .put("bool.key", true)
                        .build();

        SpanData span =
                makeSpan(
                        "00000000000000000000000000000004",
                        "0000000000000001",
                        SpanContext.getInvalid(),
                        "attrs-span",
                        SpanKind.INTERNAL,
                        t0,
                        t0 + 10,
                        attrs,
                        StatusData.unset());

        DDSpanConverter.replayTrace(tracer, List.of(span));

        SpanData out = exporter.getFinishedSpanItems().get(0);
        assertEquals(
                "value",
                out.getAttributes()
                        .get(io.opentelemetry.api.common.AttributeKey.stringKey("string.key")));
        assertEquals(
                42L,
                out.getAttributes()
                        .get(io.opentelemetry.api.common.AttributeKey.longKey("long.key")));
        assertEquals(
                3.14,
                out.getAttributes()
                        .get(io.opentelemetry.api.common.AttributeKey.doubleKey("double.key")));
        assertEquals(
                true,
                out.getAttributes()
                        .get(io.opentelemetry.api.common.AttributeKey.booleanKey("bool.key")));
    }

    private static SpanData makeSpan(
            String traceId,
            String spanId,
            SpanContext parentSpanContext,
            String name,
            SpanKind kind,
            long startNanos,
            long endNanos,
            Attributes attributes,
            StatusData status) {
        SpanContext ctx =
                SpanContext.create(
                        traceId, spanId, TraceFlags.getSampled(), TraceState.getDefault());
        return new ImmutableSpanData(
                ctx,
                parentSpanContext,
                Resource.getDefault(),
                InstrumentationScopeInfo.create("test"),
                name,
                kind,
                startNanos,
                endNanos,
                attributes,
                status);
    }
}
