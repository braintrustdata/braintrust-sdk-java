package dev.braintrust.instrumentation.genai.v1_18_0;

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
     * <p>This wraps the client's internal ApiClient to capture all API calls with OpenTelemetry
     * spans.
     *
     * @param openTelemetry the OpenTelemetry instance
     * @param client the built Gemini client
     * @return an instrumented Gemini client
     */
    public static Client wrap(OpenTelemetry openTelemetry, Client client) {
        try {
            return BraintrustInstrumentation.wrapClient(client, openTelemetry);
        } catch (Throwable t) {
            log.error("failed to instrument gemini client", t);
            return client;
        }
    }
}
