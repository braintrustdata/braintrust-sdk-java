package dev.braintrust.instrumentation.anthropic.otel;

import com.anthropic.models.messages.Message;
import com.anthropic.models.messages.MessageCreateParams;
import com.google.errorprone.annotations.CanIgnoreReturnValue;
import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.instrumentation.api.incubator.semconv.genai.GenAiAttributesExtractor;
import io.opentelemetry.instrumentation.api.incubator.semconv.genai.GenAiClientMetrics;
import io.opentelemetry.instrumentation.api.incubator.semconv.genai.GenAiSpanNameExtractor;
import io.opentelemetry.instrumentation.api.instrumenter.Instrumenter;
import io.opentelemetry.instrumentation.api.instrumenter.SpanKindExtractor;

/** A builder of {@link AnthropicTelemetry}. */
public final class AnthropicTelemetryBuilder {
    static final String INSTRUMENTATION_NAME = "io.opentelemetry.anthropic-java-2.8";

    private final OpenTelemetry openTelemetry;

    private boolean captureMessageContent;

    AnthropicTelemetryBuilder(OpenTelemetry openTelemetry) {
        this.openTelemetry = openTelemetry;
    }

    /**
     * Sets whether emitted log events include full content of user and assistant messages.
     *
     * <p>Note that full content can have data privacy and size concerns and care should be taken
     * when enabling this.
     */
    @CanIgnoreReturnValue
    public AnthropicTelemetryBuilder setCaptureMessageContent(boolean captureMessageContent) {
        this.captureMessageContent = captureMessageContent;
        return this;
    }

    /**
     * Returns a new {@link AnthropicTelemetry} with the settings of this {@link
     * AnthropicTelemetryBuilder}.
     */
    public AnthropicTelemetry build() {
        Instrumenter<MessageCreateParams, Message> messageInstrumenter =
                Instrumenter.<MessageCreateParams, Message>builder(
                                openTelemetry,
                                INSTRUMENTATION_NAME,
                                GenAiSpanNameExtractor.create(MessageAttributesGetter.INSTANCE))
                        .addAttributesExtractor(
                                GenAiAttributesExtractor.create(MessageAttributesGetter.INSTANCE))
                        .addOperationMetrics(GenAiClientMetrics.get())
                        .buildInstrumenter(SpanKindExtractor.alwaysClient());

        return new AnthropicTelemetry(messageInstrumenter, captureMessageContent);
    }
}
