package dev.braintrust.sdkspecimpl;

import dev.braintrust.TestHarness;
import io.opentelemetry.api.OpenTelemetry;

/**
 * Everything a {@link SpecClient} needs to execute a spec.
 *
 * <p>All accessors used by isolated (child-classloader) clients return JDK or OTel API types only:
 * OTel is force-delegated to the parent loader, so instances are safe to share across the
 * classloader boundary.
 */
public record SpecClientContext(OpenTelemetry otel, TestHarness harness) {

    public SpecClientContext(TestHarness harness) {
        this(harness.openTelemetry(), harness);
    }

    public String openAiBaseUrl() {
        return harness.openAiBaseUrl();
    }

    public String openAiApiKey() {
        return harness.openAiApiKey();
    }

    public String anthropicBaseUrl() {
        return harness.anthropicBaseUrl();
    }

    public String anthropicApiKey() {
        return harness.anthropicApiKey();
    }

    public String googleBaseUrl() {
        return harness.googleBaseUrl();
    }

    public String googleApiKey() {
        return harness.googleApiKey();
    }
}
