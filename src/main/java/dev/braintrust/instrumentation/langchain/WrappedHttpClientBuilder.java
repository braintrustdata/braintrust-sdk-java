package dev.braintrust.instrumentation.langchain;

import dev.langchain4j.http.client.HttpClient;
import dev.langchain4j.http.client.HttpClientBuilder;
import io.opentelemetry.api.OpenTelemetry;
import java.time.Duration;

class WrappedHttpClientBuilder implements HttpClientBuilder {
    private final OpenTelemetry openTelemetry;
    private final HttpClientBuilder underlying;
    private final BraintrustLangchain.Options options;

    public WrappedHttpClientBuilder(
            OpenTelemetry openTelemetry,
            HttpClientBuilder underlying,
            BraintrustLangchain.Options options) {
        this.openTelemetry = openTelemetry;
        this.underlying = underlying;
        this.options = options;
    }

    @Override
    public Duration connectTimeout() {
        return underlying.connectTimeout();
    }

    @Override
    public HttpClientBuilder connectTimeout(Duration timeout) {
        underlying.connectTimeout(timeout);
        return this;
    }

    @Override
    public Duration readTimeout() {
        return underlying.readTimeout();
    }

    @Override
    public HttpClientBuilder readTimeout(Duration timeout) {
        underlying.readTimeout(timeout);
        return this;
    }

    @Override
    public HttpClient build() {
        return new WrappedHttpClient(openTelemetry, underlying.build(), options);
    }
}
