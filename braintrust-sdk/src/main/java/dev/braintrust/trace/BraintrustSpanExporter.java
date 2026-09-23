package dev.braintrust.trace;

import dev.braintrust.api.BraintrustOpenApiClient;
import dev.braintrust.config.BraintrustConfig;
import io.opentelemetry.exporter.otlp.http.trace.OtlpHttpSpanExporter;
import io.opentelemetry.sdk.common.CompletableResultCode;
import io.opentelemetry.sdk.trace.data.DelegatingSpanData;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.export.SpanExporter;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.UnaryOperator;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;

/**
 * Custom span exporter for Braintrust that adds the x-bt-parent header dynamically based on span
 * attributes.
 */
@Slf4j
class BraintrustSpanExporter implements SpanExporter {
    private final BraintrustConfig config;
    private final String tracesEndpoint;
    private final Map<String, SpanExporter> exporterCache = new ConcurrentHashMap<>();
    private final UnaryOperator<SpanExporter> transportDecorator;
    private final AttachmentUploader attachmentUploader;
    private final AttachmentProcessor attachmentProcessor;

    public BraintrustSpanExporter(BraintrustConfig config) {
        this(config, UnaryOperator.identity());
    }

    BraintrustSpanExporter(
            BraintrustConfig config, UnaryOperator<SpanExporter> transportDecorator) {
        this.config = config;
        this.tracesEndpoint = config.apiUrl() + config.tracesPath();
        this.transportDecorator = transportDecorator;
        this.attachmentUploader =
                new AttachmentUploader.S3AttachmentUploader(
                        BraintrustOpenApiClient.of(config), config);
        this.attachmentProcessor = new AttachmentProcessor(config, attachmentUploader);
    }

    @Override
    public CompletableResultCode export(Collection<SpanData> spans) {
        if (spans.isEmpty()) {
            return CompletableResultCode.ofSuccess();
        }

        // Finish customization for the entire batch before any attachment or span upload.
        Map<String, List<SpanData>> spansByParent;
        try {
            var exportSpans = spans.stream();
            if (!config.spanCustomizers().isEmpty()) {
                exportSpans = exportSpans.map(this::customizeSpan);
            }
            spansByParent = exportSpans.collect(Collectors.groupingBy(this::getParentFromSpan));
        } catch (Exception e) {
            log.error("Failed to customize spans for export", e);
            return CompletableResultCode.ofFailure();
        }

        // Export each group with the appropriate x-bt-parent header
        var results =
                spansByParent.entrySet().stream()
                        .map(
                                entry ->
                                        exportWithParent(
                                                entry.getKey(),
                                                entry.getValue().stream()
                                                        .map(this::convertAttachments)
                                                        .toList()))
                        .toList();

        // Combine all results
        var combined = CompletableResultCode.ofAll(results);
        log.debug("span export results: {}", combined.isSuccess());

        return combined;
    }

    private SpanData customizeSpan(SpanData span) {
        var traceId = span.getTraceId();
        var spanId = span.getSpanId();
        var parentSpanId = span.getParentSpanId();
        var current = span;
        for (var customizer : config.spanCustomizers()) {
            current = customizer.onSpanExport(current);
            if (current == null) {
                throw new IllegalStateException("SpanCustomizer.onSpanExport must not return null");
            }
            if (!traceId.equals(current.getTraceId())
                    || !spanId.equals(current.getSpanId())
                    || !parentSpanId.equals(current.getParentSpanId())
                    || !traceId.equals(current.getSpanContext().getTraceId())
                    || !spanId.equals(current.getSpanContext().getSpanId())
                    || !parentSpanId.equals(current.getParentSpanContext().getSpanId())) {
                throw new IllegalStateException(
                        "SpanCustomizer.onSpanExport must not change trace ID, span ID, or parent"
                                + " span ID");
            }
        }
        return current;
    }

    private SpanData convertAttachments(SpanData span) {
        var attributes = span.getAttributes();
        var input = attributes.get(BraintrustSpanProcessor.INPUT_JSON);
        var output = attributes.get(BraintrustSpanProcessor.OUTPUT_JSON);
        var convertedInput = attachmentProcessor.processAndUpload(input);
        var convertedOutput = attachmentProcessor.processAndUpload(output);
        if (Objects.equals(input, convertedInput) && Objects.equals(output, convertedOutput)) {
            return span;
        }
        var builder = attributes.toBuilder();
        if (convertedInput != null) {
            builder.put(BraintrustSpanProcessor.INPUT_JSON, convertedInput);
        }
        if (convertedOutput != null) {
            builder.put(BraintrustSpanProcessor.OUTPUT_JSON, convertedOutput);
        }
        var convertedAttributes = builder.build();
        return new DelegatingSpanData(span) {
            @Override
            public io.opentelemetry.api.common.Attributes getAttributes() {
                return convertedAttributes;
            }
        };
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
                                                .setSslContext(
                                                        config.sslContext(),
                                                        config.x509TrustManager())
                                                .addHeader(
                                                        "Authorization",
                                                        "Bearer " + config.apiKey())
                                                .setTimeout(config.requestTimeout());
                                if (config.compressOtelPayload()) {
                                    exporterBuilder.setCompression("gzip");
                                }
                                // Add x-bt-parent header if we have a parent
                                if (!p.isEmpty()) {
                                    exporterBuilder.addHeader("x-bt-parent", p);
                                    log.debug("Created exporter with x-bt-parent: {}", p);
                                }

                                return transportDecorator.apply(exporterBuilder.build());
                            });

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
        } catch (Exception e) {
            log.error("Failed to export spans", e);
            return CompletableResultCode.ofFailure();
        }
    }

    @Override
    public CompletableResultCode flush() {
        // Flush all cached exporters
        var results = exporterCache.values().stream().map(SpanExporter::flush).toList();
        return CompletableResultCode.ofAll(results);
    }

    @Override
    public CompletableResultCode shutdown() {
        // Shutdown all cached exporters
        var results = exporterCache.values().stream().map(SpanExporter::shutdown).toList();
        exporterCache.clear();
        // The batch processor has drained its spans before shutting down this exporter.
        // Finish their uploads while the attachment backend is still available.
        attachmentUploader.shutdown();
        return CompletableResultCode.ofAll(results);
    }
}
