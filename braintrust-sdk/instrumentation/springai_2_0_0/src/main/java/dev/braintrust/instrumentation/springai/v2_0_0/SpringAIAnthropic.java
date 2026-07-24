package dev.braintrust.instrumentation.springai.v2_0_0;

import com.anthropic.client.AnthropicClient;
import com.anthropic.client.AnthropicClientAsync;
import dev.braintrust.instrumentation.anthropic.v2_2_0.BraintrustAnthropic;
import io.opentelemetry.api.OpenTelemetry;
import java.lang.reflect.Field;
import lombok.extern.slf4j.Slf4j;

/**
 * Instruments a Spring AI 2.x {@code AnthropicChatModel} in place.
 *
 * <p>Spring AI 2.0 delegates all HTTP to the official anthropic-java SDK: the chat model holds a
 * {@code com.anthropic.client.AnthropicClient} (sync calls) and a {@code
 * com.anthropic.client.AnthropicClientAsync} (streaming). Both are wrapped with the same {@code
 * TracingHttpClient} used by the anthropic-java instrumentation module. Spring AI types are only
 * accessed reflectively so this class never links against them.
 *
 * <p>Internal — the public entry point is {@link BraintrustSpringAI#wrap}.
 */
@Slf4j
final class SpringAIAnthropic {

    /** Wraps the official SDK clients inside an {@code AnthropicChatModel}. Idempotent. */
    static <T> T wrap(OpenTelemetry openTelemetry, T chatModel) {
        wrapClientField(openTelemetry, chatModel, "anthropicClient");
        wrapClientField(openTelemetry, chatModel, "anthropicClientAsync");
        return chatModel;
    }

    private static void wrapClientField(
            OpenTelemetry openTelemetry, Object chatModel, String fieldName) {
        try {
            // The wrapped client is a context-capturing view (not just mutated in place), so
            // swap it back into the model's field — that way the caller's span parents async
            // streaming requests correctly.
            Object client = getField(chatModel, fieldName);
            if (client instanceof AnthropicClient sync) {
                setField(chatModel, fieldName, BraintrustAnthropic.wrap(openTelemetry, sync));
            } else if (client instanceof AnthropicClientAsync async) {
                setField(chatModel, fieldName, BraintrustAnthropic.wrap(openTelemetry, async));
            }
        } catch (Exception e) {
            log.error("failed to instrument spring ai anthropic client field {}", fieldName, e);
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

    private SpringAIAnthropic() {}
}
