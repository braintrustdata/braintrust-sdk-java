package dev.braintrust;

import dev.braintrust.api.BraintrustApiClient;
import dev.braintrust.api.BraintrustOpenApiClient;
import dev.braintrust.config.BraintrustConfig;
import dev.braintrust.eval.Dataset;
import dev.braintrust.eval.Eval;
import dev.braintrust.eval.Scorer;
import dev.braintrust.prompt.BraintrustPromptLoader;
import dev.braintrust.trace.BraintrustTracing;
import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.sdk.logs.SdkLoggerProviderBuilder;
import io.opentelemetry.sdk.metrics.SdkMeterProviderBuilder;
import io.opentelemetry.sdk.trace.SdkTracerProviderBuilder;
import java.net.URI;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import lombok.Getter;
import lombok.experimental.Accessors;
import lombok.extern.slf4j.Slf4j;

/**
 * Main entry point for the Braintrust SDK.
 *
 * <p>This class provides access to all Braintrust functionality. Most users will interact with a
 * singleton instance via {@link #get()}, though you can create independent instances if needed.
 *
 * <p>The Braintrust instance also provides methods for enabling Braintrust in open telemetry
 * builders.
 *
 * <p>Additionally, vendor-specific instrumentation or functionality is provided by {@code
 * Braintrust<Vendor Name>}. E.g. {@code BraintrustOpenAI}, {@code BraintrustAnthropic}, etc.
 *
 * @see #get()
 * @see BraintrustConfig
 * @see #openTelemetryCreate()
 * @see #openTelemetryEnable(SdkTracerProviderBuilder, SdkLoggerProviderBuilder,
 *     SdkMeterProviderBuilder)
 */
@Slf4j
public class Braintrust {
    private static final String SDK_VERSION = SDKMain.loadVersionFromProperties();
    private static final AtomicReference<Braintrust> INSTANCE = new AtomicReference<>();

    /**
     * get or create the global braintrust instance. Most users will want to use this method to
     * access the Braintrust SDK.
     */
    public static Braintrust get() {
        var current = INSTANCE.get();
        if (null == current) {
            return get(BraintrustConfig.fromEnvironment());
        } else {
            return current;
        }
    }

    /** get or create the global braintrust instance from the given config */
    public static Braintrust get(BraintrustConfig config) {
        var current = INSTANCE.get();
        if (null == current) {
            return set(of(config));
        } else {
            return current;
        }
    }

    static Braintrust set(Braintrust braintrust) {
        var current = INSTANCE.get();
        if (null == current) {
            var success = INSTANCE.compareAndSet(null, braintrust);
            if (success) {
                log.info("initialized global Braintrust sdk {}", SDK_VERSION);
            } else {
                throw new RuntimeException("set must only be called once");
            }
            return braintrust;
        } else {
            return current;
        }
    }

    /** clear global braintrust instance. Only used for testing */
    static void resetForTest() {
        INSTANCE.set(null);
    }

    /** Create a new Braintrust instance from the given config */
    public static Braintrust of(BraintrustConfig config) {
        BraintrustOpenApiClient apiClient = BraintrustOpenApiClient.of(config);
        BraintrustPromptLoader promptLoader = BraintrustPromptLoader.of(config, apiClient);
        return new Braintrust(config, apiClient, promptLoader);
    }

    @Getter
    @Accessors(fluent = true)
    private final BraintrustConfig config;

    /** Deprecated. Please use openApiClient() instead */
    @Deprecated
    public BraintrustApiClient apiClient() {
        return BraintrustApiClient.of(config);
    }

    @Getter
    @Accessors(fluent = true)
    private final BraintrustOpenApiClient openApiClient;

    @Getter
    @Accessors(fluent = true)
    private final BraintrustPromptLoader promptLoader;

    Braintrust(
            BraintrustConfig config,
            BraintrustOpenApiClient apiClient,
            BraintrustPromptLoader promptLoader) {
        this.config = config;
        this.openApiClient = apiClient;
        this.promptLoader = promptLoader;
    }

    /** URI to the configured braintrust org and project */
    public URI projectUri() {
        return openApiClient.fetchProjectUri();
    }

