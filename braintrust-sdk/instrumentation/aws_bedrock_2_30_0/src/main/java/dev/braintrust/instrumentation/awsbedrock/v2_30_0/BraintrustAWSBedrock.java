package dev.braintrust.instrumentation.awsbedrock.v2_30_0;

import io.opentelemetry.api.OpenTelemetry;
import lombok.extern.slf4j.Slf4j;
import software.amazon.awssdk.core.client.config.ClientOverrideConfiguration;
import software.amazon.awssdk.services.bedrockruntime.BedrockRuntimeAsyncClientBuilder;
import software.amazon.awssdk.services.bedrockruntime.BedrockRuntimeClientBuilder;

/** Braintrust instrumentation for the AWS Bedrock Runtime client. */
@Slf4j
public class BraintrustAWSBedrock {

    /**
     * Wraps a {@link BedrockRuntimeClientBuilder} so that every {@code converse} call made through
     * the resulting client is traced via OpenTelemetry.
     *
     * <p>Call this method after applying all custom builder settings
     *
     * @param openTelemetry the OpenTelemetry instance to use for tracing
     * @param builder the client builder to instrument
     * @return the same builder (for fluent chaining)
     */
    public static BedrockRuntimeClientBuilder wrap(
            OpenTelemetry openTelemetry, BedrockRuntimeClientBuilder builder) {
        try {
            // Read existing config so we don't clobber user-registered interceptors
            ClientOverrideConfiguration existing = builder.overrideConfiguration();
            ClientOverrideConfiguration.Builder configBuilder = existing.toBuilder();
            for (var interceptor : configBuilder.executionInterceptors()) {
                if (interceptor instanceof BraintrustBedrockInterceptor) {
                    log.info("builder already wrapped. Skipping");
                    return builder;
                }
            }
            configBuilder.addExecutionInterceptor(new BraintrustBedrockInterceptor(openTelemetry));
            builder.overrideConfiguration(configBuilder.build());
        } catch (Exception e) {
            log.warn("Failed to apply Bedrock instrumentation", e);
        }
        return builder;
    }

    /**
     * Wraps a {@link BedrockRuntimeAsyncClientBuilder} so that every {@code converseStream} call
     * made through the resulting client is traced via OpenTelemetry.
     *
     * <p>Call this method after applying all custom builder settings.
     *
     * @param openTelemetry the OpenTelemetry instance to use for tracing
     * @param builder the async client builder to instrument
     * @return the same builder (for fluent chaining)
     */
    public static BedrockRuntimeAsyncClientBuilder wrap(
            OpenTelemetry openTelemetry, BedrockRuntimeAsyncClientBuilder builder) {
        try {
            ClientOverrideConfiguration existing = builder.overrideConfiguration();
            ClientOverrideConfiguration.Builder configBuilder = existing.toBuilder();
            for (var interceptor : configBuilder.executionInterceptors()) {
                if (interceptor instanceof BraintrustBedrockInterceptor) {
                    log.info("async builder already wrapped. Skipping");
                    return builder;
                }
            }
            configBuilder.addExecutionInterceptor(new BraintrustBedrockInterceptor(openTelemetry));
            builder.overrideConfiguration(configBuilder.build());
        } catch (Exception e) {
            log.warn("Failed to apply async Bedrock instrumentation", e);
        }
        return builder;
    }
}
