package dev.braintrust.instrumentation.genai;

import com.google.genai.BraintrustInstrumentation;
import com.google.genai.Client;
import io.opentelemetry.api.OpenTelemetry;
import lombok.extern.slf4j.Slf4j;

/** Braintrust Google GenAI client instrumentation. */
@Slf4j
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
        try {
            return BraintrustInstrumentation.wrapClient(genAIClientBuilder.build(), openTelemetry);
        } catch (Throwable t) {
            log.error("failed to instrument gemini client", t);
            return genAIClientBuilder.build();
        }
    }
}
