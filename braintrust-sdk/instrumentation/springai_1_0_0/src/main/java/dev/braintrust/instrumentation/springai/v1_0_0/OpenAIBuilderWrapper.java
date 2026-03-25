package dev.braintrust.instrumentation.springai.v1_0_0;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.braintrust.instrumentation.InstrumentationSemConv;
import dev.braintrust.json.BraintrustJsonMapper;
import io.micrometer.observation.ObservationRegistry;
import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.Tracer;
import java.lang.reflect.Field;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.WeakHashMap;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.openai.OpenAiChatModel;

@Slf4j
class OpenAIBuilderWrapper {
    private static final String TRACER_NAME = "braintrust-java";
    private static final Set<ObservationRegistry> REGISTERED_REGISTRIES =
            Collections.synchronizedSet(Collections.newSetFromMap(new WeakHashMap<>()));

    /** Reflection-friendly entry point called from {@link BraintrustSpringAI#wrap}. */
    static void wrap(OpenTelemetry openTelemetry, Object builderObj) {
        wrap(openTelemetry, (OpenAiChatModel.Builder) builderObj);
    }

    /** Instruments an {@link OpenAiChatModel.Builder} in place before {@code build()} runs. */
    static OpenAiChatModel.Builder wrap(
            OpenTelemetry openTelemetry, OpenAiChatModel.Builder builder) {
        try {
            Tracer tracer = openTelemetry.getTracer(TRACER_NAME);
            ObservationRegistry registry = getField(builder, "observationRegistry");
            if (registry == null || registry.isNoop()) {
                registry = ObservationRegistry.create();
                builder.observationRegistry(registry);
            }
            synchronized (REGISTERED_REGISTRIES) {
                if (!REGISTERED_REGISTRIES.contains(registry)) {
                    registry.observationConfig()
                            .observationHandler(
                                    new BraintrustObservationHandler(
                                            tracer,
                                            extractBaseUrl(builder),
                                            OpenAIBuilderWrapper::tagSpanRequest,
                                            OpenAIBuilderWrapper::tagSpanResponse));
                    REGISTERED_REGISTRIES.add(registry);
                }
            }
        } catch (Exception e) {
            log.error("failed to prepare Spring AI builder", e);
        }
        return builder;
    }

    @SneakyThrows
    static void tagSpanRequest(
            BraintrustObservationHandler observationHandler, Span span, Prompt prompt) {
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
                observationHandler.getBaseUrl(),
                List.of("v1", "chat", "completions"),
                "POST",
                BraintrustJsonMapper.toJson(requestBody));
    }

    @SneakyThrows
    static void tagSpanResponse(
            BraintrustObservationHandler observationHandler, Span span, ChatResponse chatResponse) {
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

    private static String extractBaseUrl(OpenAiChatModel.Builder builder) {
        try {
            Object openAiApi = getField(builder, "openAiApi");
            return getField(openAiApi, "baseUrl");
        } catch (Exception e) {
            log.warn("Failed to extract baseUrl from builder", e);
            return "https://api.openai.com";
        }
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

    private OpenAIBuilderWrapper() {}
}
