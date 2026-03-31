package dev.braintrust.agent.dd;

import datadog.trace.api.DDTraceId;
import datadog.trace.api.interceptor.MutableSpan;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.common.AttributesBuilder;
import io.opentelemetry.api.trace.*;
import io.opentelemetry.context.Context;
import io.opentelemetry.sdk.common.InstrumentationScopeInfo;
import io.opentelemetry.sdk.resources.Resource;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.data.StatusData;
import java.lang.reflect.Method;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import lombok.extern.slf4j.Slf4j;

/**
 * Utility for converting Datadog {@link MutableSpan}s to OTel {@link SpanData} and replaying them
 * on an OTel {@link Tracer}.
 */
@Slf4j
public class DDSpanConverter {
    private static final AtomicReference<Method> contextMethod = new AtomicReference<>();
    private static final AtomicReference<Method> getTraceIdMethod = new AtomicReference<>();
    private static final AtomicReference<Method> getSpanIdMethod = new AtomicReference<>();
    private static final AtomicBoolean successfulInit = new AtomicBoolean(false);
    private static final Map<Class<?>, Optional<Method>> getParentIdMethods =
            Collections.synchronizedMap(new WeakHashMap<>());

    static {
        initialize();
    }

    /**
     * Initialize reflection handles for extracting IDs from DD spans. Must be called before {@link
     * #convertTrace} or {@link #replayTrace}.
     */
    static synchronized boolean initialize() {
        if (successfulInit.get()) {
            return true;
        }
        try {
            Class<?> agentSpanClass =
                    Class.forName(
                            "datadog.trace.bootstrap.instrumentation.api.AgentSpan", true, null);
            contextMethod.set(agentSpanClass.getMethod("context"));

            Class<?> agentSpanContextClass =
                    Class.forName(
                            "datadog.trace.bootstrap.instrumentation.api.AgentSpanContext",
                            true,
                            null);
            getTraceIdMethod.set(agentSpanContextClass.getMethod("getTraceId"));
            getSpanIdMethod.set(agentSpanContextClass.getMethod("getSpanId"));

            log.debug("DD span converter reflection initialized successfully.");
            successfulInit.set(true);
            return true;
        } catch (Exception e) {
            log.error(
                    "Failed to initialize DD span converter reflection — span conversion will not"
                            + " work",
                    e);
            return false;
        }
    }

    /** Converts a list of DD {@link MutableSpan}s (one trace) to OTel {@link SpanData}. */
    static List<SpanData> convertTrace(List<MutableSpan> mutableSpans) throws Exception {
        if (!successfulInit.get()) {
            log.debug("Reflection not initialized — cannot convert DD spans");
            return Collections.emptyList();
        }

        List<SpanData> result = new ArrayList<>(mutableSpans.size());
        for (MutableSpan ddSpan : mutableSpans) {
            try {
                result.add(convertSpan(ddSpan));
            } catch (Exception e) {
                log.warn(
                        "Failed to convert DD span '{}': {}",
                        ddSpan.getResourceName(),
                        e.getMessage());
            }
        }
        return result;
    }

    /**
     * Replays a list of already-converted {@link SpanData} on the given OTel {@link Tracer}.
     *
     * <p>Spans are topologically sorted (parents before children) so that parent contexts are
     * available when starting child spans. Each span is started and immediately ended with the
     * original timestamps, attributes, status, and kind preserved.
     */
    static void replayTrace(Tracer tracer, List<SpanData> spans) {
        if (spans == null || spans.isEmpty()) {
            return;
        }

        // Sort spans so parents come before children (topological order by parent relationship).
        List<SpanData> sorted = topologicalSort(spans);

        // Map from spanId -> OTel Context containing that span, so children can link to parents.
        Map<String, Context> spanContextMap = new HashMap<>();

        for (SpanData sd : sorted) {
            SpanBuilder builder =
                    tracer.spanBuilder(sd.getName())
                            .setSpanKind(sd.getKind())
                            .setStartTimestamp(sd.getStartEpochNanos(), TimeUnit.NANOSECONDS);

            // Set all attributes
            sd.getAttributes().forEach((key, value) -> setAttributeUnchecked(builder, key, value));

            // Link to parent context if available
            String parentSpanId = sd.getParentSpanContext().getSpanId();
            if (sd.getParentSpanContext().isValid()) {
                Context parentCtx = spanContextMap.get(parentSpanId);
                if (parentCtx != null) {
                    builder.setParent(parentCtx);
                } else {
                    // Parent not in this batch — create a remote parent context
                    builder.setParent(Context.current().with(Span.wrap(sd.getParentSpanContext())));
                }
            } else {
                builder.setNoParent();
            }

            // Set the original IDs so the OverridableIdGenerator returns them
            // instead of generating new random ones.
            // For root spans: override both traceId and spanId.
            // For child spans: only spanId (the SDK inherits traceId from parent context).
            if (!sd.getParentSpanContext().isValid()) {
                OverridableIdGenerator.setNextIds(
                        sd.getSpanContext().getTraceId(), sd.getSpanContext().getSpanId());
            } else {
                OverridableIdGenerator.setNextIds(null, sd.getSpanContext().getSpanId());
            }

            Span span;
            try {
                span = builder.startSpan();
            } finally {
                OverridableIdGenerator.clear();
            }

            // Set status
            if (sd.getStatus().getStatusCode() == StatusCode.ERROR) {
                span.setStatus(StatusCode.ERROR, sd.getStatus().getDescription());
            } else if (sd.getStatus().getStatusCode() == StatusCode.OK) {
                span.setStatus(StatusCode.OK, sd.getStatus().getDescription());
            }

            // Store the context for potential children
            Context ctx = Context.current().with(span);
            spanContextMap.put(sd.getSpanContext().getSpanId(), ctx);

            // End with original end timestamp
            span.end(sd.getEndEpochNanos(), TimeUnit.NANOSECONDS);
        }
    }

