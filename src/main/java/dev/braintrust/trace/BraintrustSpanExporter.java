package dev.braintrust.trace;

import dev.braintrust.config.BraintrustConfig;
import io.opentelemetry.exporter.otlp.http.trace.OtlpHttpSpanExporter;
import io.opentelemetry.sdk.common.CompletableResultCode;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.export.SpanExporter;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;

/**
 * Custom span exporter for Braintrust that adds the x-bt-parent header dynamically based on span
 * attributes.
 */
@Slf4j
class BraintrustSpanExporter implements SpanExporter {
    /** Only used in unit tests. */
    static final Map<String, List<SpanData>> SPANS_EXPORTED = new ConcurrentHashMap<>();

    private final BraintrustConfig config;
    private final String tracesEndpoint;
    private final Map<String, OtlpHttpSpanExporter> exporterCache = new ConcurrentHashMap<>();

    public BraintrustSpanExporter(BraintrustConfig config) {
        this.config = config;
        this.tracesEndpoint = config.apiUrl() + config.tracesPath();
    }

    @Override
    public CompletableResultCode export(Collection<SpanData> spans) {
        if (spans.isEmpty()) {
            return CompletableResultCode.ofSuccess();
        }

        // Group spans by their parent (project or experiment)
        var spansByParent = spans.stream().collect(Collectors.groupingBy(this::getParentFromSpan));

        // Export each group with the appropriate x-bt-parent header
        var results =
                spansByParent.entrySet().stream()
                        .map(entry -> exportWithParent(entry.getKey(), entry.getValue()))
                        .toList();

        // Combine all results
        var combined = CompletableResultCode.ofAll(results);

        return combined;
    }

    private String getParentFromSpan(SpanData span) {
        var parent = span.getAttributes().get(BraintrustSpanProcessor.PARENT);
        if (parent != null) {
            return parent;
        }
        return config.getBraintrustParentValue().orElse("");
    }

    private CompletableResultCode exportWithParent(String parent, List<SpanData> spans) {
        try {
            // Get or create exporter for this parent
            if (exporterCache.size() >= 1024) {
                log.info("Clearing exporter cache. This should not happen");
                exporterCache.clear();
            }
            var exporter =
                    exporterCache.computeIfAbsent(
                            parent,
                            p -> {
                                var exporterBuilder =
                                        OtlpHttpSpanExporter.builder()
                                                .setEndpoint(tracesEndpoint)
                                                .addHeader(
                                                        "Authorization",
                                                        "Bearer " + config.apiKey())
                                                .setTimeout(config.requestTimeout());

                                // Add x-bt-parent header if we have a parent
                                if (!p.isEmpty()) {
                                    exporterBuilder.addHeader("x-bt-parent", p);
                                    log.debug("Created exporter with x-bt-parent: {}", p);
                                }

                                return exporterBuilder.build();
                            });

            if (config.exportSpansInMemoryForUnitTest()) {
                SPANS_EXPORTED.putIfAbsent(parent, new CopyOnWriteArrayList<>());
                SPANS_EXPORTED.get(parent).addAll(spans);
                return CompletableResultCode.ofSuccess();
            } else {
                var result = exporter.export(spans);
                // NOTE: whenComplete mutates the original object. does not copy.
                return result.whenComplete(
                        () -> {
                            if (result.isSuccess()) {
                                log.debug(
                                        "Successfully exported {} spans with x-bt-parent: {}",
                                        spans.size(),
                                        parent);
                            } else {
                                log.warn(
                                        "Failed to export {} spans to endpoint {}",
                                        spans.size(),
                                        tracesEndpoint,
                                        result.getFailureThrowable());
                            }
                        });
            }
        } catch (Exception e) {
            log.error("Failed to export spans", e);
            return CompletableResultCode.ofFailure();
        }
    }

    @Override
    public CompletableResultCode flush() {
        // Flush all cached exporters
        var results = exporterCache.values().stream().map(OtlpHttpSpanExporter::flush).toList();
        return CompletableResultCode.ofAll(results);
    }

    @Override
    public CompletableResultCode shutdown() {
        // Shutdown all cached exporters
        var results = exporterCache.values().stream().map(OtlpHttpSpanExporter::shutdown).toList();
        exporterCache.clear();
        return CompletableResultCode.ofAll(results);
    }
}
