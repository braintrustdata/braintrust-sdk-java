package dev.braintrust.instrumentation.langchain;

import dev.langchain4j.model.openai.OpenAiChatModel;
import dev.langchain4j.model.openai.OpenAiStreamingChatModel;
import dev.langchain4j.service.AiServices;
import io.opentelemetry.api.OpenTelemetry;
import java.lang.reflect.Method;
import lombok.extern.slf4j.Slf4j;

/**
 * Braintrust LangChain4j client instrumentation.
 *
 * @deprecated use the wrapper matching your langchain4j version instead:
 *     <ul>
 *       <li>{@code dev.braintrust.instrumentation.langchain.v1_14_0.BraintrustLangchain} —
 *           langchain4j 1.14.0 and up.
 *       <li>{@code dev.braintrust.instrumentation.langchain.v1_8_0.BraintrustLangchain} —
 *           langchain4j 1.8.0 through 1.13.x.
 *     </ul>
 */
@Deprecated(forRemoval = true)
@Slf4j
public final class BraintrustLangchain {

    /**
     * Sentinel class that first appeared in langchain4j 1.14.0, alongside the Responses API. Its
     * presence is what {@code LangchainInstrumentationModule.classLoaderMatcher()} keys the
     * agent-side module gate on, so reusing it here keeps the manual path picking the same module
     * the agent would.
     */
    private static final String RESPONSES_SENTINEL =
            "dev.langchain4j.model.openai.OpenAiResponsesChatModel";

    private static final String V1_14_0_WRAPPER =
            "dev.braintrust.instrumentation.langchain.v1_14_0.BraintrustLangchain";

    /**
     * The {@code v1_14_0} wrapper class, or {@code null} when this process is on langchain4j &lt;
     * 1.14.0 (or that module was stripped from the jar). Resolved once at class-init: both modules
     * are embedded in the same braintrust-sdk jar, so this is a fixed property of the classpath
     * rather than something to re-check per call.
     */
    private static final Class<?> V1_14_0 = resolveV1140Wrapper();

    private static Class<?> resolveV1140Wrapper() {
        ClassLoader loader = BraintrustLangchain.class.getClassLoader();
        try {
            Class.forName(RESPONSES_SENTINEL, false, loader);
        } catch (ClassNotFoundException | LinkageError e) {
            // langchain4j < 1.14.0: v1_8_0 is the correct module.
            return null;
        }
        try {
            return Class.forName(V1_14_0_WRAPPER, true, loader);
        } catch (ClassNotFoundException | LinkageError e) {
            log.warn(
                    "langchain4j 1.14.0+ detected but {} is missing; falling back to the"
                            + " langchain_1_8_0 instrumentation (chat completions only)",
                    V1_14_0_WRAPPER,
                    e);
            return null;
        }
    }

    /** Whether calls should be forwarded to the {@code v1_14_0} wrapper. */
    private static boolean forwards() {
        return V1_14_0 != null;
    }

    /**
     * Reflectively invokes {@code v1_14_0.BraintrustLangchain.wrap(OpenTelemetry, paramType)}.
     * Reflection is required because {@code langchain_1_8_0} deliberately does not depend on the
     * newer module — doing so would put langchain4j 1.14.0+ on this module's compile classpath and
     * defeat compiling against the minimum supported version.
     *
     * @return the wrapped model, or {@code null} if the forward failed (caller falls back)
     */
    private static Object forward(Class<?> paramType, OpenTelemetry otel, Object arg) {
        try {
            Method wrap = V1_14_0.getMethod("wrap", OpenTelemetry.class, paramType);
            return wrap.invoke(null, otel, arg);
        } catch (ReflectiveOperationException | LinkageError e) {
            log.warn(
                    "failed to forward to {}.wrap(OpenTelemetry, {}); falling back to"
                            + " langchain_1_8_0",
                    V1_14_0_WRAPPER,
                    paramType.getName(),
                    e);
            return null;
        }
    }

    /**
     * Instrument a LangChain4j AiServices builder with Braintrust traces.
     *
     * @deprecated see {@link BraintrustLangchain}
     */
    @Deprecated(forRemoval = true)
    @SuppressWarnings("unchecked")
    public static <T> T wrap(OpenTelemetry openTelemetry, AiServices<T> aiServices) {
        if (forwards()) {
            Object wrapped = forward(AiServices.class, openTelemetry, aiServices);
            if (wrapped != null) {
                return (T) wrapped;
            }
        }
        return dev.braintrust.instrumentation.langchain.v1_8_0.BraintrustLangchain.wrap(
                openTelemetry, aiServices);
    }

    /**
     * Instrument langchain openai chat model with braintrust traces.
     *
     * @deprecated see {@link BraintrustLangchain}
     */
    @Deprecated(forRemoval = true)
    public static OpenAiChatModel wrap(
            OpenTelemetry otel, OpenAiChatModel.OpenAiChatModelBuilder builder) {
        if (forwards()) {
            Object wrapped = forward(OpenAiChatModel.OpenAiChatModelBuilder.class, otel, builder);
            if (wrapped != null) {
                return (OpenAiChatModel) wrapped;
            }
        }
        return dev.braintrust.instrumentation.langchain.v1_8_0.BraintrustLangchain.wrap(
                otel, builder);
    }

    /**
     * Instrument langchain openai streaming chat model with braintrust traces.
     *
     * @deprecated see {@link BraintrustLangchain}
     */
    @Deprecated(forRemoval = true)
    public static OpenAiStreamingChatModel wrap(
            OpenTelemetry otel, OpenAiStreamingChatModel.OpenAiStreamingChatModelBuilder builder) {
        if (forwards()) {
            Object wrapped =
                    forward(
                            OpenAiStreamingChatModel.OpenAiStreamingChatModelBuilder.class,
                            otel,
                            builder);
            if (wrapped != null) {
                return (OpenAiStreamingChatModel) wrapped;
            }
        }
        return dev.braintrust.instrumentation.langchain.v1_8_0.BraintrustLangchain.wrap(
                otel, builder.build());
    }

    /**
     * @deprecated see {@link BraintrustLangchain}
     */
    @Deprecated(forRemoval = true)
    public static OpenAiStreamingChatModel wrap(
            OpenTelemetry otel, OpenAiStreamingChatModel model) {
        if (forwards()) {
            Object wrapped = forward(OpenAiStreamingChatModel.class, otel, model);
            if (wrapped != null) {
                return (OpenAiStreamingChatModel) wrapped;
            }
        }
        return dev.braintrust.instrumentation.langchain.v1_8_0.BraintrustLangchain.wrap(
                otel, model);
    }
}
