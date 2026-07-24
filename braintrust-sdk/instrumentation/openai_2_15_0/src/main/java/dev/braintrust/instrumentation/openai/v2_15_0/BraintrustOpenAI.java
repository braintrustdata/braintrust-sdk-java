package dev.braintrust.instrumentation.openai.v2_15_0;

import com.openai.client.OpenAIClient;
import com.openai.client.OpenAIClientAsync;
import com.openai.core.ClientOptions;
import com.openai.core.ObjectMappers;
import com.openai.core.http.HttpClient;
import com.openai.models.chat.completions.ChatCompletionCreateParams;
import dev.braintrust.prompt.BraintrustPrompt;
import io.opentelemetry.api.OpenTelemetry;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Consumer;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;

/** Braintrust OpenAI client instrumentation. */
@Slf4j
public class BraintrustOpenAI {

    /** Instrument openai client with braintrust traces */
    public static OpenAIClient wrapOpenAI(OpenTelemetry openTelemetry, OpenAIClient openAIClient) {
        if (!instrument(openTelemetry, openAIClient)) {
            return openAIClient;
        }
        return ContextCapturingProxy.wrap(openAIClient, OpenAIClient.class);
    }

    /** Instrument an async openai client with braintrust traces */
    public static OpenAIClientAsync wrapOpenAI(
            OpenTelemetry openTelemetry, OpenAIClientAsync openAIClient) {
        if (!instrument(openTelemetry, openAIClient)) {
            return openAIClient;
        }
        return ContextCapturingProxy.wrap(openAIClient, OpenAIClientAsync.class);
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
    private static boolean instrument(OpenTelemetry openTelemetry, Object client) {
        if (ContextCapturingProxy.isContextCapturingProxy(client)) {
            // already instrumented
            return true;
        }
        try {
            instrumentHttpClient(openTelemetry, client);
            return true;
        } catch (Exception e) {
            log.error(
                    "failed to apply openai instrumentation to {} — leaving client untouched",
                    client.getClass().getName(),
                    e);
            return false;
        }
    }

    @SneakyThrows
    public static ChatCompletionCreateParams buildChatCompletionsPrompt(
            BraintrustPrompt prompt, Map<String, Object> parameters) {
        var promptMap = new HashMap<>(prompt.getOptions());
        promptMap.put("messages", prompt.renderMessages(parameters));
        var promptJson = ObjectMappers.jsonMapper().writeValueAsString(promptMap);

        var body =
                ObjectMappers.jsonMapper()
                        .readValue(promptJson, ChatCompletionCreateParams.Body.class);

        return ChatCompletionCreateParams.builder()
                .body(body)
                .additionalBodyProperties(Map.of())
                .build();
    }

    private static void instrumentHttpClient(OpenTelemetry openTelemetry, Object openAIClient) {
        int[] instrumented = {0};
        forAllFields(
                openAIClient,
                fieldName -> {
                    try {
                        if (getField(openAIClient, fieldName)
                                instanceof ClientOptions clientOptions) {
                            instrumentClientOptions(openTelemetry, clientOptions);
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
                            + openAIClient.getClass().getName()
                            + " — unrecognized client shape");
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

    /** Swaps both HTTP client fields on a {@link ClientOptions} for tracing wrappers. */
    private static void instrumentClientOptions(
            OpenTelemetry openTelemetry, ClientOptions clientOptions) {
        swapHttpClient(openTelemetry, clientOptions, "originalHttpClient");
        swapHttpClient(openTelemetry, clientOptions, "httpClient");
    }

    private static void swapHttpClient(
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
