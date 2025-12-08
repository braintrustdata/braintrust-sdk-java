package dev.braintrust;

import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.braintrust.api.BraintrustApiClient;
import dev.braintrust.config.BraintrustConfig;
import dev.braintrust.prompt.BraintrustPromptLoader;
import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.baggage.propagation.W3CBaggagePropagator;
import io.opentelemetry.api.trace.propagation.W3CTraceContextPropagator;
import io.opentelemetry.context.propagation.ContextPropagators;
import io.opentelemetry.context.propagation.TextMapPropagator;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.logs.SdkLoggerProvider;
import io.opentelemetry.sdk.metrics.SdkMeterProvider;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import javax.annotation.Nonnull;
import lombok.Getter;
import lombok.experimental.Accessors;

public class TestHarness {

    public static TestHarness setup() {
        return setup(createTestConfig());
    }

    public static synchronized TestHarness setup(BraintrustConfig config) {
        GlobalOpenTelemetry.resetForTest();
        Braintrust.resetForTest();

        var apiClient = createApiClient();
        Braintrust braintrust =
                Braintrust.set(
                        new Braintrust(
                                config,
                                createApiClient(),
                                BraintrustPromptLoader.of(config, apiClient)));
        var harness = new TestHarness(braintrust);
        INSTANCE.set(harness);
        GlobalOpenTelemetry.set(harness.openTelemetry());
        return harness;
    }

    @Getter
    @Accessors(fluent = true)
    private static final String defaultProjectId = "01234";

    @Getter
    @Accessors(fluent = true)
    private static final String defaultProjectName = "Unit Test";

    @Getter
    @Accessors(fluent = true)
    private static final String defaultOrgId = "567890";

    @Getter
    @Accessors(fluent = true)
    private static final String defaultOrgName = "Test Org";

    private static final AtomicReference<TestHarness> INSTANCE = new AtomicReference<>();

    @Getter
    @Accessors(fluent = true)
    private final OpenTelemetrySdk openTelemetry;

    @Getter
    @Accessors(fluent = true)
    private final Braintrust braintrust;

    private final @Nonnull InMemorySpanExporter spanExporter;

    private TestHarness(@Nonnull Braintrust braintrust) {
        this.braintrust = braintrust;

        var tracerBuilder = SdkTracerProvider.builder();
        this.spanExporter = InMemorySpanExporter.create();
        var loggerBuilder = SdkLoggerProvider.builder();
        var meterBuilder = SdkMeterProvider.builder();
        braintrust.openTelemetryEnable(tracerBuilder, loggerBuilder, meterBuilder);
        // Add the in-memory span exporter for testing
        tracerBuilder.addSpanProcessor(SimpleSpanProcessor.create(this.spanExporter));
        var contextPropagator =
                ContextPropagators.create(
                        TextMapPropagator.composite(
                                W3CTraceContextPropagator.getInstance(),
                                W3CBaggagePropagator.getInstance()));
        var openTelemetry =
                OpenTelemetrySdk.builder()
                        .setTracerProvider(tracerBuilder.build())
                        .setLoggerProvider(loggerBuilder.build())
                        .setMeterProvider(meterBuilder.build())
                        .setPropagators(contextPropagator)
                        .build();
        this.openTelemetry = openTelemetry;
    }

    /** flush all pending spans and return all spans which have been exported so far */
    public List<SpanData> awaitExportedSpans() {
        assertTrue(
                openTelemetry
                        .getSdkTracerProvider()
                        .forceFlush()
                        .join(10, TimeUnit.SECONDS)
                        .isSuccess());
        return spanExporter.getFinishedSpanItems();
    }

    private static BraintrustApiClient.InMemoryImpl createApiClient() {
        var orgInfo =
                new dev.braintrust.api.BraintrustApiClient.OrganizationInfo(
                        defaultOrgId, defaultOrgName);
        var project =
                new dev.braintrust.api.BraintrustApiClient.Project(
                        defaultProjectId,
                        defaultProjectName,
                        "unit_test_org_123",
                        "2023-01-01T00:00:00Z",
                        "2023-01-01T00:00:00Z");
        var orgAndProjectInfo =
                new dev.braintrust.api.BraintrustApiClient.OrganizationAndProjectInfo(
                        orgInfo, project);
        return new dev.braintrust.api.BraintrustApiClient.InMemoryImpl(orgAndProjectInfo);
    }

    public static BraintrustConfig createTestConfig() {
        return BraintrustConfig.of(
                "BRAINTRUST_API_KEY", "foobar",
                "BRAINTRUST_JAVA_EXPORT_SPANS_IN_MEMORY_FOR_UNIT_TEST", "true",
                // NOTE: testhost is not real, just a placeholder value
                "BRAINTRUST_API_URL", "https://testhost:8000",
                "BRAINTRUST_APP_URL", "https://testhost:3000",
                "BRAINTRUST_DEFAULT_PROJECT_NAME", defaultProjectName());
    }
}
