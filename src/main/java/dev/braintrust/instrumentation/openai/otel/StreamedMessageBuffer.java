/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package dev.braintrust.instrumentation.openai.otel;

import com.openai.core.JsonField;
import com.openai.models.chat.completions.ChatCompletion;
import com.openai.models.chat.completions.ChatCompletionChunk;
import com.openai.models.chat.completions.ChatCompletionMessage;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import javax.annotation.Nullable;

final class StreamedMessageBuffer {
    private final long index;
    private final boolean captureMessageContent;

    @Nullable String finishReason;

    @Nullable private StringBuilder message;
    @Nullable private Map<Long, ToolCallBuffer> toolCalls;

    StreamedMessageBuffer(long index, boolean captureMessageContent) {
        this.index = index;
        this.captureMessageContent = captureMessageContent;
    }

    ChatCompletion.Choice toChoice() {
        ChatCompletion.Choice.Builder choice =
                ChatCompletion.Choice.builder().index(index).logprobs(Optional.empty());
        if (finishReason != null) {
            choice.finishReason(ChatCompletion.Choice.FinishReason.of(finishReason));
        } else {
            // Can't happen in practice, mostly to satisfy null check
            choice.finishReason(JsonField.ofNullable(null));
        }
        if (message != null) {
            choice.message(
                    ChatCompletionMessage.builder()
                            .content(message.toString())
                            .refusal(Optional.empty())
                            .build());
        } else {
            choice.message(JsonField.ofNullable(null));
        }
        return choice.build();
    }

    void append(ChatCompletionChunk.Choice.Delta delta) {
        if (captureMessageContent) {
            if (delta.content().isPresent()) {
                if (message == null) {
                    message = new StringBuilder();
                }
                message.append(delta.content().get());
            }
        }

        if (delta.toolCalls().isPresent()) {
            if (toolCalls == null) {
                toolCalls = new HashMap<>();
            }

            for (ChatCompletionChunk.Choice.Delta.ToolCall toolCall : delta.toolCalls().get()) {
                ToolCallBuffer buffer =
                        toolCalls.computeIfAbsent(
                                toolCall.index(),
                                unused -> new ToolCallBuffer(toolCall.id().orElse("")));
                toolCall.type().ifPresent(type -> buffer.type = type.toString());
                toolCall.function()
                        .ifPresent(
                                function -> {
                                    function.name().ifPresent(name -> buffer.function.name = name);
                                    if (captureMessageContent) {
                                        function.arguments()
                                                .ifPresent(
                                                        args -> {
                                                            if (buffer.function.arguments == null) {
                                                                buffer.function.arguments =
                                                                        new StringBuilder();
                                                            }
                                                            buffer.function.arguments.append(args);
                                                        });
                                    }
                                });
            }
        }
    }

    private static class FunctionBuffer {
        @Nullable String name;
        @Nullable StringBuilder arguments;
    }

    private static class ToolCallBuffer {
        final String id;
        final FunctionBuffer function = new FunctionBuffer();
        @Nullable String type;

        ToolCallBuffer(String id) {
            this.id = id;
        }
    }
}
