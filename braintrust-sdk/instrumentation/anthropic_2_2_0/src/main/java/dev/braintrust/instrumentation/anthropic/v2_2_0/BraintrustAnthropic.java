package dev.braintrust.instrumentation.anthropic.v2_2_0;

import com.anthropic.client.AnthropicClient;
import com.anthropic.client.AnthropicClientAsync;
import com.anthropic.core.ClientOptions;
import com.anthropic.core.http.HttpClient;
import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.trace.Tracer;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.function.Consumer;
import lombok.extern.slf4j.Slf4j;

/** Braintrust Anthropic client instrumentation. */
@Slf4j
public final class BraintrustAnthropic {
    static final String INSTRUMENTATION_NAME = "anthropic";
    static final String INSTRUMENTATION_VERSION = "2.2.0";

    /** Instrument Anthropic client with Braintrust traces. */
    public static AnthropicClient wrap(OpenTelemetry openTelemetry, AnthropicClient client) {
        if (!instrument(
                openTelemetry.getTracer(INSTRUMENTATION_NAME, INSTRUMENTATION_VERSION),
                client,
                false)) {
            return client;
        }
        return ContextCapturingProxy.wrap(client, AnthropicClient.class);
    }

    /** Instrument an async Anthropic client with Braintrust traces. */
    public static AnthropicClientAsync wrap(
            OpenTelemetry openTelemetry, AnthropicClientAsync client) {
        if (!instrument(
                openTelemetry.getTracer(INSTRUMENTATION_NAME, INSTRUMENTATION_VERSION),
                client,
                false)) {
            return client;
        }
        return ContextCapturingProxy.wrap(client, AnthropicClientAsync.class);
    }

    /**
     * Instruments a client using the owning library's tracer, replacing any existing provider
     * tracer without adding another tracing layer.
     */
    public static AnthropicClient wrap(Tracer tracer, AnthropicClient client) {
        if (!instrument(tracer, client, true)) {
            return client;
        }
        return ContextCapturingProxy.wrap(client, AnthropicClient.class);
    }

    /** Async counterpart of {@link #wrap(Tracer, AnthropicClient)}. */
    public static AnthropicClientAsync wrap(Tracer tracer, AnthropicClientAsync client) {
        if (!instrument(tracer, client, true)) {
            return client;
        }
        return ContextCapturingProxy.wrap(client, AnthropicClientAsync.class);
    }

    /**
     * Swaps the client's {@code ClientOptions.httpClient} for a {@link TracingHttpClient} in place;
     * wrapping is idempotent.
     *
     * @return whether the HTTP layer is instrumented. When false (custom client implementations or
     *     changed SDK internals), the caller must NOT install {@link ContextCapturingProxy}: the
     *     proxy's internal context header is only stripped by {@link TracingHttpClient}, so
     *     installing it without one would leak trace/span IDs to the provider.
     */
    private static boolean instrument(Tracer tracer, Object client, boolean replaceTracer) {
        if (ContextCapturingProxy.isContextCapturingProxy(client)) {
            if (!replaceTracer) {
                return true;
            }
            client = ContextCapturingProxy.unwrap(client);
        }
        try {
            instrumentHttpClient(tracer, client, replaceTracer);
            return true;
        } catch (Exception e) {
            log.error(
                    "failed to apply anthropic instrumentation to {} — leaving client untouched",
                    client.getClass().getName(),
                    e);
            return false;
        }
    }

    /** Preserves the source's current tracer when withOptions replaces a service's transport. */
    static boolean instrumentDerivedService(Object source, Object service) {
        try {
            ClientOptions options = getField(source, "clientOptions");
            // async() can rebuild the SDK retry layer around the traced original transport.
            HttpClient transport = options.httpClient();
            if (!(transport instanceof TracingHttpClient)) {
                transport = getField(options, "originalHttpClient");
            }
            if (transport instanceof TracingHttpClient tracing) {
                return instrument(tracing.tracer(), service, false);
            }
        } catch (ReflectiveOperationException | RuntimeException e) {
            log.error("failed to instrument derived anthropic service", e);
        }
        // Without a tracing transport, adding the context proxy would leak its internal header.
        return false;
    }

    private static void instrumentHttpClient(Tracer tracer, Object client, boolean replaceTracer) {
        int[] instrumented = {0};
        forAllFields(
                client,
                fieldName -> {
                    try {
                        if (getField(client, fieldName) instanceof ClientOptions clientOptions) {
                            instrumentClientOptions(tracer, clientOptions, replaceTracer);
                            instrumented[0]++;
                        }
                    } catch (ReflectiveOperationException e) {
                        throw new RuntimeException(e);
                    }
                });
        if (instrumented[0] == 0) {
            // Finding nothing is as much a failure as a reflection error: the request path
            // would bypass TracingHttpClient entirely.
            throw new IllegalStateException(
                    "no ClientOptions field found on "
                            + client.getClass().getName()
                            + " — unrecognized client shape");
        }
    }

