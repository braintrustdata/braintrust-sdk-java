package dev.braintrust.instrumentation.springai.v1_0_0;

import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationHandler;
import io.micrometer.observation.ObservationRegistry;
import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.trace.Tracer;
import java.io.IOException;
import java.lang.reflect.Field;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.messages.AbstractMessage;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.observation.ChatModelObservationContext;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.http.HttpRequest;
import org.springframework.http.client.ClientHttpRequestExecution;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.web.client.RestClient;

/** Braintrust Spring AI instrumentation entry point. */
@Slf4j
public class BraintrustSpringAI {
    private static final String TRACER_NAME = "braintrust-java";
    private static final Map<ObservationRegistry, Boolean> REGISTERED_REGISTRIES =
            Collections.synchronizedMap(new WeakHashMap<>());

    /**
     * Instruments an {@link OpenAiChatModel.Builder} in place before {@code build()} runs.
     *
     * <p>Installs a {@link TracingInterceptor} on the builder's {@code RestClient} (for HTTP-level
     * spans) and wires a {@link BraintrustChatModelObservationHandler} into the builder's {@link
     * ObservationRegistry} (for semantic input/output observation). If the builder already carries
     * a no-op registry a fresh one is created and set on the builder so observations fire.
     */
    public static void prepareBuilder(OpenTelemetry openTelemetry, Object builderObj) {
        if (!(builderObj instanceof OpenAiChatModel.Builder)) {
            return;
        }
        OpenAiChatModel.Builder builder = (OpenAiChatModel.Builder) builderObj;
        try {
            Tracer tracer = openTelemetry.getTracer(TRACER_NAME);

            // --- wire the HTTP tracing interceptor via RestClient ---
            /*
            OpenAiApi openAiApi = getField(builder, "openAiApi");
            if (openAiApi != null) {
                RestClient restClient = getField(openAiApi, "restClient");
                if (restClient != null && !hasTracingInterceptor(restClient)) {
                    RestClient.Builder wrappedRestClientBuilder =
                            restClient.mutate().requestInterceptor(new TracingInterceptor(tracer));
                    OpenAiApi wrappedOpenAiApi =
                            openAiApi.mutate().restClientBuilder(wrappedRestClientBuilder).build();
                    builder.openAiApi(wrappedOpenAiApi);
                }
            }
             */

            // --- wire the observation handler via ObservationRegistry ---
            ObservationRegistry registry = getField(builder, "observationRegistry");
            if (registry == null || registry.isNoop()) {
                registry = ObservationRegistry.create();
                builder.observationRegistry(registry);
            }
            synchronized (REGISTERED_REGISTRIES) {
                if (!REGISTERED_REGISTRIES.containsKey(registry)) {
                    registry.observationConfig()
                            .observationHandler(new BraintrustChatModelObservationHandler(tracer));
                    REGISTERED_REGISTRIES.put(registry, Boolean.TRUE);
                }
            }
        } catch (Exception e) {
            log.error("failed to prepare Spring AI builder", e);
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
                if (response != null) {
                    System.out.println("SPRING AI OUTPUT MESSAGES:");
                    printOutputMessages(response.getResults());
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
                System.out.println("  - ASSISTANT: " + (output == null ? "" : output.getText()));
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
