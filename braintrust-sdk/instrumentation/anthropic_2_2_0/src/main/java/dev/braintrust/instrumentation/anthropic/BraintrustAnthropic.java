package dev.braintrust.instrumentation.anthropic;

import com.anthropic.client.AnthropicClient;
import com.anthropic.client.AnthropicClientAsync;
import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.trace.Tracer;
import java.lang.reflect.Method;
import javax.annotation.Nullable;
import lombok.extern.slf4j.Slf4j;

/**
 * Braintrust Anthropic client instrumentation.
 *
 * <p>Dispatches to the module matching the anthropic-java version of each client: {@code
 * v3_0_0.BraintrustAnthropic} for 3.0.0 and up, {@code v2_2_0.BraintrustAnthropic} otherwise.
 */
@Slf4j
public final class BraintrustAnthropic {

    /**
     * Sentinel class that first appeared in anthropic-java 3.0.0. The {@code
     * AnthropicInstrumentationModule.classLoaderMatcher()}s key on the same class, so the manual
     * path picks the same module the agent would.
     */
    private static final String V3_SENTINEL = "com.anthropic.models.messages.AssistantMessageParam";

    private static final String V3_0_0_WRAPPER =
            "dev.braintrust.instrumentation.anthropic.v3_0_0.BraintrustAnthropic";

    /**
     * Whether the anthropic-java that defined a client class is 3.0.0+. Probed on the client's own
     * classloader (not this class's), so it is correct per client even when several anthropic-java
     * versions are loaded side by side.
     */
    private static final ClassValue<Boolean> IS_V3 =
            new ClassValue<>() {
                @Override
                protected Boolean computeValue(Class<?> clientClass) {
                    try {
                        Class.forName(V3_SENTINEL, false, clientClass.getClassLoader());
                        return true;
                    } catch (ClassNotFoundException | LinkageError e) {
                        return false;
                    }
                }
            };

    /** Instrument Anthropic client with Braintrust traces. */
    public static AnthropicClient wrap(OpenTelemetry openTelemetry, AnthropicClient client) {
        if (isV3(client)) {
            return forwardToV3(V3.WRAP_OTEL_SYNC, openTelemetry, client);
        }
        return dev.braintrust.instrumentation.anthropic.v2_2_0.BraintrustAnthropic.wrap(
                openTelemetry, client);
    }

    /** Instrument an async Anthropic client with Braintrust traces. */
    public static AnthropicClientAsync wrap(
            OpenTelemetry openTelemetry, AnthropicClientAsync client) {
        if (isV3(client)) {
            return forwardToV3(V3.WRAP_OTEL_ASYNC, openTelemetry, client);
        }
        return dev.braintrust.instrumentation.anthropic.v2_2_0.BraintrustAnthropic.wrap(
                openTelemetry, client);
    }

    /**
     * Instruments a client using the given tracer, replacing any tracer it was previously
     * instrumented with.
     */
    public static AnthropicClient wrap(Tracer tracer, AnthropicClient client) {
        if (isV3(client)) {
            return forwardToV3(V3.WRAP_TRACER_SYNC, tracer, client);
        }
        return dev.braintrust.instrumentation.anthropic.v2_2_0.BraintrustAnthropic.wrap(
                tracer, client);
    }

    /** Async counterpart of {@link #wrap(Tracer, AnthropicClient)}. */
    public static AnthropicClientAsync wrap(Tracer tracer, AnthropicClientAsync client) {
        if (isV3(client)) {
            return forwardToV3(V3.WRAP_TRACER_ASYNC, tracer, client);
        }
        return dev.braintrust.instrumentation.anthropic.v2_2_0.BraintrustAnthropic.wrap(
                tracer, client);
    }

    private static boolean isV3(Object client) {
        return IS_V3.get(client.getClass());
    }

    @SuppressWarnings("unchecked")
    private static <C> C forwardToV3(@Nullable Method wrap, Object tracing, C client) {
        if (wrap == null) {
            return client; // already logged when V3 was initialized
        }
        try {
            return (C) wrap.invoke(null, tracing, client);
        } catch (ReflectiveOperationException | LinkageError e) {
            log.error(
                    "failed to forward to {}.wrap; client will not be instrumented",
                    V3_0_0_WRAPPER,
                    e);
            return client;
        }
    }

    /**
     * {@code v3_0_0.BraintrustAnthropic.wrap} overloads, resolved on first use by a 3.x client.
     * Reflection keeps this module from depending on {@code anthropic_3_0_0}, which would put
     * anthropic-java 3.x on its compile classpath. A {@code null} method means the v3 module is
     * unexpectedly missing; v2_2_0 is binary-incompatible with 3.x, so there is no safe fallback.
     */
    private static final class V3 {
        private static final Class<?> WRAPPER = loadWrapper();
        static final Method WRAP_OTEL_SYNC = method(OpenTelemetry.class, AnthropicClient.class);
        static final Method WRAP_OTEL_ASYNC =
                method(OpenTelemetry.class, AnthropicClientAsync.class);
        static final Method WRAP_TRACER_SYNC = method(Tracer.class, AnthropicClient.class);
        static final Method WRAP_TRACER_ASYNC = method(Tracer.class, AnthropicClientAsync.class);

        @Nullable
        private static Class<?> loadWrapper() {
            try {
                return Class.forName(
                        V3_0_0_WRAPPER, true, BraintrustAnthropic.class.getClassLoader());
            } catch (ClassNotFoundException | LinkageError e) {
                log.error(
                        "anthropic-java 3.0.0+ detected but {} is unavailable; clients will not be"
                                + " instrumented",
                        V3_0_0_WRAPPER,
                        e);
                return null;
            }
        }

        @Nullable
        private static Method method(Class<?> tracingType, Class<?> clientType) {
            if (WRAPPER == null) {
                return null;
            }
            try {
                return WRAPPER.getMethod("wrap", tracingType, clientType);
            } catch (NoSuchMethodException | LinkageError e) {
                log.error(
                        "{}.wrap({}, {}) is unavailable; such clients will not be instrumented",
                        V3_0_0_WRAPPER,
                        tracingType.getSimpleName(),
                        clientType.getSimpleName(),
                        e);
                return null;
            }
        }
    }
}
