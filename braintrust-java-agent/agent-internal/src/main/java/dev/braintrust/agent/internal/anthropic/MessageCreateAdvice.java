package dev.braintrust.agent.internal.anthropic;

import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.context.Scope;
import net.bytebuddy.asm.Advice;

/**
 * ByteBuddy advice for intercepting Anthropic {@code MessageService.create()} calls.
 *
 * <p>Starts a span on method entry with request attributes and ends it on exit with response
 * attributes (usage tokens, model, stop reason).
 */
public class MessageCreateAdvice {

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
    static final AttributeKey<String> BT_METADATA_PROVIDER =
            AttributeKey.stringKey("braintrust.metadata.provider");
    static final AttributeKey<String> BT_METADATA_MODEL =
            AttributeKey.stringKey("braintrust.metadata.model");

    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static Object[] onEnter(@Advice.Argument(0) Object params) {
        var tracer =
                GlobalOpenTelemetry.get().getTracer("braintrust-java-agent", "0.1.0");

        var spanBuilder =
                tracer.spanBuilder("Message Create")
                        .setSpanKind(SpanKind.CLIENT)
                        .setAttribute(GEN_AI_SYSTEM, "anthropic")
                        .setAttribute(GEN_AI_OPERATION, "chat")
                        .setAttribute(BT_METADATA_PROVIDER, "anthropic");

        // Extract model from MessageCreateParams via reflection
        String model = extractModel(params);
        if (model != null) {
            spanBuilder.setAttribute(GEN_AI_REQUEST_MODEL, model);
            spanBuilder.setAttribute(BT_METADATA_MODEL, model);
        }

        Span span = spanBuilder.startSpan();
        Scope scope = span.makeCurrent();
        return new Object[] {span, scope};
    }

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

    private static String extractModel(Object params) {
        try {
            // MessageCreateParams.model() returns a Model (union type)
            Object modelObj = params.getClass().getMethod("model").invoke(params);
            if (modelObj != null) {
                try {
                    return (String) modelObj.getClass().getMethod("asString").invoke(modelObj);
                } catch (NoSuchMethodException e) {
                    return modelObj.toString();
                }
            }
        } catch (Exception e) {
            // Silently ignore
        }
        return null;
    }

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

            // response.model() — Anthropic returns a Model union type
            try {
                Object model = response.getClass().getMethod("model").invoke(response);
                if (model != null) {
                    String modelStr;
                    try {
                        modelStr = (String) model.getClass().getMethod("asString").invoke(model);
                    } catch (NoSuchMethodException e) {
                        modelStr = model.toString();
                    }
                    span.setAttribute(GEN_AI_RESPONSE_MODEL, modelStr);
                }
            } catch (NoSuchMethodException ignored) {
            }

            // response.usage() -> inputTokens(), outputTokens()
            try {
                Object usage = response.getClass().getMethod("usage").invoke(response);
                if (usage != null) {
                    Object inputTokens =
                            usage.getClass().getMethod("inputTokens").invoke(usage);
                    Object outputTokens =
                            usage.getClass().getMethod("outputTokens").invoke(usage);
                    if (inputTokens instanceof Number n) {
                        span.setAttribute(GEN_AI_USAGE_INPUT, n.longValue());
                    }
                    if (outputTokens instanceof Number n) {
                        span.setAttribute(GEN_AI_USAGE_OUTPUT, n.longValue());
                    }
                }
            } catch (NoSuchMethodException ignored) {
            }
        } catch (Exception e) {
            // Silently ignore
        }
    }
}
