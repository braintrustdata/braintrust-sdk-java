package dev.braintrust.trace;

import dev.braintrust.api.BraintrustOpenApiClient;
import dev.braintrust.config.BraintrustConfig;
import dev.braintrust.config.SpanOriginEnvironment;
import dev.braintrust.json.BraintrustJsonMapper;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.context.Context;
import io.opentelemetry.sdk.common.CompletableResultCode;
import io.opentelemetry.sdk.common.InstrumentationLibraryInfo;
import io.opentelemetry.sdk.trace.ReadWriteSpan;
import io.opentelemetry.sdk.trace.ReadableSpan;
import io.opentelemetry.sdk.trace.SpanProcessor;
import io.opentelemetry.sdk.trace.data.DelegatingSpanData;
import io.opentelemetry.sdk.trace.data.SpanData;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import lombok.extern.slf4j.Slf4j;

/**
 * Custom span processor that enriches spans with Braintrust-specific attributes. Supports parent
 * assignment to projects or experiments.
 */
@Slf4j
public class BraintrustSpanProcessor implements SpanProcessor {
    static final AttributeKey<String> INPUT_JSON = AttributeKey.stringKey("braintrust.input_json");
    static final AttributeKey<String> OUTPUT_JSON =
            AttributeKey.stringKey("braintrust.output_json");

    public static final AttributeKey<String> PARENT =
            AttributeKey.stringKey(BraintrustTracing.PARENT_KEY);
    static final AttributeKey<String> CONTEXT_JSON =
            AttributeKey.stringKey("braintrust.context_json");

    private final BraintrustConfig config;
    private final SpanProcessor delegate;
    private final List<BraintrustSampler> samplers;
    private final ConcurrentMap<String, ParentContext> parentContexts = new ConcurrentHashMap<>();
    private final AttachmentProcessor attachmentProcessor;

    BraintrustSpanProcessor(BraintrustConfig config, SpanProcessor delegate) {
        this.config = config;
        this.delegate = delegate;
        this.samplers = buildSamplers(config);
        this.attachmentProcessor =
                new AttachmentProcessor(
                        config,
                        new AttachmentUploader.S3AttachmentUploader(
                                BraintrustOpenApiClient.of(config)));
    }

    private static List<BraintrustSampler> buildSamplers(BraintrustConfig config) {
        var samplers = new java.util.ArrayList<BraintrustSampler>();
        if (config.filterAISpans()) {
            samplers.add(new BraintrustSampler.FilterAISpans());
        }
        return List.copyOf(samplers);
    }

    @Override
    public void onStart(@Nonnull Context parentContext, ReadWriteSpan span) {
        log.debug("OnStart: span={}, parent={}", span.getName(), parentContext);

        // Check if span already has a parent attribute
        if (span.getAttribute(PARENT) == null) {
            // Check if parent context has Braintrust attributes first
            var btContext = BraintrustContext.fromContext(parentContext);
            if (btContext == null) {
                // Check baggage for distributed tracing (cross-process parent propagation)
                var parentFromBaggage = BraintrustContext.getParentFromBaggage(parentContext);
                if (parentFromBaggage.isPresent()) {
                    span.setAttribute(PARENT, parentFromBaggage.get());
                    log.debug(
                            "OnStart: set parent {} from baggage for span {}",
                            parentFromBaggage.get(),
                            span.getName());
                } else {
                    // Get parent from the config if otel doesn't have it
                    config.getBraintrustParentValue()
                            .ifPresent(
                                    parentValue -> {
                                        span.setAttribute(PARENT, parentValue);
                                        log.debug(
                                                "OnStart: set parent {} for span {}",
                                                parentValue,
                                                span.getName());
                                    });
                }
            } else {
                btContext
                        .projectId()
                        .ifPresent(
                                id -> {
                                    span.setAttribute(PARENT, "project_id:" + id);
                                    log.debug("OnStart: set parent project {} from context", id);
                                });
                btContext
                        .experimentId()
                        .ifPresent(
                                id -> {
                                    span.setAttribute(PARENT, "experiment_id:" + id);
                                    log.debug("OnStart: set parent experiment {} from context", id);
                                });
            }
        }

        delegate.onStart(parentContext, span);
    }

    @SuppressWarnings("unchecked")
    private static String mergedContextJson(
            @Nullable String existingContextJson, @Nullable SpanOriginEnvironment environment) {
        Map<String, Object> context = new HashMap<>();
        if (existingContextJson != null && !existingContextJson.isBlank()) {
            try {
                context.putAll(
                        BraintrustJsonMapper.get().readValue(existingContextJson, Map.class));
            } catch (Exception ignored) {
                // Invalid user-provided context should not prevent adding provenance.
            }
        }

        var existingOrigin = context.get("span_origin");
        Map<String, Object> spanOrigin =
                existingOrigin instanceof Map<?, ?> map
                        ? new HashMap<>((Map<String, Object>) map)
                        : new HashMap<>();
        spanOrigin.putIfAbsent("name", "braintrust.sdk.java");
        spanOrigin.putIfAbsent("version", BraintrustTracing.INSTRUMENTATION_VERSION);
        spanOrigin.putIfAbsent(
                "instrumentation", Map.of("name", BraintrustTracing.INSTRUMENTATION_NAME));
        if (!spanOrigin.containsKey("environment") && environment != null) {
            var env = new HashMap<String, Object>();
            env.put("type", environment.type());
            if (environment.name() != null && !environment.name().isBlank()) {
                env.put("name", environment.name());
            }
            spanOrigin.put("environment", env);
        }
        context.put("span_origin", spanOrigin);
        return BraintrustJsonMapper.toJson(context);
    }

    @Override
    public boolean isStartRequired() {
        return true;
    }