    /**
     * Swaps both HTTP client fields on a {@link ClientOptions} for tracing wrappers and turns off
     * the SDK's built-in tracing.
     */
    private static void instrumentClientOptions(
            Tracer tracer, ClientOptions clientOptions, boolean replaceTracer) {
        disableNativeTracing(clientOptions);
        try {
            HttpClient original = getField(clientOptions, "originalHttpClient");
            if (original instanceof TracingHttpClient tracing
                    && !(clientOptions.httpClient() instanceof TracingHttpClient)) {
                // async()/withOptions() rebuild the SDK transport around the traced original.
                // Rebuild around its untraced delegate to retain retries without nested LLM spans.
                var builder = clientOptions.toBuilder();
                // Avoid the public setter's extra GC-closing wrapper for this shared transport.
                setPrivateField(builder, "httpClient", tracing.underlying());
                HttpClient rebuilt = builder.build().httpClient();
                setPrivateField(
                        clientOptions,
                        "httpClient",
                        new TracingHttpClient(replaceTracer ? tracer : tracing.tracer(), rebuilt));
            }
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException(e);
        }
        swapHttpClient(tracer, clientOptions, "originalHttpClient", replaceTracer);
        swapHttpClient(tracer, clientOptions, "httpClient", replaceTracer);
    }

    /**
     * anthropic-java 2.72.0+ has built-in OTel tracing, on by default via GlobalOpenTelemetry,
     * which would emit a duplicate {@code com.anthropic.sdk.java} span around each of ours. The SDK
     * re-reads {@code ClientOptions.openTelemetry().traces().enabled()} on every API call, so
     * replacing the field disables it for this client. Reflective because this module compiles
     * against 2.2.0, which predates that API; a no-op on older versions. Best-effort: on failure we
     * keep our instrumentation and accept the duplicate spans.
     */
    private static void disableNativeTracing(ClientOptions clientOptions) {
        Method openTelemetryGetter;
        try {
            openTelemetryGetter = ClientOptions.class.getMethod("openTelemetry");
        } catch (NoSuchMethodException e) {
            return; // anthropic-java < 2.72.0: no built-in tracing
        }
        try {
            Object otel = openTelemetryGetter.invoke(clientOptions);
            Object traces = otel.getClass().getMethod("traces").invoke(otel);
            if (!(Boolean) traces.getClass().getMethod("enabled").invoke(traces)) {
                return;
            }
            Object builder = otel.getClass().getMethod("toBuilder").invoke(otel);
            builder.getClass().getMethod("traces", boolean.class).invoke(builder, false);
            setPrivateField(
                    clientOptions,
                    "openTelemetry",
                    builder.getClass().getMethod("build").invoke(builder));
        } catch (ReflectiveOperationException | RuntimeException e) {
            log.warn(
                    "failed to disable anthropic-java built-in tracing; spans may be duplicated",
                    e);
        }
    }

    private static void swapHttpClient(
            Tracer tracer, ClientOptions clientOptions, String fieldName, boolean replaceTracer) {
        try {
            HttpClient httpClient = getField(clientOptions, fieldName);
            if (httpClient instanceof TracingHttpClient tracing) {
                if (replaceTracer) {
                    setPrivateField(clientOptions, fieldName, tracing.withTracer(tracer));
                }
            } else {
                setPrivateField(
                        clientOptions, fieldName, new TracingHttpClient(tracer, httpClient));
            }
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException(e);
        }
    }

    private static void forAllFields(Object object, Consumer<String> consumer) {
        if (object == null || consumer == null) return;
        Class<?> clazz = object.getClass();
        while (clazz != null && clazz != Object.class) {
            for (Field field : clazz.getDeclaredFields()) {
                if (field.isSynthetic()) continue;
                if (Modifier.isStatic(field.getModifiers())) continue;
                consumer.accept(field.getName());
            }
            clazz = clazz.getSuperclass();
        }
    }

    @SuppressWarnings("unchecked")
    private static <T> T getField(Object obj, String fieldName)
            throws ReflectiveOperationException {
        Class<?> clazz = obj.getClass();
        while (clazz != null) {
            try {
                Field field = clazz.getDeclaredField(fieldName);
                field.setAccessible(true);
                return (T) field.get(obj);
            } catch (NoSuchFieldException e) {
                clazz = clazz.getSuperclass();
            }
        }
        throw new NoSuchFieldException(fieldName);
    }

    private static void setPrivateField(Object obj, String fieldName, Object value)
            throws ReflectiveOperationException {
        Class<?> clazz = obj.getClass();
        while (clazz != null) {
            try {
                Field field = clazz.getDeclaredField(fieldName);
                field.setAccessible(true);
                field.set(obj, value);
                return;
            } catch (NoSuchFieldException e) {
                clazz = clazz.getSuperclass();
            }
        }
        throw new NoSuchFieldException(fieldName);
    }
}
