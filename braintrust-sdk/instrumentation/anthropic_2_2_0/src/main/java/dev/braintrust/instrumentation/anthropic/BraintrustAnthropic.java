package dev.braintrust.instrumentation.anthropic;

import com.anthropic.client.AnthropicClient;
import io.opentelemetry.api.OpenTelemetry;

/** Braintrust Anthropic client instrumentation. */
public final class BraintrustAnthropic {

    /** Instrument Anthropic client with Braintrust traces. */
    public static AnthropicClient wrap(OpenTelemetry openTelemetry, AnthropicClient client) {
        return dev.braintrust.instrumentation.anthropic.v2_2_0.BraintrustAnthropic.wrap(
                openTelemetry, client);
    }
}