    @Override
    public void onEnd(ReadableSpan span) {
        if (config.debug()) {
            logSpanDetails(span);
        }
        for (var sampler : samplers) {
            if (!sampler.sample(span)) {
                log.debug(
                        "Span filtered by {}: {}",
                        sampler.getClass().getSimpleName(),
                        span.getName());
                return;
            }
        }

        var spanData = span.toSpanData();
        @Nullable String inputJson = spanData.getAttributes().get(INPUT_JSON);
        @Nullable String outputJson = spanData.getAttributes().get(OUTPUT_JSON);

        @Nullable String newInputJson = attachmentProcessor.processAndUpload(inputJson);
        @Nullable String newOutputJson = attachmentProcessor.processAndUpload(outputJson);
        var spanOriginEnvironment = config.spanOriginEnvironment().orElse(null);
        var newContextJson =
                mergedContextJson(
                        spanData.getAttributes().get(CONTEXT_JSON), spanOriginEnvironment);

        if (!Objects.equals(newInputJson, inputJson)
                || !Objects.equals(newOutputJson, outputJson)) {
            delegate.onEnd(
                    new TransformedReadableSpan(
                            span,
                            newInputJson,
                            newOutputJson,
                            newContextJson,
                            spanOriginEnvironment));
        } else {
            delegate.onEnd(
                    new TransformedReadableSpan(
                            span, inputJson, outputJson, newContextJson, spanOriginEnvironment));
        }
    }

    @Override
    public boolean isEndRequired() {
        return true;
    }

    @Override
    public CompletableResultCode shutdown() {
        return delegate.shutdown();
    }

    @Override
    public CompletableResultCode forceFlush() {
        return delegate.forceFlush();
    }

    /** Sets the parent context for a specific trace ID. */
    public void setParentContext(String traceId, ParentContext context) {
        parentContexts.put(traceId, context);
    }

    /** Gets the parent context for a specific trace ID. */
    public Optional<ParentContext> getParentContext(String traceId) {
        return Optional.ofNullable(parentContexts.get(traceId));
    }

    private void logSpanDetails(ReadableSpan span) {
        var spanData = span.toSpanData();
        log.debug(
                "Span completed: name={}, traceId={}, spanId={}, duration={}ms, attributes={},"
                        + " events={}",
                spanData.getName(),
                spanData.getTraceId(),
                spanData.getSpanId(),
                (spanData.getEndEpochNanos() - spanData.getStartEpochNanos()) / 1_000_000,
                spanData.getAttributes(),
                spanData.getEvents());
    }

    /** Parent context for spans (project or experiment). */
    public record ParentContext(
            @Nullable String projectId, @Nullable String experimentId, ParentType type) {
        public enum ParentType {
            PROJECT,
            EXPERIMENT
        }

        public static ParentContext project(String projectId) {
            return new ParentContext(projectId, null, ParentType.PROJECT);
        }

        public static ParentContext experiment(String experimentId) {
            return new ParentContext(null, experimentId, ParentType.EXPERIMENT);
        }
    }

    /**
     * otel java does not implement onEnding, so this is the most idiomatic way to mutate a span
     * once it ends
     */
    private static class TransformedReadableSpan implements ReadableSpan {
        private final ReadableSpan delegate;
        private final Attributes attributes;

        TransformedReadableSpan(
                ReadableSpan delegate,
                String inputJson,
                String outputJson,
                String contextJson,
                @Nullable SpanOriginEnvironment environment) {
            this.delegate = delegate;
            var builder = delegate.getAttributes().toBuilder();
            if (inputJson != null) {
                builder.put(INPUT_JSON, inputJson);
            }
            if (outputJson != null) {
                builder.put(OUTPUT_JSON, outputJson);
            }
            builder.put(CONTEXT_JSON, contextJson);
            attributes = builder.build();
        }

        @Override
        public Attributes getAttributes() {
            return attributes;
        }

        @Override
        @SuppressWarnings("unchecked")
        public <T> T getAttribute(AttributeKey<T> key) {
            if (key.equals(INPUT_JSON)) {
                return (T) attributes.get(INPUT_JSON);
            }
            if (key.equals(OUTPUT_JSON)) {
                return (T) attributes.get(OUTPUT_JSON);
            }
            if (key.equals(CONTEXT_JSON)) {
                return (T) attributes.get(CONTEXT_JSON);
            }
            return delegate.getAttribute(key);
        }

        @Override
        public SpanData toSpanData() {
            return new DelegatingSpanData(delegate.toSpanData()) {
                @Override
                public io.opentelemetry.api.common.Attributes getAttributes() {
                    return TransformedReadableSpan.this.getAttributes();
                }

                @Override
                public int getTotalAttributeCount() {
                    return getAttributes().size();
                }
            };
        }

        @Override
        public String getName() {
            return delegate.getName();
        }

        @Override
        public io.opentelemetry.api.trace.SpanContext getSpanContext() {
            return delegate.getSpanContext();
        }

        @Override
        public boolean hasEnded() {
            return delegate.hasEnded();
        }

        @Override
        public io.opentelemetry.sdk.common.InstrumentationScopeInfo getInstrumentationScopeInfo() {
            return delegate.getInstrumentationScopeInfo();
        }

        @Override
        public InstrumentationLibraryInfo getInstrumentationLibraryInfo() {
            return delegate.getInstrumentationLibraryInfo();
        }

        @Override
        public long getLatencyNanos() {
            return delegate.getLatencyNanos();
        }

        @Override
        public io.opentelemetry.api.trace.SpanContext getParentSpanContext() {
            return delegate.getParentSpanContext();
        }

        @Override
        public io.opentelemetry.api.trace.SpanKind getKind() {
            return delegate.getKind();
        }
    }
}
