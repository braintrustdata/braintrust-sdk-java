package dev.braintrust;

import java.net.URI;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.client.config.ClientOverrideConfiguration;
import software.amazon.awssdk.core.interceptor.ExecutionAttributes;
import software.amazon.awssdk.core.interceptor.ExecutionInterceptor;
import software.amazon.awssdk.http.Protocol;
import software.amazon.awssdk.http.SdkHttpRequest;
import software.amazon.awssdk.http.nio.netty.NettyNioAsyncHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.bedrockruntime.BedrockRuntimeAsyncClient;
import software.amazon.awssdk.services.bedrockruntime.BedrockRuntimeAsyncClientBuilder;
import software.amazon.awssdk.services.bedrockruntime.BedrockRuntimeClient;
import software.amazon.awssdk.services.bedrockruntime.BedrockRuntimeClientBuilder;

/**
 * Shared test utilities for constructing AWS Bedrock Runtime client builders wired to the test
 * harness (WireMock endpoint override, SigV4 host rewrite, replay credentials).
 */
public class Bedrock30TestUtils {

    public static final String BEDROCK_REGION = "us-east-1";
    public static final String BEDROCK_REAL_HOST =
            "bedrock-runtime." + BEDROCK_REGION + ".amazonaws.com";

    private static final StaticCredentialsProvider REPLAY_CREDS =
            StaticCredentialsProvider.create(AwsBasicCredentials.create("fake-key", "fake-secret"));

    private final TestHarness testHarness;

    public Bedrock30TestUtils(TestHarness testHarness) {
        this.testHarness = testHarness;
    }

    /**
     * Returns a {@link BedrockRuntimeClientBuilder} pointed at the test harness WireMock endpoint,
     * with the SigV4 host-rewrite interceptor applied and fake credentials in replay mode.
     */
    public BedrockRuntimeClientBuilder syncClientBuilder() {
        var builder =
                BedrockRuntimeClient.builder()
                        .overrideConfiguration(
                                ClientOverrideConfiguration.builder()
                                        .addExecutionInterceptor(
                                                new HostRewriteInterceptor(BEDROCK_REAL_HOST))
                                        .build())
                        .region(Region.of(BEDROCK_REGION))
                        .endpointOverride(URI.create(testHarness.bedrockBaseUrl(BEDROCK_REGION)));

        if (TestHarness.getVcrMode() == VCR.VcrMode.REPLAY) {
            builder.credentialsProvider(REPLAY_CREDS);
        }

        return builder;
    }

    /**
     * Returns a {@link BedrockRuntimeAsyncClientBuilder} pointed at the test harness WireMock
     * endpoint, with the SigV4 host-rewrite interceptor, HTTP/1.1 forced (so WireMock can
     * proxy/replay the event-stream), and fake credentials in replay mode.
     */
    public BedrockRuntimeAsyncClientBuilder asyncClientBuilder() {
        var builder =
                BedrockRuntimeAsyncClient.builder()
                        .overrideConfiguration(
                                ClientOverrideConfiguration.builder()
                                        .addExecutionInterceptor(
                                                new HostRewriteInterceptor(BEDROCK_REAL_HOST))
                                        .build())
                        .region(Region.of(BEDROCK_REGION))
                        .endpointOverride(URI.create(testHarness.bedrockBaseUrl(BEDROCK_REGION)))
                        // Force HTTP/1.1 so WireMock can proxy/replay the event-stream
                        .httpClientBuilder(
                                NettyNioAsyncHttpClient.builder().protocol(Protocol.HTTP1_1));

        if (TestHarness.getVcrMode() == VCR.VcrMode.REPLAY) {
            builder.credentialsProvider(REPLAY_CREDS);
        }

        return builder;
    }

    /**
     * Rewrites the {@code Host} header to the real AWS hostname before SigV4 signing, so that
     * signatures are valid even when the request is sent to the local WireMock proxy via {@code
     * endpointOverride}.
     */
    public static class HostRewriteInterceptor implements ExecutionInterceptor {
        private final String realHost;

        public HostRewriteInterceptor(String realHost) {
            this.realHost = realHost;
        }

        @Override
        public SdkHttpRequest modifyHttpRequest(
                software.amazon.awssdk.core.interceptor.Context.ModifyHttpRequest context,
                ExecutionAttributes executionAttributes) {
            return context.httpRequest().toBuilder().putHeader("Host", realHost).build();
        }
    }
}
