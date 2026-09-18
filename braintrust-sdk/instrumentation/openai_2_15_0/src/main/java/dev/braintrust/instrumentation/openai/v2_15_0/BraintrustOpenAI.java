package dev.braintrust.instrumentation.openai.v2_15_0;

import com.openai.client.OpenAIClient;
import com.openai.client.OpenAIClientAsync;
import com.openai.core.ClientOptions;
import com.openai.core.ObjectMappers;
import com.openai.core.http.HttpClient;
import com.openai.models.chat.completions.ChatCompletionCreateParams;
import dev.braintrust.prompt.BraintrustPrompt;
import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.trace.Tracer;
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
    static final String INSTRUMENTATION_NAME = "openai";
    static final String INSTRUMENTATION_VERSION = "2.15.0";

    /** Instrument openai client with braintrust traces */
    public static OpenAIClient wrapOpenAI(OpenTelemetry openTelemetry, OpenAIClient openAIClient) {
        if (!instrument(
                openTelemetry.getTracer(INSTRUMENTATION_NAME, INSTRUMENTATION_VERSION),
                openAIClient,
                false)) {
            return openAIClient;
        }
        return ContextCapturingProxy.wrap(openAIClient, OpenAIClient.class);
    }

    /** Instrument an async openai client with braintrust traces */
    public static OpenAIClientAsync wrapOpenAI(
            OpenTelemetry openTelemetry, OpenAIClientAsync openAIClient) {
        if (!instrument(
                openTelemetry.getTracer(INSTRUMENTATION_NAME, INSTRUMENTATION_VERSION),
                openAIClient,
                false)) {
            return openAIClient;
        }
        return ContextCapturingProxy.wrap(openAIClient, OpenAIClientAsync.class);
    }

    /**
     * Instruments a client using the owning library's tracer, replacing any existing provider
     * tracer without adding another tracing layer.
     */
    public static OpenAIClient wrapOpenAI(Tracer tracer, OpenAIClient openAIClient) {
        if (!instrument(tracer, openAIClient, true)) {
            return openAIClient;
        }
        return ContextCapturingProxy.wrap(openAIClient, OpenAIClient.class);
    }

    /** Async counterpart of {@link #wrapOpenAI(Tracer, OpenAIClient)}. */
    public static OpenAIClientAsync wrapOpenAI(Tracer tracer, OpenAIClientAsync openAIClient) {
        if (!instrument(tracer, openAIClient, true)) {
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

    private static void instrumentHttpClient(
            Tracer tracer, Object openAIClient, boolean replaceTracer) {
        int[] instrumented = {0};
        forAllFields(
                openAIClient,
                fieldName -> {
                    try {
                        if (getField(openAIClient, fieldName)
                                instanceof ClientOptions clientOptions) {
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
            Tracer tracer, ClientOptions clientOptions, boolean replaceTracer) {
        swapHttpClient(tracer, clientOptions, "originalHttpClient", replaceTracer);
        swapHttpClient(tracer, clientOptions, "httpClient", replaceTracer);
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
