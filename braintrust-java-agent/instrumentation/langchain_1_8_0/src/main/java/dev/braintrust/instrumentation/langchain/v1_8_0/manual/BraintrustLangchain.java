package dev.braintrust.instrumentation.langchain.v1_8_0.manual;

import dev.langchain4j.model.openai.OpenAiChatModel;
import dev.langchain4j.model.openai.OpenAiStreamingChatModel;
import dev.langchain4j.service.AiServiceContext;
import dev.langchain4j.service.AiServices;
import dev.langchain4j.service.tool.ToolExecutor;
import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.trace.Tracer;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;

/** Braintrust LangChain4j client instrumentation for the auto-instrumentation agent. */
@Slf4j
public final class BraintrustLangchain {

    private static final String INSTRUMENTATION_NAME = "braintrust-langchain4j";

    /**
     * Wrap an already-built OpenAiChatModel by replacing its internal HTTP client with a tracing
     * wrapper.
     */
    public static OpenAiChatModel wrapChatModel(
            OpenTelemetry openTelemetry, OpenAiChatModel model) {
        try {
            Object internalClient = getPrivateField(model, "client");
            dev.langchain4j.http.client.HttpClient httpClient =
                    getPrivateField(internalClient, "httpClient");

            if (httpClient instanceof WrappedHttpClient) {
                log.debug("model already instrumented, skipping");
                return model;
            }

            dev.langchain4j.http.client.HttpClient wrappedHttpClient =
                    new WrappedHttpClient(openTelemetry, httpClient, new Options("openai"));
            setPrivateField(internalClient, "httpClient", wrappedHttpClient);
            return model;
        } catch (Exception e) {
            log.warn("failed to instrument OpenAiChatModel", e);
            return model;
        }
    }

    /**
     * Wrap an already-built OpenAiStreamingChatModel by replacing its internal HTTP client with a
     * tracing wrapper.
     */
    public static OpenAiStreamingChatModel wrapStreamingChatModel(
            OpenTelemetry openTelemetry, OpenAiStreamingChatModel model) {
        try {
            Object internalClient = getPrivateField(model, "client");
            dev.langchain4j.http.client.HttpClient httpClient =
                    getPrivateField(internalClient, "httpClient");

            if (httpClient instanceof WrappedHttpClient) {
                log.debug("model already instrumented, skipping");
                return model;
            }

            dev.langchain4j.http.client.HttpClient wrappedHttpClient =
                    new WrappedHttpClient(openTelemetry, httpClient, new Options("openai"));
            setPrivateField(internalClient, "httpClient", wrappedHttpClient);
            return model;
        } catch (Exception e) {
            log.warn("failed to instrument OpenAiStreamingChatModel", e);
            return model;
        }
    }

    /**
     * Wrap an already-built AiService with TracingProxy and TracingToolExecutors. Called from
     * AiServices.build() advice — the service is already built, so we wrap it with tracing proxy
     * and instrument tool executors.
     */
    @SuppressWarnings("unchecked")
    public static <T> T wrapAiService(
            OpenTelemetry openTelemetry, AiServices<?> aiServices, Object builtService) {
        try {
            AiServiceContext context = getPrivateField(aiServices, "context");
            Tracer tracer = openTelemetry.getTracer(INSTRUMENTATION_NAME);

            // Wrap tool executors with tracing
            if (context.toolService != null) {
                for (Map.Entry<String, ToolExecutor> entry :
                        context.toolService.toolExecutors().entrySet()) {
                    String toolName = entry.getKey();
                    ToolExecutor original = entry.getValue();
                    if (!(original instanceof TracingToolExecutor)) {
                        entry.setValue(new TracingToolExecutor(original, toolName, tracer));
                    }
                }

                // Link spans across concurrent tool calls
                var underlyingExecutor = context.toolService.executor();
                if (underlyingExecutor != null) {
                    // Replace the executor with one that passes OTel context
                    try {
                        setPrivateField(
                                context.toolService,
                                "executor",
                                new OtelContextPassingExecutor(underlyingExecutor));
                    } catch (Exception e) {
                        log.debug("Could not replace tool executor for context propagation", e);
                    }
                }
            }

            // Wrap service with tracing proxy
            Class<T> serviceInterface = (Class<T>) context.aiServiceClass;
            return TracingProxy.create(serviceInterface, (T) builtService, tracer);
        } catch (Exception e) {
            log.warn("failed to apply langchain AI services instrumentation", e);
            return (T) builtService;
        }
    }

    public record Options(String providerName) {}

    @SuppressWarnings("unchecked")
    static <T> T getPrivateField(Object obj, String fieldName) throws ReflectiveOperationException {
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

    static void setPrivateField(Object obj, String fieldName, Object value)
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
