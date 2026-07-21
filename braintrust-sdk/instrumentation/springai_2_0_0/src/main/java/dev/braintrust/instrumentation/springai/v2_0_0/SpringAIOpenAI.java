package dev.braintrust.instrumentation.springai.v2_0_0;

import com.openai.client.OpenAIClient;
import com.openai.client.OpenAIClientAsync;
import dev.braintrust.instrumentation.openai.v2_15_0.BraintrustOpenAI;
import io.opentelemetry.api.OpenTelemetry;
import java.lang.reflect.Field;
import lombok.extern.slf4j.Slf4j;

/**
 * Instruments a Spring AI 2.x {@code OpenAiChatModel} in place.
 *
 * <p>Spring AI 2.0 delegates all HTTP to the official openai-java SDK: the chat model holds a
 * {@code com.openai.client.OpenAIClient} (sync calls) and a {@code
 * com.openai.client.OpenAIClientAsync} (streaming). Both are wrapped with the same {@code
 * TracingHttpClient} used by the openai-java instrumentation module. Spring AI types are only
 * accessed reflectively so this class never links against them.
 *
 * <p>Internal — the public entry point is {@link BraintrustSpringAI#wrap}.
 */
@Slf4j
final class SpringAIOpenAI {

    /** Wraps the official SDK clients inside an {@code OpenAiChatModel}. Idempotent. */
    static <T> T wrap(OpenTelemetry openTelemetry, T chatModel) {
        wrapClientField(openTelemetry, chatModel, "openAiClient");
        wrapClientField(openTelemetry, chatModel, "openAiClientAsync");
        return chatModel;
    }

    private static void wrapClientField(
            OpenTelemetry openTelemetry, Object chatModel, String fieldName) {
        try {
            // The wrapped client is a context-capturing view (not just mutated in place), so
            // swap it back into the model's field — that way the caller's span parents async
            // streaming requests correctly.
            Object client = getField(chatModel, fieldName);
            if (client instanceof OpenAIClient sync) {
                setField(chatModel, fieldName, BraintrustOpenAI.wrapOpenAI(openTelemetry, sync));
            } else if (client instanceof OpenAIClientAsync async) {
                setField(chatModel, fieldName, BraintrustOpenAI.wrapOpenAI(openTelemetry, async));
            }
        } catch (Exception e) {
            log.error("failed to instrument spring ai openai client field {}", fieldName, e);
        }
    }

    private static void setField(Object obj, String fieldName, Object value)
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
        throw new NoSuchFieldException(
                "Field '" + fieldName + "' not found on " + obj.getClass().getName());
    }

    private static Object getField(Object obj, String fieldName)
            throws ReflectiveOperationException {
        Class<?> clazz = obj.getClass();
        while (clazz != null) {
            try {
                Field field = clazz.getDeclaredField(fieldName);
                field.setAccessible(true);
                return field.get(obj);
            } catch (NoSuchFieldException e) {
                clazz = clazz.getSuperclass();
            }
        }
        throw new NoSuchFieldException(
                "Field '" + fieldName + "' not found on " + obj.getClass().getName());
    }

    private SpringAIOpenAI() {}
}