    /**
     * Quick start method that sets up global OpenTelemetry with this Braintrust. <br>
     * <br>
     * If you're looking for more options for configuring Braintrust/OpenTelemetry, consult the
     * `enable` method.
     */
    public OpenTelemetry openTelemetryCreate() {
        return openTelemetryCreate(true);
    }

    /**
     * Quick start method that sets up OpenTelemetry with this Braintrust. <br>
     * <br>
     * If you're looking for more options for configuring Braintrust and OpenTelemetry, consult the
     * `enable` method.
     */
    public OpenTelemetry openTelemetryCreate(boolean registerGlobal) {
        return BraintrustTracing.of(this.config, registerGlobal);
    }

    /**
     * Add braintrust to existing open telemetry builders <br>
     * <br>
     * This method provides the most options for configuring Braintrust and OpenTelemetry. If you're
     * looking for a more user-friendly setup, consult the `openTelemetryCreate` methods. <br>
     * <br>
     * NOTE: if your otel setup does not have any particular builder, pass an instance of the
     * default provider builder. E.g. `SdkMeterProvider.builder()` <br>
     * <br>
     * NOTE: This method should only be invoked once. Enabling Braintrust multiple times is
     * unsupported and may lead to undesired behavior
     */
    public void openTelemetryEnable(
            @Nonnull SdkTracerProviderBuilder tracerProviderBuilder,
            @Nonnull SdkLoggerProviderBuilder loggerProviderBuilder,
            @Nonnull SdkMeterProviderBuilder meterProviderBuilder) {
        BraintrustTracing.enable(
                this.config, tracerProviderBuilder, loggerProviderBuilder, meterProviderBuilder);
    }

    /** Create a new eval builder */
    public <INPUT, OUTPUT> Eval.Builder<INPUT, OUTPUT> evalBuilder() {
        return (Eval.Builder<INPUT, OUTPUT>)
                Eval.builder().config(this.config).apiClient(this.openApiClient);
    }

    /**
     * Fetch the latest version of a dataset from Braintrust, using the default project from
     * configuration.
     *
     * @deprecated the {@code INPUT} and {@code OUTPUT} type parameters are not applied at runtime:
     *     case values are returned as raw JSON-decoded objects (e.g. {@code LinkedHashMap}), and
     *     accessing them as {@code INPUT}/{@code OUTPUT} will throw {@link ClassCastException}
     *     unless those types are {@code Object} or {@code Map}. Use {@link #fetchDataset(String,
     *     Class, Class)} instead.
     */
    @Deprecated
    public <INPUT, OUTPUT> Dataset<INPUT, OUTPUT> fetchDataset(String datasetName) {
        return fetchDataset(datasetName, null);
    }

    /**
     * Fetch the latest version of a dataset from Braintrust, deserializing each case's {@code
     * input} and {@code expected} values into the given types.
     *
     * <p>Values are deserialized using the SDK's shared Jackson mapper (see {@link
     * dev.braintrust.json.BraintrustJsonMapper}), which may be customized via {@link
     * dev.braintrust.json.BraintrustJsonMapper#configure}. For full control over per-value
     * conversion (custom mappers, fixups, generic types), use {@link #fetchDataset(String,
     * Function, Function)}.
     *
     * <p>The returned dataset preserves experiment-to-dataset linking when used with {@link Eval}:
     * experiments created by {@code Eval.run()} are stamped with this dataset's id and version.
     *
     * @param datasetName the name of the dataset within the configured project
     * @param inputClass the type to deserialize each case's {@code input} into
     * @param outputClass the type to deserialize each case's {@code expected} into
     * @return a typed view of the remote dataset; cases are fetched lazily, and deserialization
     *     errors surface during iteration
     */
    public <INPUT, OUTPUT> Dataset<INPUT, OUTPUT> fetchDataset(
            String datasetName, Class<INPUT> inputClass, Class<OUTPUT> outputClass) {
        return fetchDataset(datasetName, null, inputClass, outputClass);
    }