    @SuppressWarnings("unchecked")
    private static <T> void setAttributeUnchecked(
            SpanBuilder builder, io.opentelemetry.api.common.AttributeKey<?> key, Object value) {
        builder.setAttribute((io.opentelemetry.api.common.AttributeKey<T>) key, (T) value);
    }

    /**
     * Topologically sorts spans so that parent spans appear before their children. Spans without
     * parents in the list come first.
     */
    private static List<SpanData> topologicalSort(List<SpanData> spans) {
        // Build adjacency: parentSpanId -> list of children
        Map<String, List<SpanData>> childrenOf = new HashMap<>();
        Set<String> spanIds = new HashSet<>();

        for (SpanData sd : spans) {
            String id = sd.getSpanContext().getSpanId();
            spanIds.add(id);
        }

        List<SpanData> roots = new ArrayList<>();
        for (SpanData sd : spans) {
            String parentId = sd.getParentSpanContext().getSpanId();
            if (!sd.getParentSpanContext().isValid() || !spanIds.contains(parentId)) {
                roots.add(sd);
            } else {
                childrenOf.computeIfAbsent(parentId, k -> new ArrayList<>()).add(sd);
            }
        }

        // BFS from roots
        List<SpanData> sorted = new ArrayList<>(spans.size());
        Deque<SpanData> queue = new ArrayDeque<>(roots);
        while (!queue.isEmpty()) {
            SpanData sd = queue.poll();
            sorted.add(sd);
            List<SpanData> children = childrenOf.get(sd.getSpanContext().getSpanId());
            if (children != null) {
                queue.addAll(children);
            }
        }

        // If there were cycles or disconnected spans, append any remaining
        if (sorted.size() < spans.size()) {
            Set<String> added = new HashSet<>();
            for (SpanData sd : sorted) {
                added.add(sd.getSpanContext().getSpanId());
            }
            for (SpanData sd : spans) {
                if (!added.contains(sd.getSpanContext().getSpanId())) {
                    sorted.add(sd);
                }
            }
        }

        return sorted;
    }

    private static SpanData convertSpan(MutableSpan ddSpan) throws Exception {
        // Extract trace ID and span ID via reflection
        Object agentSpanContext = contextMethod.get().invoke(ddSpan);
        DDTraceId ddTraceId = (DDTraceId) getTraceIdMethod.get().invoke(agentSpanContext);
        long ddSpanId = (long) getSpanIdMethod.get().invoke(agentSpanContext);

        // Find parent span ID via reflection
        long reflectedParentId = 0;
        try {
            Optional<Method> getParentIdMethod = getParentIdMethod(agentSpanContext.getClass());
            if (getParentIdMethod.isPresent()) {
                reflectedParentId = (long) getParentIdMethod.get().invoke(agentSpanContext);
            }
        } catch (Exception e) {
            log.debug("Cannot determine parent span ID for '{}'", ddSpan.getResourceName());
        }

        return convertSpan(ddSpan, ddTraceId, ddSpanId, reflectedParentId);
    }

