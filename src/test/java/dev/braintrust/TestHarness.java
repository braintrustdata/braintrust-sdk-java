package dev.braintrust;

import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.braintrust.api.BraintrustApiClient;
import dev.braintrust.config.BraintrustConfig;
import dev.braintrust.trace.UnitTestShutdownHook;
import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.baggage.propagation.W3CBaggagePropagator;
import io.opentelemetry.api.trace.propagation.W3CTraceContextPropagator;
import io.opentelemetry.context.propagation.ContextPropagators;
import io.opentelemetry.context.propagation.TextMapPropagator;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.logs.SdkLoggerProvider;
import io.opentelemetry.sdk.metrics.SdkMeterProvider;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import javax.annotation.Nonnull;
import lombok.Getter;
import lombok.SneakyThrows;
import lombok.experimental.Accessors;

public class TestHarness {
    private static final VCR vcr;

    static {
        // Collect all API keys that should never appear in recorded cassettes
        List<String> apiKeysToNeverRecord =
                List.of(
                        getEnv("OPENAI_API_KEY", ""),
                        getEnv("ANTHROPIC_API_KEY", ""),
                        getEnv("GOOGLE_API_KEY", getEnv("GEMINI_API_KEY", "")),
                        getEnv("BRAINTRUST_API_KEY", ""));

        vcr =
                new VCR(
                        java.util.Map.of(
                                "https://api.openai.com/v1", "openai",
                                "https://api.anthropic.com", "anthropic",
                                "https://generativelanguage.googleapis.com", "google",
                                "https://api.braintrust.dev", "braintrust"),
                        apiKeysToNeverRecord);
        vcr.start();
        UnitTestShutdownHook.addShutdownHook(1, vcr::stop);
    }

    public static TestHarness setup() {
        var configBuilder =
                BraintrustConfig.builder()
                        .apiUrl(vcr.getUrlForTargetBase("https://api.braintrust.dev"))
                        .defaultProjectName(defaultProjectName());
        if (vcr.getMode() == VCR.VcrMode.REPLAY) {
            // tolerate missing api key in replay mode
            configBuilder.apiKey(
                    getEnv(
                            "BRAINTRUST_API_KEY",
                            "sk-000000000000000000000000000000000000000000000000"));
        }
        return setup(configBuilder.build());
    }

    private static synchronized TestHarness setup(BraintrustConfig config) {
        GlobalOpenTelemetry.resetForTest();
        Braintrust.resetForTest();

        var braintrust = Braintrust.of(config);
        var harness = new TestHarness(braintrust);
        INSTANCE.set(harness);
        GlobalOpenTelemetry.set(harness.openTelemetry());
        return harness;
    }

    @Getter
    @Accessors(fluent = true)
    private static final String defaultProjectId = "6ae68365-7620-4630-921b-bac416634fc8";

    @Getter
    @Accessors(fluent = true)
    private static final String defaultProjectName = "java-unit-test";

    @Getter
    @Accessors(fluent = true)
    private static final String defaultOrgId = "5d7c97d7-fef1-4cb7-bda6-7e3756a0ca8e";

    @Getter
    @Accessors(fluent = true)
    private static final String defaultOrgName = "braintrustdata.com";

    private static final AtomicReference<TestHarness> INSTANCE = new AtomicReference<>();

    @Getter
    @Accessors(fluent = true)
    private final OpenTelemetrySdk openTelemetry;

    @Getter
    @Accessors(fluent = true)
    private final Braintrust braintrust;

    private final @Nonnull UnitTestSpanExporter spanExporter;

    private TestHarness(@Nonnull Braintrust braintrust) {
        this.braintrust = braintrust;
        var tracerBuilder = SdkTracerProvider.builder();
        this.spanExporter = new UnitTestSpanExporter();
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

    public String openAiBaseUrl() {
        return vcr.getUrlForTargetBase("https://api.openai.com/v1");
    }

    public String openAiApiKey() {
        return getEnv("OPENAI_API_KEY", "test-key");
    }

    public String anthropicBaseUrl() {
        return vcr.getUrlForTargetBase("https://api.anthropic.com");
    }

    public String anthropicApiKey() {
        return getEnv("ANTHROPIC_API_KEY", "test-key");
    }

    public String googleBaseUrl() {
        return vcr.getUrlForTargetBase("https://generativelanguage.googleapis.com");
    }

    public String googleApiKey() {
        return getEnv("GOOGLE_API_KEY", getEnv("GEMINI_API_KEY", "test-key"));
    }

    public String braintrustApiBaseUrl() {
        return braintrust.config().apiUrl();
    }

    public String braintrustApiKey() {
        return braintrust.config().apiKey();
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

    /**
     * flush all pending spans and return all spans which have been exported so far
     *
     * <p>repeat the process until the number of exported spans equals or exceeds `minSpanCount`
     */
    @SneakyThrows
    public List<SpanData> awaitExportedSpans(int minSpanCount) {
        return spanExporter.getFinishedSpanItems(minSpanCount);
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

    private static String getEnv(String envarName, String defaultValue) {
        var envar = System.getenv(envarName);
        return envar == null ? defaultValue : envar;
    }
}
