package dev.braintrust.instrumentation.openai;

import com.openai.client.OpenAIClient;
import com.openai.core.ObjectMappers;
import com.openai.models.chat.completions.ChatCompletionCreateParams;
import dev.braintrust.instrumentation.openai.otel.OpenAITelemetry;
import dev.braintrust.prompt.BraintrustPrompt;
import io.opentelemetry.api.OpenTelemetry;
import java.util.HashMap;
import java.util.Map;
import lombok.SneakyThrows;

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

    @SneakyThrows
    public static ChatCompletionCreateParams buildChatCompletionsPrompt(
            BraintrustPrompt prompt, Map<String, String> parameters) {
        var promptMap = new HashMap<>(prompt.getOptions());
        promptMap.put("messages", prompt.renderMessages(parameters));
        var promptJson = ObjectMappers.jsonMapper().writeValueAsString(promptMap);

        var body =
                ObjectMappers.jsonMapper()
                        .readValue(promptJson, ChatCompletionCreateParams.Body.class);

        return ChatCompletionCreateParams.builder()
                .body(body)
                .additionalBodyProperties(Map.of())
                .build();
    }
}
