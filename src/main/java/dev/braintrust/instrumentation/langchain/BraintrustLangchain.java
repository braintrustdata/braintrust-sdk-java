package dev.braintrust.instrumentation.langchain;

import dev.langchain4j.http.client.HttpClientBuilder;
import dev.langchain4j.http.client.HttpClientBuilderLoader;
import dev.langchain4j.model.openai.OpenAiChatModel;
import dev.langchain4j.model.openai.OpenAiStreamingChatModel;
import io.opentelemetry.api.OpenTelemetry;
import lombok.extern.slf4j.Slf4j;

/** Braintrust LangChain4j client instrumentation. */
@Slf4j
public final class BraintrustLangchain {
    /** Instrument langchain openai chat model with braintrust traces */
    public static OpenAiChatModel wrap(
            OpenTelemetry otel, OpenAiChatModel.OpenAiChatModelBuilder builder) {
        try {
            HttpClientBuilder underlyingHttpClient = getPrivateField(builder, "httpClientBuilder");
            if (underlyingHttpClient == null) {
                underlyingHttpClient = HttpClientBuilderLoader.loadHttpClientBuilder();
            }
            HttpClientBuilder wrappedHttpClient =
                    wrap(otel, underlyingHttpClient, new Options("openai"));
            return builder.httpClientBuilder(wrappedHttpClient).build();
        } catch (Exception e) {
            log.warn(
                    "Braintrust instrumentation could not be applied to OpenAiChatModel builder",
                    e);
            return builder.build();
        }
    }

    /** Instrument langchain openai chat model with braintrust traces */
    public static OpenAiStreamingChatModel wrap(
            OpenTelemetry otel, OpenAiStreamingChatModel.OpenAiStreamingChatModelBuilder builder) {
        try {
            HttpClientBuilder underlyingHttpClient = getPrivateField(builder, "httpClientBuilder");
            if (underlyingHttpClient == null) {
                underlyingHttpClient = HttpClientBuilderLoader.loadHttpClientBuilder();
            }
            HttpClientBuilder wrappedHttpClient =
                    wrap(otel, underlyingHttpClient, new Options("openai"));
            return builder.httpClientBuilder(wrappedHttpClient).build();
        } catch (Exception e) {
            log.warn(
                    "Braintrust instrumentation could not be applied to OpenAiStreamingChatModel"
                            + " builder",
                    e);
            return builder.build();
        }
    }

    private static HttpClientBuilder wrap(
            OpenTelemetry otel, HttpClientBuilder builder, Options options) {
        return new WrappedHttpClientBuilder(otel, builder, options);
    }

    public record Options(String providerName) {}

    @SuppressWarnings("unchecked")
    private static <T> T getPrivateField(Object obj, String fieldName)
            throws ReflectiveOperationException {
        java.lang.reflect.Field field = obj.getClass().getDeclaredField(fieldName);
        field.setAccessible(true);
        return (T) field.get(obj);
    }
}
