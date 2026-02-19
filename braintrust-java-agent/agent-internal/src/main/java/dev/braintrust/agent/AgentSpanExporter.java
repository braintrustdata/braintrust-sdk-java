package dev.braintrust.agent;

import io.opentelemetry.exporter.otlp.http.trace.OtlpHttpSpanExporter;
import io.opentelemetry.sdk.common.CompletableResultCode;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.export.SpanExporter;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * Span exporter for the Braintrust agent that routes spans to Braintrust's OTLP ingestion endpoint
 * with the appropriate {@code x-bt-parent} header.
 *
 * <p>Mirrors the SDK's {@code BraintrustSpanExporter} but without Lombok or full SDK dependencies.
 * Groups spans by their {@code braintrust.parent} attribute and creates per-parent
 * {@link OtlpHttpSpanExporter} instances.
 */
final class AgentSpanExporter implements SpanExporter {

    private final AgentConfig config;
    private final Map<String, OtlpHttpSpanExporter> exporterCache = new ConcurrentHashMap<>();

    AgentSpanExporter(AgentConfig config) {
        this.config = config;
    }

    @Override
    public CompletableResultCode export(Collection<SpanData> spans) {
        if (spans.isEmpty()) {
            return CompletableResultCode.ofSuccess();
        }

        // Group spans by their braintrust.parent attribute
        var spansByParent = spans.stream().collect(Collectors.groupingBy(this::getParentFromSpan));

        // Export each group with the appropriate x-bt-parent header
        var results =
                spansByParent.entrySet().stream()
                        .map(entry -> exportWithParent(entry.getKey(), entry.getValue()))
                        .toList();

        return CompletableResultCode.ofAll(results);
    }

    private String getParentFromSpan(SpanData span) {
        var parent = span.getAttributes().get(AgentSpanProcessor.PARENT_KEY);
        if (parent != null) {
            return parent;
        }
        return config.getBraintrustParentValue().orElse("");
    }

    private CompletableResultCode exportWithParent(String parent, List<SpanData> spans) {
        try {
            // Evict cache if it grows unexpectedly large (shouldn't happen in normal use)
            if (exporterCache.size() >= 1024) {
                log("Clearing exporter cache (unexpected growth)");
                exporterCache.clear();
            }

            var exporter =
                    exporterCache.computeIfAbsent(
                            parent,
                            p -> {
                                var builder =
                                        OtlpHttpSpanExporter.builder()
                                                .setEndpoint(config.tracesEndpoint())
                                                .addHeader(
                                                        "Authorization",
                                                        "Bearer " + config.apiKey())
                                                .setTimeout(config.requestTimeout());

                                if (!p.isEmpty()) {
                                    builder.addHeader("x-bt-parent", p);
                                    if (config.debug()) {
                                        log("Created exporter with x-bt-parent: " + p);
                                    }
                                }

                                // Set TCCL to BraintrustClassLoader so that ServiceLoader
                                // can find the OkHttp HttpSenderProvider from inst/ entries.
                                return buildWithClassLoader(builder);
                            });

            return exporter.export(spans);
        } catch (Exception e) {
            System.err.println("[braintrust] Failed to export spans: " + e.getMessage());
            if (config.debug()) {
                e.printStackTrace(System.err);
            }
            return CompletableResultCode.ofFailure();
        }
    }

    @Override
    public CompletableResultCode flush() {
        var results = exporterCache.values().stream().map(OtlpHttpSpanExporter::flush).toList();
        return CompletableResultCode.ofAll(results);
    }

    @Override
    public CompletableResultCode shutdown() {
        var results = exporterCache.values().stream().map(OtlpHttpSpanExporter::shutdown).toList();
        exporterCache.clear();
        return CompletableResultCode.ofAll(results);
    }

    /**
     * Builds the OtlpHttpSpanExporter with the thread context classloader set to this class's
     * classloader (BraintrustClassLoader). This is needed because the exporter uses ServiceLoader
     * to find the HttpSenderProvider, and the service files are in inst/META-INF/services/.
     */
    private static OtlpHttpSpanExporter buildWithClassLoader(
            io.opentelemetry.exporter.otlp.http.trace.OtlpHttpSpanExporterBuilder builder) {
        Thread currentThread = Thread.currentThread();
        ClassLoader previousTCCL = currentThread.getContextClassLoader();
        try {
            currentThread.setContextClassLoader(AgentSpanExporter.class.getClassLoader());
            return builder.build();
        } finally {
            currentThread.setContextClassLoader(previousTCCL);
        }
    }

    private static void log(String msg) {
        System.out.println("[braintrust] " + msg);
    }
}
