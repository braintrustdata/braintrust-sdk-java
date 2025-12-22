package dev.braintrust.instrumentation.langchain;

import dev.langchain4j.http.client.HttpClientBuilder;
import io.opentelemetry.api.OpenTelemetry;

/** Braintrust LangChain4j client instrumentation. */
public final class BraintrustLangchain {
    public static HttpClientBuilder wrap(OpenTelemetry otel, HttpClientBuilder builder) {
        return new WrappedHttpClientBuilder(otel, builder);
    }
}
