package dev.braintrust.config;

import dev.braintrust.api.BraintrustApiClient;
import java.net.URI;
import java.net.URISyntaxException;
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

    /** fetch all project info and IDs from the braintrust api */
    public URI fetchProjectURI() {
        return fetchProjectURI(BraintrustApiClient.of(this));
    }

    URI fetchProjectURI(BraintrustApiClient client) {
        try {
            var orgAndProject = client.getProjectAndOrgInfo().orElseThrow();
            var baseURI = new URI(appUrl());
            var path =
                    "/app/%s/p/%s"
                            .formatted(
                                    orgAndProject.orgInfo().name(), orgAndProject.project().name());
            return new URI(
                    baseURI.getScheme(),
                    baseURI.getUserInfo(),
                    baseURI.getHost(),
                    baseURI.getPort(),
                    path,
                    null,
                    null);
        } catch (URISyntaxException e) {
            throw new RuntimeException(e);
        }
    }
}
