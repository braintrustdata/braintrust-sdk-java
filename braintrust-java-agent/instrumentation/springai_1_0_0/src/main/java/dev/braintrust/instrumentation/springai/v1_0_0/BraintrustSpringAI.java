package dev.braintrust.instrumentation.springai.v1_0_0;

import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.trace.Tracer;
import java.io.IOException;
import java.lang.reflect.Field;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.observation.ChatModelObservationConvention;
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

    /**
     * Wraps a Spring AI {@link ChatModel} so that every {@code call()} invocation is traced as a
     * Braintrust LLM span.
     */
    public static ChatModel wrap(OpenTelemetry openTelemetry, ChatModel chatModel) {
        try {
            if (chatModel instanceof OpenAiChatModel) {
                return instrumentOpenAiChatModel(openTelemetry, (OpenAiChatModel) chatModel);
            }
        } catch (Exception e) {
            log.error("failed to apply Spring AI instrumentation", e);
        }
        return chatModel;
    }

    private static OpenAiChatModel instrumentOpenAiChatModel(
            OpenTelemetry openTelemetry, OpenAiChatModel chatModel)
            throws ReflectiveOperationException {
        OpenAiApi openAiApi = getField(chatModel, "openAiApi");
        RestClient restClient = getField(openAiApi, "restClient");

        if (restClient == null || hasTracingInterceptor(restClient)) {
            // Already wrapped or nothing to wrap
            return chatModel;
        }

        Tracer tracer = openTelemetry.getTracer("braintrust-java");
        RestClient.Builder wrappedRestClientBuilder =
                restClient.mutate().requestInterceptor(new TracingInterceptor(tracer));
        OpenAiApi wrappedOpenAiApi =
                openAiApi.mutate().restClientBuilder(wrappedRestClientBuilder).build();
        OpenAiChatModel wrappedChatModel = chatModel.mutate().openAiApi(wrappedOpenAiApi).build();

        ChatModelObservationConvention observationConvention =
                getField(chatModel, "observationConvention");
        if (observationConvention != null) {
            wrappedChatModel.setObservationConvention(observationConvention);
        }

        return wrappedChatModel;
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

    private BraintrustSpringAI() {}
}
