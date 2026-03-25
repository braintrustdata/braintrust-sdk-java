package dev.braintrust.instrumentation.langchain;

import dev.langchain4j.model.openai.OpenAiChatModel;
import dev.langchain4j.model.openai.OpenAiStreamingChatModel;
import dev.langchain4j.service.AiServices;
import io.opentelemetry.api.OpenTelemetry;

/** Braintrust LangChain4j client instrumentation. */
public final class BraintrustLangchain {

    /** Instrument a LangChain4j AiServices builder with Braintrust traces. */
    @SuppressWarnings("unchecked")
    public static <T> T wrap(OpenTelemetry openTelemetry, AiServices<T> aiServices) {
        return dev.braintrust.instrumentation.langchain.v1_8_0.BraintrustLangchain.wrap(
                openTelemetry, aiServices);
    }

    /** Instrument langchain openai chat model with braintrust traces. */
    public static OpenAiChatModel wrap(
            OpenTelemetry otel, OpenAiChatModel.OpenAiChatModelBuilder builder) {
        return dev.braintrust.instrumentation.langchain.v1_8_0.BraintrustLangchain.wrap(
                otel, builder);
    }

    /** Instrument langchain openai streaming chat model with braintrust traces. */
    public static OpenAiStreamingChatModel wrap(
            OpenTelemetry otel, OpenAiStreamingChatModel.OpenAiStreamingChatModelBuilder builder) {
        return dev.braintrust.instrumentation.langchain.v1_8_0.BraintrustLangchain.wrap(
                otel, builder.build());
    }

    public static OpenAiStreamingChatModel wrap(
            OpenTelemetry otel, OpenAiStreamingChatModel model) {
        return dev.braintrust.instrumentation.langchain.v1_8_0.BraintrustLangchain.wrap(
                otel, model);
    }
}
