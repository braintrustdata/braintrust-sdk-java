package dev.braintrust.config;

import dev.braintrust.Braintrust;
import dev.braintrust.BraintrustUtils;
import dev.braintrust.api.BraintrustApiClient;
import java.net.URI;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import lombok.Getter;
import lombok.experimental.Accessors;

/**
 * Configuration for Braintrust SDK with sane defaults.
 *
 * <p>Most SDK users will want to use envars to configure all Braintrust settings.
 *
 * <p>However, it's also possible to override any envar during config construction.
 */
@Getter
@Accessors(fluent = true)
public final class BraintrustConfig extends BaseConfig {
    private final String apiKey = getRequiredConfig("BRAINTRUST_API_KEY");
    private final String apiUrl = getConfig("BRAINTRUST_API_URL", "https://api.braintrust.dev");
    private final String appUrl = getConfig("BRAINTRUST_APP_URL", "https://www.braintrust.dev");
    private final String tracesPath = getConfig("BRAINTRUST_TRACES_PATH", "/otel/v1/traces");
    private final String logsPath = getConfig("BRAINTRUST_LOGS_PATH", "/otel/v1/logs");
    private final Optional<String> defaultProjectId =
            Optional.ofNullable(getConfig("BRAINTRUST_DEFAULT_PROJECT_ID", null, String.class));
    private final Optional<String> defaultProjectName =
            Optional.of(getConfig("BRAINTRUST_DEFAULT_PROJECT_NAME", "default-java-project"));
    private final boolean enableTraceConsoleLog =
            getConfig("BRAINTRUST_ENABLE_TRACE_CONSOLE_LOG", false);
    private final boolean debug = getConfig("BRAINTRUST_DEBUG", false);
    private final boolean experimentalOtelLogs = getConfig("BRAINTRUST_X_OTEL_LOGS", false);
    private final Duration requestTimeout =
            Duration.ofSeconds(getConfig("BRAINTRUST_REQUEST_TIMEOUT", 30));

    /** Setting for unit testing. Do not use in production. */
    private final boolean exportSpansInMemoryForUnitTest =
            getConfig("BRAINTRUST_JAVA_EXPORT_SPANS_IN_MEMORY_FOR_UNIT_TEST", false);

    public static BraintrustConfig fromEnvironment() {
        return of();
    }

    public static BraintrustConfig of(String... envOverrides) {
        if (envOverrides.length % 2 != 0) {
            throw new RuntimeException(
                    "config overrides require key-value pairs. Found dangling key: %s"
                            .formatted(envOverrides[envOverrides.length - 1]));
        }
        var overridesMap = new HashMap<String, String>();
        for (int i = 0; i < envOverrides.length - 1; i = i + 2) {
            overridesMap.put(envOverrides[i], envOverrides[i + 1]);
        }
        return new BraintrustConfig(overridesMap);
    }

    private BraintrustConfig(Map<String, String> envOverrides) {
        super(envOverrides);
        if (defaultProjectId.isEmpty() && defaultProjectName.isEmpty()) {
            // should never happen
            throw new RuntimeException("A project name or ID is required.");
        }
    }

    /**
     * The parent attribute tells braintrust where to send otel data <br>
     * <br>
     * The otel ingestion endpoint looks for (a) braintrust.parent =
     * project_id|project_name|experiment_id:value otel attribute and routes accordingly <br>
     * <br>
     * (b) if a span has no parent marked explicitly, it will look to see if there's an x-bt-parent
     * http header (with the same format marked above e.g. project_name:andrew) that parent will
     * apply to all spans in a request that don't have one <br>
     * <br>
     * If neither (a) nor (b) exists, the data is dropped
     */
    public Optional<String> getBraintrustParentValue() {
        if (defaultProjectId.isPresent()) {
            return Optional.of("project_id:" + defaultProjectId.orElseThrow());
        } else if (this.defaultProjectName.isPresent()) {
            return Optional.of("project_name:" + defaultProjectName.orElseThrow());
        } else {
            return Optional.empty();
        }
    }

    /** Deprecated. Please use {@link Braintrust#projectUri()} instead */
    @Deprecated
    public URI fetchProjectURI() {
        var client = BraintrustApiClient.of(this);
        var orgAndProject = client.getProjectAndOrgInfo().orElseThrow();
        return BraintrustUtils.createProjectURI(appUrl(), orgAndProject);
    }

    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private final Map<String, String> envOverrides = new HashMap<>();

        public Builder apiKey(String value) {
            envOverrides.put("BRAINTRUST_API_KEY", value);
            return this;
        }

        public Builder apiUrl(String value) {
            envOverrides.put("BRAINTRUST_API_URL", value);
            return this;
        }

        public Builder appUrl(String value) {
            envOverrides.put("BRAINTRUST_APP_URL", value);
            return this;
        }

        public Builder tracesPath(String value) {
            envOverrides.put("BRAINTRUST_TRACES_PATH", value);
            return this;
        }

        public Builder logsPath(String value) {
            envOverrides.put("BRAINTRUST_LOGS_PATH", value);
            return this;
        }

        public Builder defaultProjectId(String value) {
            if (value != null) {
                envOverrides.put("BRAINTRUST_DEFAULT_PROJECT_ID", value);
            } else {
                envOverrides.put("BRAINTRUST_DEFAULT_PROJECT_ID", NULL_OVERRIDE);
            }
            return this;
        }

        public Builder defaultProjectName(String value) {
            if (value != null) {
                envOverrides.put("BRAINTRUST_DEFAULT_PROJECT_NAME", value);
            } else {
                envOverrides.put("BRAINTRUST_DEFAULT_PROJECT_NAME", NULL_OVERRIDE);
            }
            return this;
        }

        public Builder enableTraceConsoleLog(boolean value) {
            envOverrides.put("BRAINTRUST_ENABLE_TRACE_CONSOLE_LOG", String.valueOf(value));
            return this;
        }

        public Builder debug(boolean value) {
            envOverrides.put("BRAINTRUST_DEBUG", String.valueOf(value));
            return this;
        }

        public Builder requestTimeout(Duration value) {
            envOverrides.put("BRAINTRUST_REQUEST_TIMEOUT", String.valueOf(value.getSeconds()));
            return this;
        }

        // hiding visibility. only used for testing
        Builder experimentalOtelLogs(boolean value) {
            envOverrides.put("BRAINTRUST_X_OTEL_LOGS", String.valueOf(value));
            return this;
        }

        // hiding visibility. only used for testing
        Builder exportSpansInMemoryForUnitTest(boolean value) {
            envOverrides.put(
                    "BRAINTRUST_JAVA_EXPORT_SPANS_IN_MEMORY_FOR_UNIT_TEST", String.valueOf(value));
            return this;
        }

        public BraintrustConfig build() {
            return new BraintrustConfig(envOverrides);
        }
    }
}
