package dev.braintrust.trace;

import dev.braintrust.config.BraintrustConfig;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.context.Context;
import io.opentelemetry.sdk.common.CompletableResultCode;
import io.opentelemetry.sdk.trace.ReadWriteSpan;
import io.opentelemetry.sdk.trace.ReadableSpan;
import io.opentelemetry.sdk.trace.SpanProcessor;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import javax.annotation.Nullable;
import lombok.extern.slf4j.Slf4j;
import org.jetbrains.annotations.NotNull;

/**
 * Custom span processor that enriches spans with Braintrust-specific attributes. Supports parent
 * assignment to projects or experiments.
 */
@Slf4j
class BraintrustSpanProcessor implements SpanProcessor {
    // Braintrust-specific attributes
    public static final AttributeKey<String> PARENT =
            AttributeKey.stringKey(BraintrustTracing.PARENT_KEY);

    private final BraintrustConfig config;
    private final SpanProcessor delegate;
    private final ConcurrentMap<String, ParentContext> parentContexts = new ConcurrentHashMap<>();

    public BraintrustSpanProcessor(BraintrustConfig config, SpanProcessor delegate) {
        this.config = config;
        this.delegate = delegate;
    }

    @Override
    public void onStart(@NotNull Context parentContext, ReadWriteSpan span) {
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

    @Override
    public boolean isStartRequired() {
        return true;
    }

    @Override
    public void onEnd(ReadableSpan span) {
        if (config.debug()) {
            logSpanDetails(span);
        }
        delegate.onEnd(span);
    }

    @Override
    public boolean isEndRequired() {
        return delegate.isEndRequired();
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
}
