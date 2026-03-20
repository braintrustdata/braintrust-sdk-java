package dev.braintrust.instrumentation.springai.v1_0_0;

import io.opentelemetry.api.OpenTelemetry;
import java.lang.reflect.Method;
import lombok.extern.slf4j.Slf4j;

/**
 * Braintrust Spring AI instrumentation entry point.
 *
 * <p>Accepts any Spring AI chat-model builder and instruments it in place before {@code build()}
 * runs. Provider-specific logic lives in {@link OpenAIBuilderWrapper} and {@link
 * AnthropicBuilderWrapper}, which are only referenced here by string class name so that muzzle does
 * not follow the reference when a given provider library is absent from the classpath.
 */
@Slf4j
public class BraintrustSpringAI {
    private static final String OPENAI_BUILDER_CLASS =
            "org.springframework.ai.openai.OpenAiChatModel$Builder";
    private static final String ANTHROPIC_BUILDER_CLASS =
            "org.springframework.ai.anthropic.AnthropicChatModel$Builder";

    private static final String OPENAI_WRAPPER_CLASS =
            "dev.braintrust.instrumentation.springai.v1_0_0.OpenAIBuilderWrapper";
    private static final String ANTHROPIC_WRAPPER_CLASS =
            "dev.braintrust.instrumentation.springai.v1_0_0.AnthropicBuilderWrapper";

    /** Instruments a Spring AI chat-model builder in place. */
    public static <T> T wrap(OpenTelemetry openTelemetry, T chatModelBuilder) {
        try {
            String builderClassName = chatModelBuilder.getClass().getName();
            String wrapperClass;
            if (OPENAI_BUILDER_CLASS.equals(builderClassName)) {
                wrapperClass = OPENAI_WRAPPER_CLASS;
            } else if (ANTHROPIC_BUILDER_CLASS.equals(builderClassName)) {
                wrapperClass = ANTHROPIC_WRAPPER_CLASS;
            } else {
                log.info("BraintrustSpringAI.wrap: unrecognised builder type {}", builderClassName);
                return chatModelBuilder;
            }
            Class<?> wrapper = chatModelBuilder.getClass().getClassLoader().loadClass(wrapperClass);
            Method wrapMethod =
                    wrapper.getDeclaredMethod("wrap", OpenTelemetry.class, Object.class);
            wrapMethod.invoke(null, openTelemetry, chatModelBuilder);
        } catch (Exception e) {
            log.error("failed to apply spring ai instrumentation", e);
        }
        return chatModelBuilder;
    }

    private BraintrustSpringAI() {}
}