    /**
     * Fetch a specific version of a dataset from Braintrust, deserializing each case's {@code
     * input} and {@code expected} values into the given types.
     *
     * <p>See {@link #fetchDataset(String, Class, Class)} for deserialization and experiment-linking
     * semantics.
     *
     * @param datasetName the name of the dataset within the configured project
     * @param datasetVersion the dataset version to pin, or null to fetch the latest version upon
     *     every cursor open
     * @param inputClass the type to deserialize each case's {@code input} into
     * @param outputClass the type to deserialize each case's {@code expected} into
     * @return a typed view of the remote dataset; cases are fetched lazily, and deserialization
     *     errors surface during iteration
     */
    public <INPUT, OUTPUT> Dataset<INPUT, OUTPUT> fetchDataset(
            String datasetName,
            @Nullable String datasetVersion,
            Class<INPUT> inputClass,
            Class<OUTPUT> outputClass) {
        return Dataset.fetchFromBraintrust(
                openApiClient,
                resolveProjectName(),
                datasetName,
                datasetVersion,
                inputClass,
                outputClass);
    }

    /**
     * Fetch a specific version of a dataset from Braintrust, using the default project from
     * configuration.
     *
     * @deprecated the {@code INPUT} and {@code OUTPUT} type parameters are not applied at runtime;
     *     see {@link #fetchDataset(String)}. Use {@link #fetchDataset(String, String, Class,
     *     Class)} instead.
     */
    @Deprecated
    public <INPUT, OUTPUT> Dataset<INPUT, OUTPUT> fetchDataset(
            String datasetName, @Nullable String datasetVersion) {
        return Dataset.fetchFromBraintrust(
                openApiClient, resolveProjectName(), datasetName, datasetVersion);
    }

    /**
     * Fetch the latest version of a dataset from Braintrust, converting each case's {@code input}
     * and {@code expected} values with the supplied converter functions.
     *
     * <p>Converters receive the raw JSON-decoded value (typically a {@code Map<String, Object>},
     * {@code List}, {@code String}, {@code Number}, {@code Boolean}, or null) and are responsible
     * for producing the typed value. This gives the caller full control over deserialization — e.g.
     * a custom Jackson {@code ObjectMapper}, generic types, or row fixups:
     *
     * <pre>{@code
     * Dataset<MyInput, MyOutput> ds = braintrust.fetchDataset(
     *         "golden-cases",
     *         raw -> myMapper.convertValue(raw, MyInput.class),
     *         raw -> myMapper.convertValue(raw, MyOutput.class));
     * }</pre>
     *
     * <p>The returned dataset preserves experiment-to-dataset linking when used with {@link Eval}:
     * experiments created by {@code Eval.run()} are stamped with this dataset's id and version.
     *
     * @param datasetName the name of the dataset within the configured project
     * @param inputConverter converts each case's raw {@code input} value; must tolerate null
     * @param outputConverter converts each case's raw {@code expected} value; must tolerate null
     * @return a typed view of the remote dataset; cases are fetched lazily, and converter errors
     *     surface during iteration
     */
    public <INPUT, OUTPUT> Dataset<INPUT, OUTPUT> fetchDataset(
            String datasetName,
            Function<Object, INPUT> inputConverter,
            Function<Object, OUTPUT> outputConverter) {
        return fetchDataset(datasetName, null, inputConverter, outputConverter);
    }

    /**
     * Fetch a specific version of a dataset from Braintrust, converting each case's {@code input}
     * and {@code expected} values with the supplied converter functions.
     *
     * <p>See {@link #fetchDataset(String, Function, Function)} for converter and experiment-linking
     * semantics.
     *
     * @param datasetName the name of the dataset within the configured project
     * @param datasetVersion the dataset version to pin, or null to fetch the latest version upon
     *     every cursor open
     * @param inputConverter converts each case's raw {@code input} value; must tolerate null
     * @param outputConverter converts each case's raw {@code expected} value; must tolerate null
     * @return a typed view of the remote dataset; cases are fetched lazily, and converter errors
     *     surface during iteration
     */
    public <INPUT, OUTPUT> Dataset<INPUT, OUTPUT> fetchDataset(
            String datasetName,
            @Nullable String datasetVersion,
            Function<Object, INPUT> inputConverter,
            Function<Object, OUTPUT> outputConverter) {
        return Dataset.fetchFromBraintrust(
                openApiClient,
                resolveProjectName(),
                datasetName,
                datasetVersion,
                inputConverter,
                outputConverter);
    }

    /**
     * Fetch a scorer from Braintrust by slug, using the default project from configuration.
     *
     * @param scorerSlug the unique slug identifier for the scorer
     * @return a Scorer that invokes the remote function
     */
    public <INPUT, OUTPUT> Scorer<INPUT, OUTPUT> fetchScorer(String scorerSlug) {
        return fetchScorer(scorerSlug, null);
    }