    /**
     * Converts a DD span to OTel {@link SpanData} using pre-extracted IDs. This overload is
     * testable without the DD bootstrap classloader since it does not use reflection.
     *
     * @param ddSpan the DD span to convert
     * @param ddTraceId the DD trace ID (already extracted)
     * @param ddSpanId the DD span ID as a long (already extracted)
     * @param parentId the parent span ID as a long, or 0 if this is a root span
     */
    static SpanData convertSpan(
            MutableSpan ddSpan, DDTraceId ddTraceId, long ddSpanId, long parentId) {
        // Convert DD trace ID (DDTraceId) to OTel 32-hex-char trace ID
        String traceIdHex = ddTraceId.toHexStringPadded(32);

        // Convert DD span ID (long) to OTel 16-hex-char span ID
        String spanIdHex = String.format("%016x", ddSpanId);

        // Convert parent span ID
        String parentSpanIdHex = SpanContext.getInvalid().getSpanId();
        if (parentId != 0) {
            parentSpanIdHex = String.format("%016x", parentId);
        }

        SpanContext spanContext =
                SpanContext.create(
                        traceIdHex, spanIdHex, TraceFlags.getSampled(), TraceState.getDefault());

        SpanContext parentSpanContext;
        if (!parentSpanIdHex.equals(SpanContext.getInvalid().getSpanId())) {
            parentSpanContext =
                    SpanContext.create(
                            traceIdHex,
                            parentSpanIdHex,
                            TraceFlags.getSampled(),
                            TraceState.getDefault());
        } else {
            parentSpanContext = SpanContext.getInvalid();
        }

        Attributes attributes = convertTags(ddSpan.getTags());
        SpanKind spanKind = inferSpanKind(ddSpan);

        StatusData status =
                ddSpan.isError() ? StatusData.create(StatusCode.ERROR, "") : StatusData.unset();

        long startEpochNanos = ddSpan.getStartTime();
        long endEpochNanos = startEpochNanos + ddSpan.getDurationNano();

        return new ImmutableSpanData(
                spanContext,
                parentSpanContext,
                Resource.getDefault(),
                InstrumentationScopeInfo.create("braintrust-dd"),
                String.valueOf(ddSpan.getResourceName()),
                spanKind,
                startEpochNanos,
                endEpochNanos,
                attributes,
                status);
    }

    private static Optional<Method> getParentIdMethod(Class<?> agentSpanContextClass) {
        synchronized (getParentIdMethods) {
            Optional<Method> cached = getParentIdMethods.get(agentSpanContextClass);
            if (cached != null) {
                return cached;
            }

            Optional<Method> resolved;
            try {
                resolved = Optional.of(agentSpanContextClass.getMethod("getParentId"));
            } catch (NoSuchMethodException e) {
                resolved = Optional.empty();
            }
            getParentIdMethods.put(agentSpanContextClass, resolved);
            return resolved;
        }
    }

    /** DD internal span tags that should not be forwarded as OTel attributes. */
    private static final Set<String> DROPPED_DD_TAGS =
            Set.of(
                    "_dd.agent_psr",
                    "_dd.profiling.enabled",
                    "_dd.trace_span_attribute_schema",
                    "_sample_rate");

    private static Attributes convertTags(Map<String, Object> tags) {
        if (tags == null || tags.isEmpty()) {
            return Attributes.empty();
        }
        AttributesBuilder builder = Attributes.builder();
        for (var entry : tags.entrySet()) {
            String key = entry.getKey();
            if (DROPPED_DD_TAGS.contains(key)) {
                continue;
            }
            Object value = entry.getValue();
            if (value instanceof String s) {
                builder.put(key, s);
            } else if (value instanceof Long l) {
                builder.put(key, l);
            } else if (value instanceof Integer i) {
                builder.put(key, (long) i);
            } else if (value instanceof Double d) {
                builder.put(key, d);
            } else if (value instanceof Float f) {
                builder.put(key, (double) f);
            } else if (value instanceof Boolean b) {
                builder.put(key, b);
            } else if (value != null) {
                builder.put(key, value.toString());
            }
        }
        return builder.build();
    }

    /** Infers OTel SpanKind from DD's operation name convention. */
    private static SpanKind inferSpanKind(MutableSpan ddSpan) {
        CharSequence opName = ddSpan.getOperationName();
        if (opName == null) return SpanKind.INTERNAL;
        String op = opName.toString();
        return switch (op) {
            case "server.request", "server" -> SpanKind.SERVER;
            case "client.request", "client" -> SpanKind.CLIENT;
            case "producer" -> SpanKind.PRODUCER;
            case "consumer" -> SpanKind.CONSUMER;
            default -> SpanKind.INTERNAL;
        };
    }
}
