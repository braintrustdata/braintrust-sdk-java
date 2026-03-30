package dev.braintrust.instrumentation.springai.v1_0_0;

import com.fasterxml.jackson.databind.JsonNode;
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
import org.springframework.ai.anthropic.AnthropicChatModel;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.observation.ChatModelObservationContext;
import org.springframework.ai.chat.prompt.Prompt;

@Slf4j
class AnthropicBuilderWrapper {
    private static final String TRACER_NAME = "braintrust-java";
    private static final Set<ObservationRegistry> REGISTERED_REGISTRIES =
            Collections.synchronizedSet(Collections.newSetFromMap(new WeakHashMap<>()));

    /** Reflection-friendly entry point called from {@link BraintrustSpringAI#wrap}. */
    static void wrap(OpenTelemetry openTelemetry, Object builderObj) {
        wrap(openTelemetry, (AnthropicChatModel.Builder) builderObj);
    }

    /** Instruments an {@link AnthropicChatModel.Builder} in place before {@code build()} runs. */
    static void wrap(OpenTelemetry openTelemetry, AnthropicChatModel.Builder builder) {
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
                                            AnthropicBuilderWrapper::tagSpanRequest,
                                            AnthropicBuilderWrapper::tagSpanResponse));
                    REGISTERED_REGISTRIES.add(registry);
                }
            }
        } catch (Exception e) {
            log.error("failed to prepare Spring AI Anthropic builder", e);
        }
    }

    // -------------------------------------------------------------------------
    // Span-tagging helpers
    // -------------------------------------------------------------------------

    @SneakyThrows
    static void tagSpanRequest(
            BraintrustObservationHandler observationHandler,
            Span span,
            ChatModelObservationContext context) {
        Prompt prompt = context.getRequest();
        ArrayNode messages = BraintrustJsonMapper.get().createArrayNode();
        for (Message msg : prompt.getInstructions()) {
            ObjectNode msgNode = BraintrustJsonMapper.get().createObjectNode();
            msgNode.put("role", msg.getMessageType().getValue().toLowerCase());
            String text = msg.getText();
            try {
                JsonNode parsed = BraintrustJsonMapper.get().readTree(text);
                if (parsed.isArray() || parsed.isObject()) {
                    msgNode.set("content", parsed);
                } else {
                    msgNode.put("content", text);
                }
            } catch (Exception e) {
                msgNode.put("content", text);
            }
            messages.add(msgNode);
        }
        String model = null;
        if (prompt.getOptions() != null && prompt.getOptions().getModel() != null) {
            model = prompt.getOptions().getModel().toString();
        }
        ObjectNode requestBody = BraintrustJsonMapper.get().createObjectNode();
        requestBody.set("messages", messages);
        if (model != null) requestBody.put("model", model);

        InstrumentationSemConv.tagLLMSpanRequest(
                span,
                InstrumentationSemConv.PROVIDER_NAME_ANTHROPIC,
                observationHandler.getBaseUrl(),
                List.of("v1", "messages"),
                "POST",
                BraintrustJsonMapper.toJson(requestBody));
    }

    @SneakyThrows
    static void tagSpanResponse(
            BraintrustObservationHandler observationHandler,
            Span span,
            ChatModelObservationContext context) {
        ChatResponse chatResponse = context.getResponse();
        if (null == chatResponse) {
            return;
        }
        ArrayNode content = BraintrustJsonMapper.get().createArrayNode();
        for (var generation : chatResponse.getResults()) {
            ObjectNode block = BraintrustJsonMapper.get().createObjectNode();
            block.put("type", "text");
            block.put("text", generation.getOutput().getText());
            content.add(block);
        }
        ObjectNode responseBody = BraintrustJsonMapper.get().createObjectNode();
        responseBody.put("role", "assistant");
        responseBody.set("content", content);

        ChatResponseMetadata metadata = chatResponse.getMetadata();
        if (metadata != null && metadata.getUsage() != null) {
            Usage usage = metadata.getUsage();
            Integer promptTokens = usage.getPromptTokens();
            Integer completionTokens = usage.getCompletionTokens();
            ObjectNode usageNode = BraintrustJsonMapper.get().createObjectNode();
            if (promptTokens != null) usageNode.put("input_tokens", promptTokens);
            if (completionTokens != null) usageNode.put("output_tokens", completionTokens);
            responseBody.set("usage", usageNode);
        }

        InstrumentationSemConv.tagLLMSpanResponse(
                span,
                InstrumentationSemConv.PROVIDER_NAME_ANTHROPIC,
                BraintrustJsonMapper.toJson(responseBody),
                context.get(BraintrustObservationHandler.TTFT_NANOS_KEY));
    }

    private static String extractBaseUrl(AnthropicChatModel.Builder builder) {
        try {
            // AnthropicApi doesn't store baseUrl directly; dig through:
            // builder.anthropicApi -> restClient (DefaultRestClient)
            //   -> uriBuilderFactory (DefaultUriBuilderFactory) -> baseUri (UriComponentsBuilder)
            Object anthropicApi = getField(builder, "anthropicApi");
            Object restClient = getField(anthropicApi, "restClient");
            Object uriBuilderFactory = getField(restClient, "uriBuilderFactory");
            Object baseUri = getField(uriBuilderFactory, "baseUri");
            return (String) baseUri.getClass().getMethod("toUriString").invoke(baseUri);
        } catch (Exception e) {
            log.warn("Failed to extract baseUrl from builder", e);
            return "https://api.anthropic.com";
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

    private AnthropicBuilderWrapper() {}
}
