package dev.braintrust.trace;

import dev.braintrust.config.BraintrustConfig;
import io.opentelemetry.exporter.otlp.http.logs.OtlpHttpLogRecordExporter;
import io.opentelemetry.sdk.common.CompletableResultCode;
import io.opentelemetry.sdk.logs.data.LogRecordData;
import io.opentelemetry.sdk.logs.export.LogRecordExporter;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;

/**
 * Custom log exporter for Braintrust that adds the x-bt-parent header dynamically based on log
 * attributes.
 */
@Slf4j
class BraintrustLogExporter implements LogRecordExporter {

    private final BraintrustConfig config;
    private final String logsEndpoint;
    private final Map<String, OtlpHttpLogRecordExporter> exporterCache = new ConcurrentHashMap<>();

    public BraintrustLogExporter(BraintrustConfig config) {
        this.config = config;
        this.logsEndpoint = config.apiUrl() + config.logsPath();
    }

    @Override
    public CompletableResultCode export(Collection<LogRecordData> logs) {
        if (logs.isEmpty()) {
            return CompletableResultCode.ofSuccess();
        }

        // Group logs by their parent (project or experiment)
        var logsByParent = logs.stream().collect(Collectors.groupingBy(this::getParentFromLog));

        // Export each group with the appropriate x-bt-parent header
        var results =
                logsByParent.entrySet().stream()
                        .map(entry -> exportWithParent(entry.getKey(), entry.getValue()))
                        .toList();

        // Combine all results
        var combined = CompletableResultCode.ofAll(results);

        return combined;
    }

    private String getParentFromLog(LogRecordData log) {
        var parent = log.getAttributes().get(BraintrustSpanProcessor.PARENT);
        if (parent != null) {
            return parent;
        }
        return config.getBraintrustParentValue().orElse("");
    }

    private CompletableResultCode exportWithParent(String parent, List<LogRecordData> logs) {
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
                                        OtlpHttpLogRecordExporter.builder()
                                                .setEndpoint(logsEndpoint)
                                                .addHeader(
                                                        "Authorization",
                                                        "Bearer " + config.apiKey())
                                                .setTimeout(config.requestTimeout());

                                // Add x-bt-parent header if we have a parent
                                if (!p.isEmpty()) {
                                    exporterBuilder.addHeader("x-bt-parent", p);
                                    log.debug("Created log exporter with x-bt-parent: {}", p);
                                }

                                return exporterBuilder.build();
                            });

            var result = exporter.export(logs);
            // NOTE: whenComplete mutates the original object. does not copy.
            return result.whenComplete(
                    () -> {
                        if (result.isSuccess()) {
                            log.debug(
                                    "Successfully exported {} logs with x-bt-parent: {}",
                                    logs.size(),
                                    parent);
                        } else {
                            log.warn(
                                    "Failed to export {} spans to endpoint {}",
                                    logs.size(),
                                    logsEndpoint,
                                    result.getFailureThrowable());
                        }
                    });
        } catch (Exception e) {
            log.error("Failed to export logs", e);
            return CompletableResultCode.ofFailure();
        }
    }

    @Override
    public CompletableResultCode flush() {
        // Flush all cached exporters
        var results =
                exporterCache.values().stream().map(OtlpHttpLogRecordExporter::flush).toList();
        return CompletableResultCode.ofAll(results);
    }

    @Override
    public CompletableResultCode shutdown() {
        // Shutdown all cached exporters
        var results =
                exporterCache.values().stream().map(OtlpHttpLogRecordExporter::shutdown).toList();
        exporterCache.clear();
        return CompletableResultCode.ofAll(results);
    }
}
