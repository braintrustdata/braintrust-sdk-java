/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package dev.braintrust.instrumentation.openai.otel;

import com.openai.models.chat.completions.ChatCompletion;
import io.opentelemetry.api.trace.Span;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;

/** Centralized class for setting all OpenAI-related span attributes. */
@Slf4j
final class BraintrustOAISpanAttributes {

    // GenAI semantic convention constants
    static final String OPERATION_CHAT = "chat";
    static final String OPERATION_EMBEDDINGS = "embeddings";
    static final String SYSTEM_OPENAI = "openai";

    private BraintrustOAISpanAttributes() {}

    @SneakyThrows
    static void setRequestAttributes(
            Span span, com.openai.models.chat.completions.ChatCompletionCreateParams request) {
        // Set input messages
        String semconvJson = GenAiSemconvSerializer.serializeInputMessages(request.messages());
        span.setAttribute("gen_ai.input.messages", semconvJson);

        // Set Braintrust metadata
        span.setAttribute("braintrust.metadata.provider", SYSTEM_OPENAI);

        // Set model in metadata if present
        try {
            var model = request.model();
            span.setAttribute("braintrust.metadata.model", model.toString());
        } catch (Exception e) {
            // If model() throws or returns null, just skip setting it
            log.debug("Could not get model from request", e);
        }
    }

    @SneakyThrows
    static void setOutputMessagesFromCompletion(Span span, ChatCompletion completion) {
        span.setAttribute(
                "gen_ai.output.messages",
                GenAiSemconvSerializer.serializeOutputMessages(completion.choices()));
    }

    static void setTimeToFirstToken(Span span, double timeInSeconds) {
        span.setAttribute("braintrust.metrics.time_to_first_token", timeInSeconds);
    }
}
