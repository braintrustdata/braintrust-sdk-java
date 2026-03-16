package dev.braintrust.instrumentation.openai.v2_8_0.manual;

import com.openai.client.OpenAIClient;
import com.openai.core.ClientOptions;
import com.openai.core.http.HttpClient;
import io.opentelemetry.api.OpenTelemetry;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import kotlin.Lazy;
import lombok.extern.slf4j.Slf4j;

/** Braintrust OpenAI client instrumentation. */
@Slf4j
public class BraintrustOpenAI {

    /** Instrument openai client with braintrust traces */
    public static OpenAIClient wrapOpenAI(OpenTelemetry openTelemetry, OpenAIClient openAIClient) {
        try {
            instrumentHttpClient(openTelemetry, openAIClient);
            return openAIClient;
        } catch (Exception e) {
            log.error("failed to apply openai instrumentation", e);
            return openAIClient;
        }
    }

    // FIXME: bring back braintrust prompt

    private static void instrumentHttpClient(
            OpenTelemetry openTelemetry, OpenAIClient openAIClient) {
        forAllFields(
                openAIClient,
                fieldName -> {
                    try {
                        var field = getField(openAIClient, fieldName);
                        if (field instanceof ClientOptions clientOptions) {
                            instrumentClientOptions(
                                    openTelemetry, clientOptions, "originalHttpClient");
                            instrumentClientOptions(openTelemetry, clientOptions, "httpClient");
                        } else {
                            if (field instanceof Lazy<?> lazyField) {
                                var resolved = lazyField.getValue();
                                forAllFieldsOfType(
                                        resolved,
                                        ClientOptions.class,
                                        (clientOptions, subfieldName) ->
                                                instrumentClientOptions(
                                                        openTelemetry,
                                                        clientOptions,
                                                        subfieldName));
                            } else {
                                forAllFieldsOfType(
                                        field,
                                        ClientOptions.class,
                                        (clientOptions, subfieldName) ->
                                                instrumentClientOptions(
                                                        openTelemetry,
                                                        clientOptions,
                                                        subfieldName));
                            }
                        }
                    } catch (ReflectiveOperationException e) {
                        throw new RuntimeException(e);
                    }
                });
    }

    private static <T> void forAllFields(Object object, Consumer<String> consumer) {
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
