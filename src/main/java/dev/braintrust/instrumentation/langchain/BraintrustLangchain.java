package dev.braintrust.instrumentation.langchain;

import dev.langchain4j.model.openai.OpenAiChatModel;
import dev.langchain4j.model.openai.OpenAiStreamingChatModel;
import dev.langchain4j.service.AiServiceContext;
import dev.langchain4j.service.AiServices;
import dev.langchain4j.service.tool.ToolExecutor;
import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.trace.Tracer;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;

/** Braintrust LangChain4j client instrumentation. */
@Slf4j
public final class BraintrustLangchain {

    private static final String INSTRUMENTATION_NAME = "braintrust-langchain4j";

    @SuppressWarnings("unchecked")
    public static <T> T wrap(OpenTelemetry openTelemetry, AiServices<T> aiServices) {
        try {
            AiServiceContext context = getPrivateField(aiServices, "context");
            Tracer tracer = openTelemetry.getTracer(INSTRUMENTATION_NAME);

            // ////// CREATE A LLM SPAN FOR EACH CALL TO AI PROVIDER
            var chatModel = context.chatModel;
            var streamingChatModel = context.streamingChatModel;
            if (chatModel != null) {
                if (chatModel instanceof OpenAiChatModel oaiModel) {
                    aiServices.chatModel(wrap(openTelemetry, oaiModel));
                } else {
                    log.warn(
                            "unsupported model: {}. LLM calls will not be instrumented",
                            chatModel.getClass().getName());
                }
                // intentional fall-through
            } else if (streamingChatModel != null) {
                if (streamingChatModel instanceof OpenAiStreamingChatModel oaiModel) {
                    aiServices.streamingChatModel(wrap(openTelemetry, oaiModel));
                } else {
                    log.warn(
                            "unsupported model: {}. LLM calls will not be instrumented",
                            streamingChatModel.getClass().getName());
                }
                // intentional fall-through
            } else {
                // langchain is going to fail to build. don't apply instrumentation.
                throw new RuntimeException("model or chat model must be set");
            }

            if (context.toolService != null) {
                // ////// CREATE A SPAN FOR EACH TOOL CALL
                for (Map.Entry<String, ToolExecutor> entry :
                        context.toolService.toolExecutors().entrySet()) {
                    String toolName = entry.getKey();
                    ToolExecutor original = entry.getValue();
                    entry.setValue(new TracingToolExecutor(original, toolName, tracer));
                }

                // ////// LINK SPANS ACROSS CONCURRENT TOOL CALLS
                var underlyingExecutor = context.toolService.executor();
                if (underlyingExecutor != null) {
                    aiServices.executeToolsConcurrently(
                            new OtelContextPassingExecutor(underlyingExecutor));
                }
            }

            // ////// CREATE A SPAN ON SERVICE METHOD INVOKE
            T service = aiServices.build();
            Class<T> serviceInterface = (Class<T>) context.aiServiceClass;
            return TracingProxy.create(serviceInterface, service, tracer);
        } catch (Exception e) {
            log.warn("failed to apply langchain AI services instrumentation", e);
            return aiServices.build();
        }
    }

    /** Instrument langchain openai chat model with braintrust traces */
    public static OpenAiChatModel wrap(
            OpenTelemetry otel, OpenAiChatModel.OpenAiChatModelBuilder builder) {
        return wrap(otel, builder.build());
    }

    private static OpenAiChatModel wrap(OpenTelemetry otel, OpenAiChatModel model) {
        try {
            // Get the internal OpenAiClient from the chat model
            Object internalClient = getPrivateField(model, "client");

            // Get the HttpClient from the internal client
            dev.langchain4j.http.client.HttpClient httpClient =
                    getPrivateField(internalClient, "httpClient");

            if (httpClient instanceof WrappedHttpClient) {
                log.debug("model already instrumented. skipping: {}", httpClient.getClass());
                return model;
            }

            // Wrap the HttpClient with our instrumented version
            dev.langchain4j.http.client.HttpClient wrappedHttpClient =
                    new WrappedHttpClient(otel, httpClient, new Options("openai"));

            setPrivateField(internalClient, "httpClient", wrappedHttpClient);

            return model;
        } catch (Exception e) {
            log.warn("failed to instrument OpenAiChatModel", e);
            return model;
        }
    }

    /** Instrument langchain openai chat model with braintrust traces */
    public static OpenAiStreamingChatModel wrap(
            OpenTelemetry otel, OpenAiStreamingChatModel.OpenAiStreamingChatModelBuilder builder) {
        return wrap(otel, builder.build());
    }

    public static OpenAiStreamingChatModel wrap(
            OpenTelemetry otel, OpenAiStreamingChatModel model) {
        try {
            // Get the internal OpenAiClient from the streaming chat model
            Object internalClient = getPrivateField(model, "client");

            // Get the HttpClient from the internal client
            dev.langchain4j.http.client.HttpClient httpClient =
                    getPrivateField(internalClient, "httpClient");

            if (httpClient instanceof WrappedHttpClient) {
                log.debug("model already instrumented. skipping: {}", httpClient.getClass());
                return model;
            }

            // Wrap the HttpClient with our instrumented version
            dev.langchain4j.http.client.HttpClient wrappedHttpClient =
                    new WrappedHttpClient(otel, httpClient, new Options("openai"));

            setPrivateField(internalClient, "httpClient", wrappedHttpClient);

            return model;
        } catch (Exception e) {
            log.warn("failed to instrument OpenAiStreamingChatModel", e);
            return model;
        }
    }

    public record Options(String providerName) {}

    @SuppressWarnings("unchecked")
    private static <T> T getPrivateField(Object obj, String fieldName)
            throws ReflectiveOperationException {
        Class<?> clazz = obj.getClass();
        while (clazz != null) {
            try {
                java.lang.reflect.Field field = clazz.getDeclaredField(fieldName);
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
                java.lang.reflect.Field field = clazz.getDeclaredField(fieldName);
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