    /**
     * Fetch a scorer from Braintrust by slug, using the default project from configuration.
     *
     * @param scorerSlug the unique slug identifier for the scorer
     * @param version optional version of the scorer to fetch
     * @return a Scorer that invokes the remote function
     */
    public <INPUT, OUTPUT> Scorer<INPUT, OUTPUT> fetchScorer(
            String scorerSlug, @Nullable String version) {
        return Scorer.fetchFromBraintrust(openApiClient, resolveProjectName(), scorerSlug, version);
    }

    /**
     * Fetch a scorer from Braintrust by slug, using the default project from configuration, with
     * custom converters that transform the {@code input}, {@code output}, and {@code expected}
     * scorer argument values into a JSON-serializable form.
     *
     * <p>By default, argument values are serialized with the SDK's shared mapper ({@link
     * dev.braintrust.json.BraintrustJsonMapper}). Use this variant to control serialization, e.g.
     * with a custom {@code ObjectMapper}: {@code fetchScorer(slug, null, myMapper::valueToTree,
     * myMapper::valueToTree)}. Case {@code metadata} and eval {@code parameters} are always
     * serialized with the OpenAPI client's mapper.
     *
     * @param scorerSlug the unique slug identifier for the scorer
     * @param version optional version of the scorer to fetch
     * @param inputConverter converts each case's {@code input} value into a JSON-serializable form
     *     (e.g. a Jackson {@code JsonNode}, {@code Map}, or scalar); never invoked with null
     * @param outputConverter converts the task {@code output} and case {@code expected} values into
     *     a JSON-serializable form; never invoked with null
     * @return a Scorer that invokes the remote function
     */
    public <INPUT, OUTPUT> Scorer<INPUT, OUTPUT> fetchScorer(
            String scorerSlug,
            @Nullable String version,
            Function<INPUT, Object> inputConverter,
            Function<OUTPUT, Object> outputConverter) {
        return Scorer.fetchFromBraintrust(
                openApiClient,
                resolveProjectName(),
                scorerSlug,
                version,
                inputConverter,
                outputConverter);
    }

    /**
     * Fetch a scorer from Braintrust by project name and slug.
     *
     * @param projectName the name of the project containing the scorer
     * @param scorerSlug the unique slug identifier for the scorer
     * @param version optional version of the scorer to fetch
     * @return a Scorer that invokes the remote function
     */
    public <INPUT, OUTPUT> Scorer<INPUT, OUTPUT> fetchScorer(
            String projectName, String scorerSlug, @Nullable String version) {
        return Scorer.fetchFromBraintrust(openApiClient, projectName, scorerSlug, version);
    }

    /**
     * Fetch a scorer from Braintrust by project name and slug, with custom converters that
     * transform the {@code input}, {@code output}, and {@code expected} scorer argument values into
     * a JSON-serializable form. See {@link #fetchScorer(String, String, Function, Function)} for
     * converter semantics.
     *
     * @param projectName the name of the project containing the scorer
     * @param scorerSlug the unique slug identifier for the scorer
     * @param version optional version of the scorer to fetch
     * @param inputConverter converts each case's {@code input} value into a JSON-serializable form;
     *     never invoked with null
     * @param outputConverter converts the task {@code output} and case {@code expected} values into
     *     a JSON-serializable form; never invoked with null
     * @return a Scorer that invokes the remote function
     */
    public <INPUT, OUTPUT> Scorer<INPUT, OUTPUT> fetchScorer(
            String projectName,
            String scorerSlug,
            @Nullable String version,
            Function<INPUT, Object> inputConverter,
            Function<OUTPUT, Object> outputConverter) {
        return Scorer.fetchFromBraintrust(
                openApiClient, projectName, scorerSlug, version, inputConverter, outputConverter);
    }

    /**
     * Resolve the default project name from config. If only a project ID is configured, looks it up
     * via the API. Mirrors the behavior of the old hand-rolled client.
     */
    private String resolveProjectName() {
        return openApiClient
                .fetchOrCreateProject(
                        config.defaultProjectId().orElse(null),
                        config.defaultProjectName().orElse(null))
                .getName();
    }
}
