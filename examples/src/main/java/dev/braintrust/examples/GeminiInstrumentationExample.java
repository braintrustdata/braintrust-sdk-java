package dev.braintrust.examples;

import com.google.genai.Client;
import com.google.genai.types.GenerateContentConfig;
import com.google.genai.types.GenerateContentResponse;
import dev.braintrust.Braintrust;
import dev.braintrust.instrumentation.genai.BraintrustGenAI;
import io.opentelemetry.api.OpenTelemetry;

/** Basic OTel + Gemini instrumentation example */
public class GeminiInstrumentationExample {
    public static void main(String[] args) throws Exception {
        if (null == System.getenv("GOOGLE_API_KEY") && null == System.getenv("GEMINI_API_KEY")) {
            System.err.println(
                    "\n"
                            + "WARNING: Neither GOOGLE_API_KEY nor GEMINI_API_KEY found. This"
                            + " example will likely fail.\n"
                            + "Set either: export GOOGLE_API_KEY='your-key' (recommended) or export"
                            + " GEMINI_API_KEY='your-key'\n");
        }

        Braintrust braintrust = Braintrust.get();
        OpenTelemetry openTelemetry = braintrust.openTelemetryCreate();
        // CLAUDE: don't change the type of geminiClient -- sdk users must use the google genai
        // client in their signature, not our instrumented client.
        Client geminiClient = BraintrustGenAI.wrap(openTelemetry, new Client.Builder());

        var tracer = openTelemetry.getTracer("my-instrumentation");
        var rootSpan = tracer.spanBuilder("gemini-java-instrumentation-example").startSpan();
        try (var ignored = rootSpan.makeCurrent()) {
            generateContentExample(geminiClient);
            // generateContentStreamingExample(client);
        } finally {
            rootSpan.end();
        }

        var url =
                braintrust.projectUri()
                        + "/logs?r=%s&s=%s"
                                .formatted(
                                        rootSpan.getSpanContext().getTraceId(),
                                        rootSpan.getSpanContext().getSpanId());

        System.out.println(
                "\n\n  Example complete! View your data in Braintrust: %s\n".formatted(url));
    }

    private static void generateContentExample(Client client) {
        var config = GenerateContentConfig.builder().temperature(0.0f).maxOutputTokens(50).build();

        var response =
                client.models.generateContent(
                        "gemini-2.0-flash-lite", "What is the third planet from the sun?", config);

        System.out.println("\n~~~ GENERATE CONTENT RESPONSE: %s\n".formatted(response.text()));
    }

    private static void generateContentStreamingExample(Client client) {
        var config = GenerateContentConfig.builder().temperature(0.0f).maxOutputTokens(50).build();

        System.out.println("\n~~~ STREAMING RESPONSE:");
        var stream =
                client.models.generateContentStream(
                        "gemini-2.0-flash-exp",
                        "Who was the first president of the United States?",
                        config);

        for (GenerateContentResponse chunk : stream) {
            String text = chunk.text();
            if (text != null && !text.isEmpty()) {
                System.out.print(text);
            }
        }
        System.out.println("\n");
    }
}
