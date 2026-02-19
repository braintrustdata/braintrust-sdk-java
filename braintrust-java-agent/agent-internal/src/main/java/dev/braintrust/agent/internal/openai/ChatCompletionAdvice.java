package dev.braintrust.agent.internal.openai;

import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.Scope;
import net.bytebuddy.asm.Advice;

/**
 * ByteBuddy advice for intercepting OpenAI {@code ChatCompletionService.create()} calls.
 *
 * <p>Starts a span on method entry with request attributes (model, messages) and ends it on exit
 * with response attributes (usage tokens, finish reason, response model).
 *
 * <p>This advice class is inlined into the target method by ByteBuddy. The advice methods must only
 * reference types visible from the target class's classloader — which means OTel API (on bootstrap)
 * and JDK types. We use reflection to access OpenAI SDK types since they're only on the app
 * classpath.
 */
public class ChatCompletionAdvice {

    // GenAI semantic convention attribute keys
    static final AttributeKey<String> GEN_AI_SYSTEM = AttributeKey.stringKey("gen_ai.system");
    static final AttributeKey<String> GEN_AI_OPERATION =
            AttributeKey.stringKey("gen_ai.operation.name");
    static final AttributeKey<String> GEN_AI_REQUEST_MODEL =
            AttributeKey.stringKey("gen_ai.request.model");
    static final AttributeKey<String> GEN_AI_RESPONSE_MODEL =
            AttributeKey.stringKey("gen_ai.response.model");
    static final AttributeKey<String> GEN_AI_RESPONSE_ID =
            AttributeKey.stringKey("gen_ai.response.id");
    static final AttributeKey<Long> GEN_AI_USAGE_INPUT =
            AttributeKey.longKey("gen_ai.usage.input_tokens");
    static final AttributeKey<Long> GEN_AI_USAGE_OUTPUT =
            AttributeKey.longKey("gen_ai.usage.output_tokens");

    // Braintrust-specific attribute keys
    static final AttributeKey<String> BT_METADATA_PROVIDER =
            AttributeKey.stringKey("braintrust.metadata.provider");
    static final AttributeKey<String> BT_METADATA_MODEL =
            AttributeKey.stringKey("braintrust.metadata.model");

    /**
     * Called on entry to {@code ChatCompletionService.create(ChatCompletionCreateParams)} and the
     * overload with {@code RequestOptions}.
     *
     * @param params the first argument — a {@code ChatCompletionCreateParams} instance
     * @return an array holding [Span, Scope] for cleanup in the exit advice
     */
    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static Object[] onEnter(@Advice.Argument(0) Object params) {
        var tracer =
                GlobalOpenTelemetry.get().getTracer("braintrust-java-agent", "0.1.0");

        var spanBuilder =
                tracer.spanBuilder("Chat Completion")
                        .setSpanKind(SpanKind.CLIENT)
                        .setAttribute(GEN_AI_SYSTEM, "openai")
                        .setAttribute(GEN_AI_OPERATION, "chat")
                        .setAttribute(BT_METADATA_PROVIDER, "openai");

        // Extract model from params via reflection (avoids compile-time dep on OpenAI SDK)
        String model = extractModel(params);
        if (model != null) {
            spanBuilder.setAttribute(GEN_AI_REQUEST_MODEL, model);
            spanBuilder.setAttribute(BT_METADATA_MODEL, model);
        }

        Span span = spanBuilder.startSpan();
        Scope scope = span.makeCurrent();
        return new Object[] {span, scope};
    }

    /**
     * Called on exit from {@code ChatCompletionService.create()}.
     *
     * @param enterState the [Span, Scope] array from onEnter
     * @param result the return value (a {@code ChatCompletion} instance), or null if exception
     * @param thrown the exception thrown, or null on success
     */
    @Advice.OnMethodExit(onThrowable = Throwable.class, suppress = Throwable.class)
    public static void onExit(
            @Advice.Enter Object[] enterState,
            @Advice.Return Object result,
            @Advice.Thrown Throwable thrown) {
        if (enterState == null) {
            return;
        }
        Span span = (Span) enterState[0];
        Scope scope = (Scope) enterState[1];

        try {
            if (thrown != null) {
                span.setStatus(StatusCode.ERROR, thrown.getMessage());
                span.recordException(thrown);
            } else if (result != null) {
                enrichSpanFromResponse(span, result);
            }
        } finally {
            span.end();
            scope.close();
        }
    }

    /**
     * Extracts the model string from a {@code ChatCompletionCreateParams} via reflection.
     * Returns null if extraction fails (the span just won't have the model attribute).
     */
    private static String extractModel(Object params) {
        try {
            // ChatCompletionCreateParams.model() returns a ChatModel (union type)
            Object modelObj = params.getClass().getMethod("model").invoke(params);
            if (modelObj != null) {
                // ChatModel.asString() returns the model name
                try {
                    return (String) modelObj.getClass().getMethod("asString").invoke(modelObj);
                } catch (NoSuchMethodException e) {
                    return modelObj.toString();
                }
            }
        } catch (Exception e) {
            // Silently ignore — the span just won't have model info
        }
        return null;
    }

    /**
     * Enriches the span with response attributes from a {@code ChatCompletion} via reflection.
     */
    private static void enrichSpanFromResponse(Span span, Object response) {
        try {
            // response.id()
            try {
                Object id = response.getClass().getMethod("id").invoke(response);
                if (id != null) {
                    span.setAttribute(GEN_AI_RESPONSE_ID, id.toString());
                }
            } catch (NoSuchMethodException ignored) {
            }

            // response.model()
            try {
                Object model = response.getClass().getMethod("model").invoke(response);
                if (model != null) {
                    span.setAttribute(GEN_AI_RESPONSE_MODEL, model.toString());
                }
            } catch (NoSuchMethodException ignored) {
            }

            // response.usage() -> promptTokens(), completionTokens()
            try {
                Object usage = response.getClass().getMethod("usage").invoke(response);
                if (usage != null) {
                    Object promptTokens =
                            usage.getClass().getMethod("promptTokens").invoke(usage);
                    Object completionTokens =
                            usage.getClass().getMethod("completionTokens").invoke(usage);
                    if (promptTokens instanceof Number n) {
                        span.setAttribute(GEN_AI_USAGE_INPUT, n.longValue());
                    }
                    if (completionTokens instanceof Number n) {
                        span.setAttribute(GEN_AI_USAGE_OUTPUT, n.longValue());
                    }
                }
            } catch (NoSuchMethodException ignored) {
            }
        } catch (Exception e) {
            // Silently ignore — partial attributes are better than no span
        }
    }
}
