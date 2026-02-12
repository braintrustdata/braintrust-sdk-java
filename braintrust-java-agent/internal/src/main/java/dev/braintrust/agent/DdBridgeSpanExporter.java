package dev.braintrust.agent;

import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanBuilder;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.api.trace.TracerProvider;
import io.opentelemetry.context.Context;
import io.opentelemetry.sdk.common.CompletableResultCode;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.export.SpanExporter;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * SpanExporter that replays finished OTel spans into Datadog's OTel shim TracerProvider.
 *
 * <p>Spans are buffered per-trace by trace ID. When a root span arrives (no valid
 * parent), all buffered spans for that trace are replayed into DD in parent-first
 * order, then the buffer is cleared. This works because the root span always ends
 * last — it encompasses all children — so by the time the root is exported, all
 * child spans for that trace have already been buffered.
 *
 * <p>{@link #flush()} replays any remaining incomplete traces (e.g. on shutdown).
 */
class DdBridgeSpanExporter implements SpanExporter {

    private final TracerProvider ddTracerProvider;

    /** Buffered spans per trace, keyed by OTel trace ID. */
    private final ConcurrentHashMap<String, List<SpanData>> pendingTraces = new ConcurrentHashMap<>();

    DdBridgeSpanExporter(TracerProvider ddTracerProvider) {
        this.ddTracerProvider = ddTracerProvider;
    }

    @Override
    public synchronized CompletableResultCode export(Collection<SpanData> spans) {
        for (SpanData sd : spans) {
            String traceId = sd.getSpanContext().getTraceId();
            pendingTraces.computeIfAbsent(traceId, k -> new ArrayList<>()).add(sd);

            if (isRoot(sd)) {
                replayTrace(traceId);
            }
        }
        return CompletableResultCode.ofSuccess();
    }

    @Override
    public synchronized CompletableResultCode flush() {
        for (String traceId : List.copyOf(pendingTraces.keySet())) {
            replayTrace(traceId);
        }
        return CompletableResultCode.ofSuccess();
    }

    @Override
    public CompletableResultCode shutdown() {
        return flush();
    }

    private static boolean isRoot(SpanData sd) {
        return !sd.getParentSpanContext().isValid();
    }

    private void replayTrace(String traceId) {
        List<SpanData> spans = pendingTraces.remove(traceId);
        if (spans == null || spans.isEmpty()) return;

        List<SpanData> sorted = toParentFirstOrder(spans);

        Map<String, Span> ddSpansByOtelId = new HashMap<>();
        List<Map.Entry<SpanData, Span>> created = new ArrayList<>(sorted.size());

        // Phase 1: Create all DD spans in parent-first order (no end() yet)
        for (SpanData sd : sorted) {
            String instrumentationName = sd.getInstrumentationScopeInfo().getName();
            String instrumentationVersion = sd.getInstrumentationScopeInfo().getVersion();
            Tracer ddTracer = ddTracerProvider.get(instrumentationName, instrumentationVersion);

            SpanBuilder builder = ddTracer.spanBuilder(sd.getName())
                    .setSpanKind(sd.getKind())
                    .setStartTimestamp(sd.getStartEpochNanos(), TimeUnit.NANOSECONDS);

            String parentSpanId = sd.getParentSpanContext().getSpanId();
            Span ddParent = ddSpansByOtelId.get(parentSpanId);
            if (ddParent != null) {
                builder.setParent(Context.current().with(ddParent));
            } else {
                builder.setNoParent();
            }

            sd.getAttributes().forEach((key, value) -> setAttribute(builder, key, value));
            builder.setAttribute("otel.span_id", sd.getSpanContext().getSpanId());

            Span ddSpan = builder.startSpan();

            if (sd.getStatus().getStatusCode() != StatusCode.UNSET) {
                ddSpan.setStatus(sd.getStatus().getStatusCode(), sd.getStatus().getDescription());
            }

            sd.getEvents().forEach(event ->
                    ddSpan.addEvent(event.getName(), event.getAttributes(),
                            event.getEpochNanos(), TimeUnit.NANOSECONDS));

            ddSpansByOtelId.put(sd.getSpanContext().getSpanId(), ddSpan);
            created.add(Map.entry(sd, ddSpan));
        }

        // Phase 2: End in reverse (children-first) so root ends last
        for (int i = created.size() - 1; i >= 0; i--) {
            var entry = created.get(i);
            entry.getValue().end(entry.getKey().getEndEpochNanos(), TimeUnit.NANOSECONDS);
        }
    }

    private static List<SpanData> toParentFirstOrder(List<SpanData> spans) {
        Map<String, SpanData> byId = new HashMap<>();
        for (SpanData sd : spans) {
            byId.put(sd.getSpanContext().getSpanId(), sd);
        }

        List<SpanData> result = new ArrayList<>(spans.size());
        Set<String> visited = new HashSet<>();

        for (SpanData sd : spans) {
            visit(sd, byId, visited, result);
        }
        return result;
    }

    private static void visit(SpanData sd, Map<String, SpanData> byId,
                              Set<String> visited, List<SpanData> result) {
        String id = sd.getSpanContext().getSpanId();
        if (!visited.add(id)) return;

        String parentId = sd.getParentSpanContext().getSpanId();
        SpanData parent = byId.get(parentId);
        if (parent != null) {
            visit(parent, byId, visited, result);
        }

        result.add(sd);
    }

    @SuppressWarnings("unchecked")
    private static void setAttribute(SpanBuilder builder, AttributeKey<?> key, Object value) {
        switch (key.getType()) {
            case STRING -> builder.setAttribute((AttributeKey<String>) key, (String) value);
            case BOOLEAN -> builder.setAttribute((AttributeKey<Boolean>) key, (Boolean) value);
            case LONG -> builder.setAttribute((AttributeKey<Long>) key, (Long) value);
            case DOUBLE -> builder.setAttribute((AttributeKey<Double>) key, (Double) value);
            default -> builder.setAttribute(AttributeKey.stringKey(key.getKey()), String.valueOf(value));
        }
    }
}
