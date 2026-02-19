package dev.braintrust.agent.internal;

import java.time.Duration;
import java.util.Optional;

/**
 * Lightweight agent configuration that reads from environment variables and system properties.
 *
 * <p>This is a simplified version of the SDK's {@code BraintrustConfig} that avoids dependencies on
 * Lombok, the full SDK, etc. — keeping the agent-internal module self-contained.
 */
final class AgentConfig {

    private final String apiKey;
    private final String apiUrl;
    private final String tracesPath;
    private final Optional<String> defaultProjectId;
    private final Optional<String> defaultProjectName;
    private final Duration requestTimeout;
    private final boolean debug;

    private AgentConfig() {
        this.apiKey = requireEnv("BRAINTRUST_API_KEY");
        this.apiUrl = env("BRAINTRUST_API_URL", "https://api.braintrust.dev");
        this.tracesPath = env("BRAINTRUST_TRACES_PATH", "/otel/v1/traces");
        this.defaultProjectId = optionalEnv("BRAINTRUST_DEFAULT_PROJECT_ID");
        this.defaultProjectName =
                Optional.of(env("BRAINTRUST_DEFAULT_PROJECT_NAME", "default-java-project"));
        this.requestTimeout =
                Duration.ofSeconds(Long.parseLong(env("BRAINTRUST_REQUEST_TIMEOUT", "30")));
        this.debug = Boolean.parseBoolean(env("BRAINTRUST_DEBUG", "false"));
    }

    static AgentConfig fromEnvironment() {
        return new AgentConfig();
    }

    String apiKey() {
        return apiKey;
    }

    String apiUrl() {
        return apiUrl;
    }

    String tracesEndpoint() {
        return apiUrl + tracesPath;
    }

    Duration requestTimeout() {
        return requestTimeout;
    }

    boolean debug() {
        return debug;
    }

    /**
     * Returns the parent routing value for Braintrust's OTLP ingestion endpoint.
     *
     * <p>Format: {@code project_id:<id>} or {@code project_name:<name>}. The ingestion endpoint
     * uses the {@code x-bt-parent} header to route spans to the correct project/experiment.
     */
    Optional<String> getBraintrustParentValue() {
        if (defaultProjectId.isPresent()) {
            return Optional.of("project_id:" + defaultProjectId.get());
        } else if (defaultProjectName.isPresent()) {
            return Optional.of("project_name:" + defaultProjectName.get());
        }
        return Optional.empty();
    }

    /** Reads an environment variable, falling back to a system property, then to the default. */
    private static String env(String key, String defaultValue) {
        String value = System.getenv(key);
        if (value != null && !value.isEmpty()) {
            return value;
        }
        // Also check system property with dot notation (e.g., braintrust.api.key)
        String propKey = key.toLowerCase().replace('_', '.');
        value = System.getProperty(propKey);
        if (value != null && !value.isEmpty()) {
            return value;
        }
        return defaultValue;
    }

    private static Optional<String> optionalEnv(String key) {
        String value = env(key, null);
        return Optional.ofNullable(value);
    }

    private static String requireEnv(String key) {
        String value = env(key, null);
        if (value == null || value.isEmpty()) {
            throw new IllegalStateException(
                    "Required configuration "
                            + key
                            + " is not set. "
                            + "Set the environment variable or system property.");
        }
        return value;
    }
}
