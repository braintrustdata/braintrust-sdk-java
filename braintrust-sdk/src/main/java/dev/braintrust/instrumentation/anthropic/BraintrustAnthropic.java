package dev.braintrust.instrumentation.anthropic;

import com.anthropic.client.AnthropicClient;
import com.anthropic.core.ClientOptions;
import com.anthropic.core.http.HttpClient;
import io.opentelemetry.api.OpenTelemetry;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import kotlin.Lazy;
import lombok.extern.slf4j.Slf4j;

/** Braintrust Anthropic client instrumentation. */
@Slf4j
public final class BraintrustAnthropic {

    /** Instrument Anthropic client with Braintrust traces. */
    public static AnthropicClient wrap(OpenTelemetry openTelemetry, AnthropicClient client) {
        try {
            instrumentHttpClient(openTelemetry, client);
            return client;
        } catch (Exception e) {
            log.error("failed to apply anthropic instrumentation", e);
            return client;
        }
    }

    private static void instrumentHttpClient(OpenTelemetry openTelemetry, AnthropicClient client) {
        forAllFields(
                client,
                fieldName -> {
                    try {
                        var field = getField(client, fieldName);
                        if (field instanceof ClientOptions clientOptions) {
                            instrumentClientOptions(
                                    openTelemetry, clientOptions, "originalHttpClient");
                            instrumentClientOptions(openTelemetry, clientOptions, "httpClient");
                        } else if (field instanceof Lazy<?> lazyField) {
                            var resolved = lazyField.getValue();
                            forAllFieldsOfType(
                                    resolved,
                                    ClientOptions.class,
                                    (clientOptions, subfieldName) ->
                                            instrumentClientOptions(
                                                    openTelemetry, clientOptions, subfieldName));
                        } else {
                            forAllFieldsOfType(
                                    field,
                                    ClientOptions.class,
                                    (clientOptions, subfieldName) ->
                                            instrumentClientOptions(
                                                    openTelemetry, clientOptions, subfieldName));
                        }
                    } catch (ReflectiveOperationException e) {
                        throw new RuntimeException(e);
                    }
                });
    }

    private static void instrumentClientOptions(
            OpenTelemetry openTelemetry, ClientOptions clientOptions, String fieldName) {
        try {
            HttpClient httpClient = getField(clientOptions, fieldName);
            if (!(httpClient instanceof TracingHttpClient)) {
                setPrivateField(
                        clientOptions, fieldName, new TracingHttpClient(openTelemetry, httpClient));
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

    private static <T> void forAllFieldsOfType(
            Object object, Class<T> targetClazz, BiConsumer<T, String> consumer) {
        forAllFields(
                object,
                fieldName -> {
                    try {
                        if (targetClazz.isAssignableFrom(object.getClass())) {
                            consumer.accept(getField(object, fieldName), fieldName);
                        }
                    } catch (ReflectiveOperationException e) {
                        throw new RuntimeException(e);
                    }
                });
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
