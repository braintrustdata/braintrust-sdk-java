package dev.braintrust.agent.internal.google;

import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.context.Scope;
import net.bytebuddy.asm.Advice;

/**
 * ByteBuddy advice for intercepting Google GenAI {@code ApiClient.request()} calls.
 *
 * <p>Since Google's GenAI SDK routes all API calls through a single {@code ApiClient.request()}
 * method, we instrument at that level and extract model/usage info from the URL and response body.
 */
public class GenerateContentAdvice {

    static final AttributeKey<String> GEN_AI_SYSTEM = AttributeKey.stringKey("gen_ai.system");
    static final AttributeKey<String> GEN_AI_OPERATION =
            AttributeKey.stringKey("gen_ai.operation.name");
    static final AttributeKey<String> GEN_AI_REQUEST_MODEL =
            AttributeKey.stringKey("gen_ai.request.model");
    static final AttributeKey<String> BT_METADATA_PROVIDER =
            AttributeKey.stringKey("braintrust.metadata.provider");
    static final AttributeKey<String> BT_METADATA_MODEL =
            AttributeKey.stringKey("braintrust.metadata.model");
    static final AttributeKey<String> HTTP_REQUEST_METHOD =
            AttributeKey.stringKey("http.request.method");
    static final AttributeKey<String> URL_FULL = AttributeKey.stringKey("url.full");

    /**
     * Intercepts {@code ApiClient.request(String method, String url, String body, Optional opts)}.
     *
     * @param method HTTP method (GET, POST, etc.)
     * @param url the API URL, which contains the model name (e.g., ".../models/gemini-pro:generateContent")
     */
    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static Object[] onEnter(
            @Advice.Argument(0) String method, @Advice.Argument(1) String url) {
        // Only instrument generateContent and streamGenerateContent calls
        if (url == null
                || (!url.contains("generateContent") && !url.contains("streamGenerateContent"))) {
            return null;
        }

        var tracer =
                GlobalOpenTelemetry.get().getTracer("braintrust-java-agent", "0.1.0");

        String model = extractModelFromUrl(url);
        String operation = url.contains("streamGenerateContent") ? "chat_stream" : "chat";

        var spanBuilder =
                tracer.spanBuilder("Generate Content")
                        .setSpanKind(SpanKind.CLIENT)
                        .setAttribute(GEN_AI_SYSTEM, "google_genai")
                        .setAttribute(GEN_AI_OPERATION, operation)
                        .setAttribute(BT_METADATA_PROVIDER, "google")
                        .setAttribute(HTTP_REQUEST_METHOD, method)
                        .setAttribute(URL_FULL, url);

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
            }
            // Note: Response body parsing for usage tokens would require reading the ApiResponse,
            // which is complex. For now, we capture the span timing and model info. Token usage
            // can be added in a future enhancement by also intercepting the response.
        } finally {
            span.end();
            scope.close();
        }
    }

    /**
     * Extracts the model name from a Google GenAI API URL.
     * Example URL: "https://generativelanguage.googleapis.com/v1beta/models/gemini-pro:generateContent"
     * Returns: "gemini-pro"
     */
    private static String extractModelFromUrl(String url) {
        try {
            int modelsIdx = url.indexOf("/models/");
            if (modelsIdx < 0) {
                return null;
            }
            String afterModels = url.substring(modelsIdx + "/models/".length());
            int colonIdx = afterModels.indexOf(':');
            if (colonIdx > 0) {
                return afterModels.substring(0, colonIdx);
            }
            // If no colon, take up to next '/' or '?' or end
            int endIdx = afterModels.length();
            for (char c : new char[] {'/', '?'}) {
                int idx = afterModels.indexOf(c);
                if (idx > 0 && idx < endIdx) {
                    endIdx = idx;
                }
            }
            return afterModels.substring(0, endIdx);
        } catch (Exception e) {
            return null;
        }
    }
}
