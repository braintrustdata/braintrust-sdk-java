package dev.braintrust.instrumentation.openai;

import com.openai.client.OpenAIClient;
import com.openai.models.chat.completions.ChatCompletionCreateParams;
import dev.braintrust.instrumentation.openai.otel.OpenAITelemetry;
import dev.braintrust.prompt.BraintrustPrompt;
import io.opentelemetry.api.OpenTelemetry;

/** Braintrust OpenAI client instrumentation. */
public class BraintrustOpenAI {
    /** Instrument openai client with braintrust traces */
    public static OpenAIClient wrapOpenAI(OpenTelemetry openTelemetry, OpenAIClient openAIClient) {
        if ("true".equalsIgnoreCase(System.getenv("BRAINTRUST_X_OTEL_LOGS"))) {
            return io.opentelemetry.instrumentation.openai.v1_1.OpenAITelemetry.builder(
                            openTelemetry)
                    .setCaptureMessageContent(true)
                    .build()
                    .wrap(openAIClient);
        } else {
            return OpenAITelemetry.builder(openTelemetry)
                    .setCaptureMessageContent(true)
                    .build()
                    .wrap(openAIClient);
        }
    }

    public static ChatCompletionCreateParams buildChatCompletionsPrompt(BraintrustPrompt prompt) {
        throw new RuntimeException("TODO");
    }
}
