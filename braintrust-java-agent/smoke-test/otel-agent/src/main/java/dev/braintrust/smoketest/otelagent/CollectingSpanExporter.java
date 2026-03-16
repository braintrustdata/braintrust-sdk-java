package dev.braintrust.smoketest.otelagent;

import io.opentelemetry.sdk.common.CompletableResultCode;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.export.SpanExporter;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/** SpanExporter that collects exported spans grouped by traceId for test assertions. */
public class CollectingSpanExporter implements SpanExporter {

    private final Map<String, List<SpanData>> tracesByTraceId = new ConcurrentHashMap<>();

    @Override
    public CompletableResultCode export(Collection<SpanData> spans) {
        for (SpanData sd : spans) {
            tracesByTraceId
                    .computeIfAbsent(
                            sd.getSpanContext().getTraceId(), k -> new CopyOnWriteArrayList<>())
                    .add(sd);
        }
        return CompletableResultCode.ofSuccess();
    }

    @Override
    public CompletableResultCode flush() {
        return CompletableResultCode.ofSuccess();
    }

    @Override
    public CompletableResultCode shutdown() {
        return CompletableResultCode.ofSuccess();
    }

    public Map<String, List<SpanData>> getTracesByTraceId() {
        return tracesByTraceId;
    }
}
