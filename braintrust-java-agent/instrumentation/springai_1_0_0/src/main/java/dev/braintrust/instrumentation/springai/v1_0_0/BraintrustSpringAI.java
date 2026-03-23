package dev.braintrust.instrumentation.springai.v1_0_0;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.braintrust.instrumentation.InstrumentationSemConv;
import dev.braintrust.json.BraintrustJsonMapper;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationHandler;
import io.micrometer.observation.ObservationRegistry;
import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.Tracer;
import java.io.IOException;
import java.lang.reflect.Field;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.observation.ChatModelObservationContext;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.http.HttpRequest;
import org.springframework.http.client.ClientHttpRequestExecution;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.http.client.ClientHttpResponse;
import reactor.core.publisher.Flux;

/** Braintrust Spring AI instrumentation entry point. */
@Slf4j
public class BraintrustSpringAI {
    private static final String TRACER_NAME = "braintrust-java";
    private static final Map<ObservationRegistry, Boolean> REGISTERED_REGISTRIES =
            Collections.synchronizedMap(new WeakHashMap<>());

    /**
     * Instruments an {@link OpenAiChatModel.Builder} in place before {@code build()} runs.
     *
     * <p>Wires a {@link BraintrustChatModelObservationHandler} into the builder's {@link
     * ObservationRegistry} so that every {@code call()} invocation is observed. If the builder
     * carries the default no-op registry a fresh one is created first.
     */
    public static void prepareBuilder(OpenTelemetry openTelemetry, Object builderObj) {
        if (!(builderObj instanceof OpenAiChatModel.Builder)) {
            return;
        }
        OpenAiChatModel.Builder builder = (OpenAiChatModel.Builder) builderObj;
        try {
            Tracer tracer = openTelemetry.getTracer(TRACER_NAME);
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

    /**
     * Wraps a {@code Flux<ChatResponse>} returned by {@code OpenAiChatModel.stream(Prompt)} with a
     * Braintrust LLM span.
     *
     * <p>Spring AI's streaming path does not fire the standard Micrometer chat observation, so we
     * instrument it here. The prompt is captured on method-enter (before {@code stream()} runs) and
     * passed alongside the returned Flux so we can tag the span immediately.
     */
    public static Object wrapStream(OpenTelemetry openTelemetry, Object promptObj, Object fluxObj) {
        if (!(fluxObj instanceof Flux)) {
            return fluxObj;
        }
        @SuppressWarnings("unchecked")
        Flux<ChatResponse> flux = (Flux<ChatResponse>) fluxObj;
        try {
            Tracer tracer = openTelemetry.getTracer(TRACER_NAME);
            Span span = tracer.spanBuilder(InstrumentationSemConv.UNSET_LLM_SPAN_NAME).startSpan();

            if (promptObj instanceof Prompt) {
                tagSpanRequest(span, (Prompt) promptObj);
            }

            StringBuilder accumulatedText = new StringBuilder();
            return flux.doOnNext(
                            chunk -> {
                                if (chunk.getResult() != null
                                        && chunk.getResult().getOutput() != null
                                        && chunk.getResult().getOutput().getText() != null) {
                                    accumulatedText.append(chunk.getResult().getOutput().getText());
                                }
                            })
                    .doOnComplete(
                            () -> {
                                String text = accumulatedText.toString();
                                if (!text.isEmpty()) {
                                    try {
                                        ArrayNode choices =
                                                BraintrustJsonMapper.get().createArrayNode();
                                        ObjectNode choice =
                                                BraintrustJsonMapper.get().createObjectNode();
                                        ObjectNode message =
                                                BraintrustJsonMapper.get().createObjectNode();
                                        message.put("role", "assistant");
                                        message.put("content", text);
                                        choice.set("message", message);
                                        choice.put("finish_reason", "stop");
                                        choices.add(choice);
                                        ObjectNode responseBody =
                                                BraintrustJsonMapper.get().createObjectNode();
                                        responseBody.set("choices", choices);
                                        InstrumentationSemConv.tagLLMSpanResponse(
                                                span,
                                                InstrumentationSemConv.PROVIDER_NAME_OPENAI,
                                                BraintrustJsonMapper.toJson(responseBody));
                                    } catch (Exception e) {
                                        log.error("failed to tag Spring AI stream response", e);
                                    }
                                }
                                span.end();
                            })
                    .doOnError(
                            err -> {
                                InstrumentationSemConv.tagLLMSpanResponse(span, err);
                                span.end();
                            });
        } catch (Exception e) {
            log.error("failed to wrap Spring AI stream", e);
            return fluxObj;
        }
    }

    // -------------------------------------------------------------------------
    // Shared span-tagging helpers (used by both the observation handler and wrapStream)
    // -------------------------------------------------------------------------

    @SneakyThrows
    static void tagSpanRequest(Span span, Prompt prompt) {
        ArrayNode messages = BraintrustJsonMapper.get().createArrayNode();
        for (Message msg : prompt.getInstructions()) {
            ObjectNode msgNode = BraintrustJsonMapper.get().createObjectNode();
            msgNode.put("role", msg.getMessageType().getValue().toLowerCase());
            msgNode.put("content", msg.getText());
            messages.add(msgNode);
        }

        String model = null;
        if (prompt.getOptions() != null) {
            Object modelOpt = prompt.getOptions().getModel();
            if (modelOpt != null) {
                model = modelOpt.toString();
            }
        }

        ObjectNode requestBody = BraintrustJsonMapper.get().createObjectNode();
        requestBody.set("messages", messages);
        if (model != null) {
            requestBody.put("model", model);
        }

        InstrumentationSemConv.tagLLMSpanRequest(
                span,
                InstrumentationSemConv.PROVIDER_NAME_OPENAI,
                "https://api.openai.com",
                List.of("v1", "chat", "completions"),
                "POST",
                BraintrustJsonMapper.toJson(requestBody));
    }

    @SneakyThrows
    static void tagSpanResponse(Span span, ChatResponse chatResponse) {
        ArrayNode choices = BraintrustJsonMapper.get().createArrayNode();
        for (var generation : chatResponse.getResults()) {
            ObjectNode choice = BraintrustJsonMapper.get().createObjectNode();
            ObjectNode message = BraintrustJsonMapper.get().createObjectNode();
            message.put("role", "assistant");
            message.put("content", generation.getOutput().getText());
            choice.set("message", message);
            choice.put(
                    "finish_reason",
                    generation.getMetadata().getFinishReason() != null
                            ? generation.getMetadata().getFinishReason().toLowerCase()
                            : "stop");
            choices.add(choice);
        }

        ObjectNode responseBody = BraintrustJsonMapper.get().createObjectNode();
        responseBody.set("choices", choices);

        ChatResponseMetadata metadata = chatResponse.getMetadata();
        if (metadata != null && metadata.getUsage() != null) {
            Usage usage = metadata.getUsage();
            Integer promptTokens = usage.getPromptTokens();
            Integer completionTokens = usage.getCompletionTokens();
            ObjectNode usageNode = BraintrustJsonMapper.get().createObjectNode();
            if (promptTokens != null) usageNode.put("prompt_tokens", promptTokens);
            if (completionTokens != null) usageNode.put("completion_tokens", completionTokens);
            if (promptTokens != null && completionTokens != null) {
                usageNode.put("total_tokens", promptTokens + completionTokens);
            }
            responseBody.set("usage", usageNode);
        }

        InstrumentationSemConv.tagLLMSpanResponse(
                span,
                InstrumentationSemConv.PROVIDER_NAME_OPENAI,
                BraintrustJsonMapper.toJson(responseBody));
    }

    // -------------------------------------------------------------------------
    // Internal helpers
    // -------------------------------------------------------------------------

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
                ClientHttpResponse response = execution.execute(request, body);
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
            Span span = tracer.spanBuilder(InstrumentationSemConv.UNSET_LLM_SPAN_NAME).startSpan();
            context.put(OBSERVATION_SPAN_KEY, span);
            Prompt prompt = context.getRequest();
            if (prompt != null) {
                tagSpanRequest(span, prompt);
            }
        }

        @Override
        public void onError(ChatModelObservationContext context) {
            Span span = context.get(OBSERVATION_SPAN_KEY);
            if (span != null) {
                Throwable error = context.getError();
                if (error != null) {
                    InstrumentationSemConv.tagLLMSpanResponse(span, error);
                }
            }
        }

        @Override
        public void onStop(ChatModelObservationContext context) {
            Span span = context.get(OBSERVATION_SPAN_KEY);
            if (span == null) {
                return;
            }
            try {
                ChatResponse response = context.getResponse();
                if (response != null) {
                    tagSpanResponse(span, response);
                }
            } finally {
                span.end();
            }
        }
    }

    private BraintrustSpringAI() {}
}
