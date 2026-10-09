package dev.braintrust.trace;

import dev.braintrust.api.BraintrustOpenApiClient;
import dev.braintrust.config.BraintrustConfig;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.trace.SpanContext;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.TraceFlags;
import io.opentelemetry.api.trace.TraceState;
import io.opentelemetry.exporter.otlp.http.trace.OtlpHttpSpanExporter;
import io.opentelemetry.sdk.common.CompletableResultCode;
import io.opentelemetry.sdk.common.InstrumentationLibraryInfo;
import io.opentelemetry.sdk.common.InstrumentationScopeInfo;
import io.opentelemetry.sdk.resources.Resource;
import io.opentelemetry.sdk.trace.data.DelegatingSpanData;
import io.opentelemetry.sdk.trace.data.EventData;
import io.opentelemetry.sdk.trace.data.LinkData;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.data.StatusData;
import io.opentelemetry.sdk.trace.export.SpanExporter;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.UnaryOperator;
import java.util.stream.Collectors;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

/**
 * Custom span exporter for Braintrust that adds the x-bt-parent header dynamically based on span
 * attributes.
 */
@Slf4j
class BraintrustSpanExporter implements SpanExporter {
    private static final AtomicBoolean customizerFailureLogged = new AtomicBoolean();
    private static final AttributeKey<String> CUSTOMIZER_CONTEXT =
            AttributeKey.stringKey("braintrust.context_json");
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
        var exportSpans = spans.stream();
        if (!config.spanCustomizers().isEmpty()) {
            exportSpans = exportSpans.map(this::customizeSpan);
        }
        var spansByParent = exportSpans.collect(Collectors.groupingBy(this::getParentFromSpan));

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
        var parentTraceId = span.getParentSpanContext().getTraceId();
        var startEpochNanos = span.getStartEpochNanos();
        var endEpochNanos = span.getEndEpochNanos();
        var parent = span.getAttributes().get(BraintrustSpanProcessor.PARENT);
        var current = span;
        try {
            for (var customizer : config.spanCustomizers()) {
                var next = customizer.onSpanExport(current);
                // SpanData is immutable: returning the input requires no further work.
                if (next == current) {
                    continue;
                }
                if (next == null) {
                    current = null;
                    break;
                }
                current = new CustomizedSpanData(next);
                if (!traceId.equals(current.getTraceId())
                        || !spanId.equals(current.getSpanId())
                        || !parentSpanId.equals(current.getParentSpanId())
                        || !traceId.equals(current.getSpanContext().getTraceId())
                        || !spanId.equals(current.getSpanContext().getSpanId())
                        || !parentTraceId.equals(current.getParentSpanContext().getTraceId())
                        || !parentSpanId.equals(current.getParentSpanContext().getSpanId())) {
                    current = null;
                    break;
                }
            }
            if (current != null) {
                return current;
            }
        } catch (Exception ignored) {
            // Never log hook exceptions: even their messages and stacks may contain span content.
        }
        if (customizerFailureLogged.compareAndSet(false, true)) {
            log.error(
                    "Span customization failed; exporting only span identity and duration with"
                        + " context.customizer_error=true. Further failures will not be logged.");
        }
        var attributes =
                Attributes.builder().put(CUSTOMIZER_CONTEXT, "{\"customizer_error\":true}");
        if (parent != null) {
            attributes.put(BraintrustSpanProcessor.PARENT, parent);
        }
        return new StrippedSpanData(
                SpanContext.create(
                        traceId, spanId, TraceFlags.getDefault(), TraceState.getDefault()),
                SpanContext.create(
                        parentTraceId,
                        parentSpanId,
                        TraceFlags.getDefault(),
                        TraceState.getDefault()),
                startEpochNanos,
                endEpochNanos,
                attributes.build());
    }

    /** Read replacements inside the failure boundary; downstream code never calls their getters. */
    @Getter
    private static final class CustomizedSpanData implements SpanData {
        private final String traceId;
        private final String spanId;
        private final String parentSpanId;
        private final SpanContext spanContext;
        private final SpanContext parentSpanContext;
        private final String name;
        private final SpanKind kind;
        private final StatusData status;
        private final long startEpochNanos;
        private final long endEpochNanos;
        private final Attributes attributes;
        private final List<EventData> events;
        private final List<LinkData> links;
        private final int totalAttributeCount;
        private final int totalRecordedEvents;
        private final int totalRecordedLinks;
        private final Resource resource;
        private final InstrumentationScopeInfo instrumentationScopeInfo;

        @SuppressWarnings("deprecation")
        private final InstrumentationLibraryInfo instrumentationLibraryInfo;

        private final boolean ended;

        @SuppressWarnings("deprecation")
        private CustomizedSpanData(SpanData span) {
            traceId = span.getTraceId();
            spanId = span.getSpanId();
            parentSpanId = span.getParentSpanId();
            spanContext = Objects.requireNonNull(span.getSpanContext());
            parentSpanContext = Objects.requireNonNull(span.getParentSpanContext());
            name = Objects.requireNonNull(span.getName());
            kind = Objects.requireNonNull(span.getKind());
            var spanStatus = span.getStatus();
            status = StatusData.create(spanStatus.getStatusCode(), spanStatus.getDescription());
            startEpochNanos = span.getStartEpochNanos();
            endEpochNanos = span.getEndEpochNanos();
            attributes = Objects.requireNonNull(span.getAttributes());
            // Materialize nested event/link access too, rather than retaining lazy lists or data.
            var spanEvents = span.getEvents();
            events =
                    spanEvents.isEmpty()
                            ? List.of()
                            : spanEvents.stream()
                                    .map(
                                            event ->
                                                    EventData.create(
                                                            event.getEpochNanos(),
                                                            event.getName(),
                                                            event.getAttributes(),
                                                            event.getTotalAttributeCount()))
                                    .toList();
            var spanLinks = span.getLinks();
            links =
                    spanLinks.isEmpty()
                            ? List.of()
                            : spanLinks.stream()
                                    .map(
                                            link ->
                                                    LinkData.create(
                                                            link.getSpanContext(),
                                                            link.getAttributes(),
                                                            link.getTotalAttributeCount()))
                                    .toList();
            totalAttributeCount = span.getTotalAttributeCount();
            totalRecordedEvents = span.getTotalRecordedEvents();
            totalRecordedLinks = span.getTotalRecordedLinks();
            resource = Objects.requireNonNull(span.getResource());
            instrumentationScopeInfo = Objects.requireNonNull(span.getInstrumentationScopeInfo());
            instrumentationLibraryInfo =
                    Objects.requireNonNull(span.getInstrumentationLibraryInfo());
            ended = span.hasEnded();
        }

        @Override
        public boolean hasEnded() {
            return ended;
        }
    }

    /** Allowlist only: never delegate content access back to the original or customized span. */
    @Getter
    private static final class StrippedSpanData implements SpanData {
        private final SpanContext spanContext;
        private final SpanContext parentSpanContext;
        private final long startEpochNanos;
        private final long endEpochNanos;
        private final Attributes attributes;
        private final int totalAttributeCount;
        private final String name = "";
        private final SpanKind kind = SpanKind.INTERNAL;
        private final StatusData status = StatusData.unset();
        private final List<EventData> events = List.of();
        private final List<LinkData> links = List.of();
        private final int totalRecordedEvents = 0;
        private final int totalRecordedLinks = 0;
        private final Resource resource = Resource.empty();
        private final InstrumentationScopeInfo instrumentationScopeInfo =
                InstrumentationScopeInfo.empty();

        @SuppressWarnings("deprecation")
        private final InstrumentationLibraryInfo instrumentationLibraryInfo =
                InstrumentationLibraryInfo.empty();

        private StrippedSpanData(
                SpanContext spanContext,
                SpanContext parentSpanContext,
                long startEpochNanos,
                long endEpochNanos,
                Attributes attributes) {
            this.spanContext = spanContext;
            this.parentSpanContext = parentSpanContext;
            this.startEpochNanos = startEpochNanos;
            this.endEpochNanos = endEpochNanos;
            this.attributes = attributes;
            this.totalAttributeCount = attributes.size();
        }

        @Override
        public boolean hasEnded() {
            return true;
        }
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
