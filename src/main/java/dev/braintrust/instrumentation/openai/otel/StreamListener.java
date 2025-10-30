/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package dev.braintrust.instrumentation.openai.otel;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.openai.models.chat.completions.ChatCompletion;
import com.openai.models.chat.completions.ChatCompletionChunk;
import com.openai.models.chat.completions.ChatCompletionCreateParams;
import com.openai.models.chat.completions.ChatCompletionMessage;
import com.openai.models.completions.CompletionUsage;
import io.opentelemetry.api.logs.Logger;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.context.Context;
import io.opentelemetry.instrumentation.api.instrumenter.Instrumenter;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;
import javax.annotation.Nullable;
import lombok.SneakyThrows;

final class StreamListener {
    private static final ObjectMapper JSON_MAPPER =
            new com.fasterxml.jackson.databind.ObjectMapper();

    private final Context context;
    private final ChatCompletionCreateParams request;
    private final List<StreamedMessageBuffer> choiceBuffers;

    private final Instrumenter<ChatCompletionCreateParams, ChatCompletion> instrumenter;
    private final Logger eventLogger;
    private final boolean captureMessageContent;
    private final boolean newSpan;
    private final AtomicBoolean hasEnded;
    private final AtomicBoolean firstChunkReceived;
    private final long startTime;

    @Nullable private CompletionUsage usage;
    @Nullable private String model;
    @Nullable private String responseId;

    StreamListener(
            Context context,
            ChatCompletionCreateParams request,
            Instrumenter<ChatCompletionCreateParams, ChatCompletion> instrumenter,
            Logger eventLogger,
            boolean captureMessageContent,
            boolean newSpan) {
        this.context = context;
        this.request = request;
        this.instrumenter = instrumenter;
        this.eventLogger = eventLogger;
        this.captureMessageContent = captureMessageContent;
        this.newSpan = newSpan;
        choiceBuffers = new ArrayList<>();
        hasEnded = new AtomicBoolean();
        firstChunkReceived = new AtomicBoolean(false);
        startTime = System.nanoTime();
    }

    @SneakyThrows
    void onChunk(ChatCompletionChunk chunk) {
        // Capture time_to_first_token and provider on first chunk
        if (firstChunkReceived.compareAndSet(false, true)) {
            long endTime = System.nanoTime();
            double timeToFirstToken = (endTime - startTime) / 1_000_000.0; // Convert to milliseconds
            Span span = Span.fromContext(context);
            span.setAttribute("braintrust.time_to_first_token", timeToFirstToken);
            span.setAttribute("braintrust.provider", "openai");
        }

        model = chunk.model();
        responseId = chunk.id();
        chunk.usage().ifPresent(u -> usage = u);

        for (ChatCompletionChunk.Choice choice : chunk.choices()) {
            while (choiceBuffers.size() <= choice.index()) {
                choiceBuffers.add(null);
            }
            StreamedMessageBuffer buffer = choiceBuffers.get((int) choice.index());
            if (buffer == null) {
                buffer = new StreamedMessageBuffer(choice.index(), captureMessageContent);
                choiceBuffers.set((int) choice.index(), buffer);
            }
            buffer.append(choice.delta());
            if (choice.finishReason().isPresent()) {
                buffer.finishReason = choice.finishReason().get().toString();
                Span.fromContext(context)
                        .setAttribute(
                                "braintrust.output_json",
                                JSON_MAPPER.writeValueAsString(
                                        new ChatCompletionMessage[] {buffer.toChoice().message()}));

                // message has ended, let's emit
                ChatCompletionEventsHelper.emitCompletionLogEvent(
                        context,
                        eventLogger,
                        choice.index(),
                        buffer.finishReason,
                        buffer.toEventBody());
            }
        }
    }

    void endSpan(@Nullable Throwable error) {
        // Use an atomic operation since close() type of methods are exposed to the user
        // and can come from any thread.
        if (!hasEnded.compareAndSet(false, true)) {
            return;
        }

        if (model == null || responseId == null) {
            // Only happens if we got no chunks, so we have no response.
            if (newSpan) {
                instrumenter.end(context, request, null, error);
            }
            return;
        }

        ChatCompletion.Builder result =
                ChatCompletion.builder()
                        .created(0)
                        .model(model)
                        .id(responseId)
                        .choices(
                                choiceBuffers.stream()
                                        .map(StreamedMessageBuffer::toChoice)
                                        .collect(Collectors.toList()));

        if (usage != null) {
            result.usage(usage);
        }

        if (newSpan) {
            instrumenter.end(context, request, result.build(), error);
        }
    }
}
