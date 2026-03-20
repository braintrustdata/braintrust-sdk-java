package dev.braintrust.instrumentation.springai.v1_0_0;

import dev.braintrust.json.BraintrustJsonMapper;
import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.trace.Tracer;
import java.io.IOException;
import java.lang.reflect.Field;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;
import lombok.extern.slf4j.Slf4j;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationHandler;
import io.micrometer.observation.ObservationRegistry;
import org.springframework.ai.chat.messages.AbstractMessage;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.observation.ChatModelObservationContext;
import org.springframework.ai.chat.observation.ChatModelObservationConvention;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.http.HttpRequest;
import org.springframework.http.client.ClientHttpRequestExecution;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.web.client.RestClient;

/** Braintrust Spring AI instrumentation entry point. */
@Slf4j
public class BraintrustSpringAI {
    private static final String TRACER_NAME = "braintrust-java";
    private static final Map<ObservationRegistry, Boolean> REGISTERED_OBSERVATION_REGISTRIES =
            Collections.synchronizedMap(new WeakHashMap<>());
    private static final ThreadLocal<Boolean> WRAP_IN_PROGRESS =
            ThreadLocal.withInitial(() -> false);

    /**
     * Wraps a Spring AI chat model so that every {@code call()} invocation is traced as a
     * Braintrust LLM span.
     */
    public static Object wrap(OpenTelemetry openTelemetry, ChatModel chatModel) {
        if (WRAP_IN_PROGRESS.get()) {
            return chatModel;
        }

        try {
            WRAP_IN_PROGRESS.set(true);
            if (chatModel instanceof OpenAiChatModel) {
                return instrumentOpenAiChatModel(openTelemetry, (OpenAiChatModel) chatModel);
            }
        } catch (Exception e) {
            log.error("failed to apply Spring AI instrumentation", e);
        } finally {
            WRAP_IN_PROGRESS.set(false);
        }
        return chatModel;
    }

    private static OpenAiChatModel instrumentOpenAiChatModel(
            OpenTelemetry openTelemetry, OpenAiChatModel chatModel)
            throws ReflectiveOperationException {
        OpenAiApi openAiApi = getField(chatModel, "openAiApi");
        RestClient restClient = getField(openAiApi, "restClient");
        Tracer tracer = openTelemetry.getTracer(TRACER_NAME);
        ObservationRegistry observationRegistry = getOrCreateObservationRegistry(chatModel);

        if (restClient == null || hasTracingInterceptor(restClient)) {
            // Already wrapped or nothing to wrap
            OpenAiChatModel observedChatModel = withObservationRegistry(chatModel, observationRegistry);
            registerObservationHandler(observedChatModel, tracer);
            return observedChatModel;
        }

        RestClient.Builder wrappedRestClientBuilder =
                restClient.mutate().requestInterceptor(new TracingInterceptor(tracer));
        OpenAiApi wrappedOpenAiApi =
                openAiApi.mutate().restClientBuilder(wrappedRestClientBuilder).build();
        // NOTE: calling the builder here will re-enter instrumentation but we'll detect our tracing
        // interceptor and stop so we'll only wrap once
        OpenAiChatModel wrappedChatModel =
                chatModel
                        .mutate()
                        .openAiApi(wrappedOpenAiApi)
                        .observationRegistry(observationRegistry)
                        .build();

        ChatModelObservationConvention observationConvention =
                getField(chatModel, "observationConvention");
        if (observationConvention != null) {
            wrappedChatModel.setObservationConvention(observationConvention);
        }

        registerObservationHandler(wrappedChatModel, tracer);

        return wrappedChatModel;
    }

    private static ObservationRegistry getOrCreateObservationRegistry(OpenAiChatModel chatModel)
            throws ReflectiveOperationException {
        ObservationRegistry observationRegistry = getField(chatModel, "observationRegistry");
        if (observationRegistry == null || observationRegistry.isNoop()) {
            return ObservationRegistry.create();
        }
        return observationRegistry;
    }

    private static OpenAiChatModel withObservationRegistry(
            OpenAiChatModel chatModel, ObservationRegistry observationRegistry) throws ReflectiveOperationException {
        ObservationRegistry currentObservationRegistry = getField(chatModel, "observationRegistry");
        if (currentObservationRegistry == observationRegistry) {
            return chatModel;
        }

        OpenAiChatModel observedChatModel = chatModel.mutate().observationRegistry(observationRegistry).build();
        ChatModelObservationConvention observationConvention =
                getField(chatModel, "observationConvention");
        if (observationConvention != null) {
            observedChatModel.setObservationConvention(observationConvention);
        }
        return observedChatModel;
    }

