package dev.braintrust.instrumentation.openai;

import com.openai.client.OpenAIClient;
import com.openai.models.chat.completions.ChatCompletionCreateParams;
import dev.braintrust.prompt.BraintrustPrompt;
import io.opentelemetry.api.OpenTelemetry;
import java.util.Map;

public class BraintrustOpenAI {
    /** Instrument openai client with braintrust traces */
    public static OpenAIClient wrapOpenAI(OpenTelemetry openTelemetry, OpenAIClient openAIClient) {
        return dev.braintrust.instrumentation.openai.v2_15_0.BraintrustOpenAI.wrapOpenAI(
                openTelemetry, openAIClient);
    }

    public static ChatCompletionCreateParams buildChatCompletionsPrompt(
            BraintrustPrompt prompt, Map<String, Object> parameters) {
        return dev.braintrust.instrumentation.openai.v2_15_0.BraintrustOpenAI
                .buildChatCompletionsPrompt(prompt, parameters);
    }
}
