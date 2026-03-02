package dev.braintrust.instrumentation.anthropic.otel;

import com.anthropic.client.AnthropicClient;
import com.anthropic.models.beta.messages.BetaMessage;
import com.anthropic.models.messages.Message;
import com.anthropic.models.messages.MessageCreateParams;
import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.instrumentation.api.instrumenter.Instrumenter;

/** Entrypoint for instrumenting Anthropic clients. */
public final class AnthropicTelemetry {
    /** Returns a new {@link AnthropicTelemetry} configured with the given {@link OpenTelemetry}. */
    public static AnthropicTelemetry create(OpenTelemetry openTelemetry) {
        return builder(openTelemetry).build();
    }

    /**
     * Returns a new {@link AnthropicTelemetryBuilder} configured with the given {@link
     * OpenTelemetry}.
     */
    public static AnthropicTelemetryBuilder builder(OpenTelemetry openTelemetry) {
        return new AnthropicTelemetryBuilder(openTelemetry);
    }

    private final Instrumenter<MessageCreateParams, Message> messageInstrumenter;
    private final Instrumenter<com.anthropic.models.beta.messages.MessageCreateParams, BetaMessage>
            betaMessageInstrumenter;
    private final boolean captureMessageContent;

    AnthropicTelemetry(
            Instrumenter<MessageCreateParams, Message> messageInstrumenter,
            Instrumenter<com.anthropic.models.beta.messages.MessageCreateParams, BetaMessage>
                    betaMessageInstrumenter,
            boolean captureMessageContent) {
        this.messageInstrumenter = messageInstrumenter;
        this.betaMessageInstrumenter = betaMessageInstrumenter;
        this.captureMessageContent = captureMessageContent;
    }

    /** Wraps the provided AnthropicClient, enabling telemetry for it. */
    public AnthropicClient wrap(AnthropicClient client) {
        return new InstrumentedAnthropicClient(
                        client, messageInstrumenter, betaMessageInstrumenter, captureMessageContent)
                .createProxy();
    }
}