    private static void registerObservationHandler(OpenAiChatModel chatModel, Tracer tracer)
            throws ReflectiveOperationException {
        ObservationRegistry observationRegistry = getField(chatModel, "observationRegistry");
        if (observationRegistry == null) {
            return;
        }

        synchronized (REGISTERED_OBSERVATION_REGISTRIES) {
            if (REGISTERED_OBSERVATION_REGISTRIES.containsKey(observationRegistry)) {
                return;
            }
            observationRegistry
                    .observationConfig()
                    .observationHandler(new BraintrustChatModelObservationHandler(tracer));
            REGISTERED_OBSERVATION_REGISTRIES.put(observationRegistry, Boolean.TRUE);
        }
    }

    private static boolean hasTracingInterceptor(RestClient restClient)
            throws ReflectiveOperationException {
        List<ClientHttpRequestInterceptor> interceptors = getField(restClient, "interceptors");
        if (interceptors == null) {
            return false;
        }

        for (ClientHttpRequestInterceptor interceptor : interceptors) {
            if (interceptor instanceof TracingInterceptor) {
                return true;
            }
        }
        return false;
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
        throw new NoSuchFieldException(
                "Field '" + fieldName + "' not found on " + obj.getClass().getName());
    }

    private static final class TracingInterceptor implements ClientHttpRequestInterceptor {
        private final Tracer tracer;

        private TracingInterceptor(Tracer tracer) {
            this.tracer = tracer;
        }

        @Override
        public ClientHttpResponse intercept(
                HttpRequest request, byte[] body, ClientHttpRequestExecution execution)
                throws IOException {
            var span = tracer.spanBuilder("todo").startSpan();
            try (var ignored = span.makeCurrent()) {
                // TODO: tag request
                ClientHttpResponse response = execution.execute(request, body);
                // TODO: tag response
                return response;
            } finally {
                span.end();
            }
        }
    }

    private static final class BraintrustChatModelObservationHandler
            implements ObservationHandler<ChatModelObservationContext> {
        private static final String OBSERVATION_SPAN_KEY =
                BraintrustChatModelObservationHandler.class.getName() + ".span";

        private final Tracer tracer;

        private BraintrustChatModelObservationHandler(Tracer tracer) {
            this.tracer = tracer;
        }

        @Override
        public boolean supportsContext(Observation.Context context) {
            return context instanceof ChatModelObservationContext;
        }

        @Override
        public void onStart(ChatModelObservationContext context) {
            var span = tracer.spanBuilder("spring.ai.chat").startSpan();
            context.put(OBSERVATION_SPAN_KEY, span);

            Prompt prompt = context.getRequest();
            System.out.println("DONTMERGE request: " + BraintrustJsonMapper.toJson(context.getRequest()));
            if (prompt != null) {
                System.out.println("SPRING AI INPUT MESSAGES:");
                printInputMessages(prompt.getInstructions());
            }
        }

        @Override
        public void onError(ChatModelObservationContext context) {
            var span = context.get(OBSERVATION_SPAN_KEY);
            if (span instanceof io.opentelemetry.api.trace.Span) {
                Throwable error = context.getError();
                if (error != null) {
                    ((io.opentelemetry.api.trace.Span) span).recordException(error);
                }
            }
        }

        @Override
        public void onStop(ChatModelObservationContext context) {
            try {
                ChatResponse response = context.getResponse();
                System.out.println("DONTMERGE response: " + BraintrustJsonMapper.toJson(response));
                if (response != null) {
                    System.out.println("SPRING AI OUTPUT MESSAGES:");
                    // printOutputMessages(response.getResults());
                }
            } finally {
                var span = context.get(OBSERVATION_SPAN_KEY);
                if (span instanceof io.opentelemetry.api.trace.Span) {
                    ((io.opentelemetry.api.trace.Span) span).end();
                }
            }
        }

        private static void printInputMessages(List<Message> messages) {
            if (messages == null || messages.isEmpty()) {
                System.out.println("  (none)");
                return;
            }

            for (Message message : messages) {
                System.out.println("  - " + message.getMessageType() + ": " + messageText(message));
            }
        }

        private static void printOutputMessages(List<Generation> generations) {
            if (generations == null || generations.isEmpty()) {
                System.out.println("  (none)");
                return;
            }

            for (Generation generation : generations) {
                AssistantMessage output = generation.getOutput();
                System.out.println(
                        "  - ASSISTANT: " + (output == null ? "" : output.getText()));
            }
        }

        private static String messageText(Message message) {
            if (message instanceof AbstractMessage) {
                return ((AbstractMessage) message).getText();
            }
            return String.valueOf(message);
        }
    }

    private BraintrustSpringAI() {}
}
