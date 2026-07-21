package dev.braintrust.instrumentation.springai.v2_0_0;

import io.opentelemetry.api.OpenTelemetry;
import lombok.extern.slf4j.Slf4j;

/**
 * Braintrust Spring AI 2.x instrumentation entry point for manual (non-agent) use.
 *
 * <p>Accepts a built Spring AI chat model and instruments it in place.
 *
 * <pre>{@code
 * var chatModel = BraintrustSpringAI.wrap(openTelemetry, OpenAiChatModel.builder()...build());
 * }</pre>
 */
@Slf4j
public final class BraintrustSpringAI {

    private static final String OPENAI_CHAT_MODEL_CLASS =
            "org.springframework.ai.openai.OpenAiChatModel";
    private static final String ANTHROPIC_CHAT_MODEL_CLASS =
            "org.springframework.ai.anthropic.AnthropicChatModel";

    /** Instruments a Spring AI chat model in place and returns it. */
    public static <T> T wrap(OpenTelemetry openTelemetry, T chatModel) {
        try {
            String className = chatModel.getClass().getName();
            switch (className) {
                case OPENAI_CHAT_MODEL_CLASS -> SpringAIOpenAI.wrap(openTelemetry, chatModel);
                case ANTHROPIC_CHAT_MODEL_CLASS -> SpringAIAnthropic.wrap(openTelemetry, chatModel);
                default ->
                        log.warn("BraintrustSpringAI.wrap: unrecognised chat model {}", className);
            }
        } catch (Exception e) {
            log.error("failed to apply spring ai instrumentation", e);
        }
        return chatModel;
    }

    private BraintrustSpringAI() {}
}
