/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package dev.braintrust.instrumentation.openai.otel;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.openai.models.chat.completions.ChatCompletion;
import com.openai.models.chat.completions.ChatCompletionMessage;
import io.opentelemetry.api.trace.Span;
import java.util.List;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;

/** Centralized class for setting all OpenAI-related span attributes. */
@Slf4j
final class BraintrustOAISpanAttributes {
    private static final ObjectMapper JSON_MAPPER = new ObjectMapper();

    // GenAI semantic convention constants
    static final String OPERATION_CHAT = "chat";
    static final String OPERATION_EMBEDDINGS = "embeddings";
    static final String SYSTEM_OPENAI = "openai";

    private BraintrustOAISpanAttributes() {}

    /**
     * Sets the gen_ai.input.messages attribute with the serialized input messages. This captures
     * the user's prompt and system messages before sending to OpenAI.
     */
    @SneakyThrows
    public static void setInputMessages(Span span, List<?> messages) {
        String semconvJson =
                GenAiSemconvSerializer.serializeInputMessages(
                        (List<com.openai.models.chat.completions.ChatCompletionMessageParam>)
                                messages);
        span.setAttribute("gen_ai.input.messages", semconvJson);
    }

    /**
     * Sets the gen_ai.output.messages attribute with the serialized output message. This captures
     * the assistant's response from OpenAI for a single choice.
     */
    @SneakyThrows
    public static void setOutputMessages(
            Span span, ChatCompletionMessage message, String finishReason) {
        String outputJson = GenAiSemconvSerializer.serializeOutputMessage(message, finishReason);
        span.setAttribute("gen_ai.output.messages", outputJson);
    }

    /**
     * Sets the gen_ai.output.messages attribute for the primary choice in a completion. Logs a
     * debug message if there are no choices or multiple choices.
     */
    public static void setOutputMessagesFromCompletion(Span span, ChatCompletion completion) {
        if (completion.choices().isEmpty()) {
            log.debug("no choices in OAI response");
        } else if (completion.choices().size() > 1) {
            log.debug("multiple choices in OAI response: {}", completion.choices().size());
        } else {
            // Set gen_ai.output.messages attribute for single choice (most common case)
            ChatCompletion.Choice choice = completion.choices().get(0);
            setOutputMessages(span, choice.message(), choice.finishReason().toString());
        }
    }

    /**
     * Sets the braintrust.output_json attribute with a single message. This is used for streaming
     * responses to capture output in Braintrust format.
     */
    @SneakyThrows
    public static void setBraintrustOutputJson(Span span, ChatCompletionMessage message) {
        span.setAttribute(
                "braintrust.output_json",
                JSON_MAPPER.writeValueAsString(new ChatCompletionMessage[] {message}));
    }
}
