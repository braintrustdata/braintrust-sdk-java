package dev.braintrust.instrumentation.genai;

import com.google.genai.Client;
import io.opentelemetry.api.OpenTelemetry;

/** Braintrust Google GenAI client instrumentation. */
public class BraintrustGenAI {
    /**
     * Instrument Google GenAI Client with Braintrust traces.
     *
     * <p>This wraps the client's internal HTTP layer to capture all API calls with OpenTelemetry
     * spans.
     *
     * @param openTelemetry the OpenTelemetry instance
     * @param genAIClientBuilder the Gemini client builder
     * @return an instrumented Gemini client
     */
    public static Client wrap(OpenTelemetry openTelemetry, Client.Builder genAIClientBuilder) {
        return dev.braintrust.instrumentation.genai.v1_18_0.BraintrustGenAI.wrap(
                openTelemetry, genAIClientBuilder.build());
    }
}
