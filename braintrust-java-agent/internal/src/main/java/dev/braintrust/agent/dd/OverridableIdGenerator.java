package dev.braintrust.agent.dd;

import io.opentelemetry.sdk.trace.IdGenerator;

/**
 * An {@link IdGenerator} that supports thread-local overrides for trace and span IDs. When an
 * override is set, the next call to {@link #generateTraceId()} or {@link #generateSpanId()} returns
 * the override and clears it. When no override is set, delegates to {@link IdGenerator#random()}.
 *
 * <p>This is used by {@link DDSpanConverter#replayTrace} to preserve the original DD span/trace IDs
 * when replaying converted spans through the OTel SDK pipeline.
 */
class OverridableIdGenerator implements IdGenerator {

    public static final OverridableIdGenerator INSTANCE = new OverridableIdGenerator();

    private static final IdGenerator RANDOM = IdGenerator.random();

    private static final ThreadLocal<String> nextTraceId = new ThreadLocal<>();
    private static final ThreadLocal<String> nextSpanId = new ThreadLocal<>();

    private OverridableIdGenerator() {}

    /**
     * Sets the trace and span IDs to return from the next {@link #generateTraceId()} and {@link
     * #generateSpanId()} calls on the current thread. Each value is consumed (cleared) on use.
     */
    public static void setNextIds(String traceId, String spanId) {
        nextTraceId.set(traceId);
        nextSpanId.set(spanId);
    }

    /** Clears any pending overrides on the current thread. */
    public static void clear() {
        nextTraceId.remove();
        nextSpanId.remove();
    }

    @Override
    public String generateTraceId() {
        String override = nextTraceId.get();
        if (override != null) {
            nextTraceId.remove();
            return override;
        }
        return RANDOM.generateTraceId();
    }

    @Override
    public String generateSpanId() {
        String override = nextSpanId.get();
        if (override != null) {
            nextSpanId.remove();
            return override;
        }
        return RANDOM.generateSpanId();
    }
}
