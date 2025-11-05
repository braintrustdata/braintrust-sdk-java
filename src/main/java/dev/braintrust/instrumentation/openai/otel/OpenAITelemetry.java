/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package dev.braintrust.instrumentation.openai.otel;

import com.openai.client.OpenAIClient;
import com.openai.client.OpenAIClientAsync;
import com.openai.models.chat.completions.ChatCompletion;
import com.openai.models.chat.completions.ChatCompletionCreateParams;
import com.openai.models.embeddings.CreateEmbeddingResponse;
import com.openai.models.embeddings.EmbeddingCreateParams;
import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.instrumentation.api.instrumenter.Instrumenter;

/** Entrypoint for instrumenting OpenAI clients. */
@SuppressWarnings("IdentifierName") // Want to match library's convention
public final class OpenAITelemetry {
    /** Returns a new {@link OpenAITelemetry} configured with the given {@link OpenTelemetry}. */
    public static OpenAITelemetry create(OpenTelemetry openTelemetry) {
        return builder(openTelemetry).build();
    }

    /**
     * Returns a new {@link OpenAITelemetryBuilder} configured with the given {@link OpenTelemetry}.
     */
    public static OpenAITelemetryBuilder builder(OpenTelemetry openTelemetry) {
        return new OpenAITelemetryBuilder(openTelemetry);
    }

    private final Instrumenter<ChatCompletionCreateParams, ChatCompletion> chatInstrumenter;
    private final Instrumenter<EmbeddingCreateParams, CreateEmbeddingResponse>
            embeddingsInstrumenter;
    private final boolean captureMessageContent;

    OpenAITelemetry(
            Instrumenter<ChatCompletionCreateParams, ChatCompletion> chatInstrumenter,
            Instrumenter<EmbeddingCreateParams, CreateEmbeddingResponse> embeddingsInstrumenter,
            boolean captureMessageContent) {
        this.chatInstrumenter = chatInstrumenter;
        this.embeddingsInstrumenter = embeddingsInstrumenter;
        this.captureMessageContent = captureMessageContent;
    }

    /** Wraps the provided OpenAIClient, enabling telemetry for it. */
    public OpenAIClient wrap(OpenAIClient client) {
        return new InstrumentedOpenAiClient(
                        client, chatInstrumenter, embeddingsInstrumenter, captureMessageContent)
                .createProxy();
    }

    /** Wraps the provided OpenAIClientAsync, enabling telemetry for it. */
    public OpenAIClientAsync wrap(OpenAIClientAsync client) {
        return new InstrumentedOpenAiClientAsync(
                        client, chatInstrumenter, embeddingsInstrumenter, captureMessageContent)
                .createProxy();
    }
}
