package dev.braintrust.instrumentation.anthropic.otel;

import com.anthropic.models.messages.Message;
import com.anthropic.models.messages.MessageCreateParams;
import com.anthropic.models.messages.MessageDeltaUsage;
import com.anthropic.models.messages.Model;
import com.anthropic.models.messages.RawMessageStreamEvent;
import com.anthropic.models.messages.Usage;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.context.Context;
import io.opentelemetry.instrumentation.api.instrumenter.Instrumenter;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.annotation.Nullable;
import lombok.SneakyThrows;

final class StreamListener {
    private static final ObjectMapper JSON_MAPPER = new ObjectMapper();

    private final Context context;
    private final MessageCreateParams request;
    private final Instrumenter<MessageCreateParams, Message> instrumenter;
    private final boolean captureMessageContent;
    private final boolean newSpan;
    private final AtomicBoolean hasEnded;
    private final long startTimeNanos;

    private final StringBuilder contentBuilder = new StringBuilder();

    @Nullable private Usage usage;
    @Nullable private MessageDeltaUsage deltaUsage;
    @Nullable private Model model;
    @Nullable private String responseId;
    @Nullable private String stopReason;
    @Nullable private Double timeToFirstToken;

    StreamListener(
            Context context,
            MessageCreateParams request,
            Instrumenter<MessageCreateParams, Message> instrumenter,
            boolean captureMessageContent,
            boolean newSpan,
            long startTimeNanos) {
        this.context = context;
        this.request = request;
        this.instrumenter = instrumenter;
        this.captureMessageContent = captureMessageContent;
        this.newSpan = newSpan;
        this.startTimeNanos = startTimeNanos;
        hasEnded = new AtomicBoolean();
    }

    @SneakyThrows
    void onEvent(RawMessageStreamEvent event) {
        // Capture time to first token on the first event
        if (timeToFirstToken == null) {
            long firstEventTimeNanos = System.nanoTime();
            timeToFirstToken = (firstEventTimeNanos - startTimeNanos) / 1_000_000_000.0;
        }

        // Handle message_start event
        if (event.messageStart().isPresent()) {
            var messageStart = event.messageStart().get();
            model = messageStart.message().model();
            responseId = messageStart.message().id();
            if (messageStart.message().usage() != null) {
                usage = messageStart.message().usage();
            }
        }

        // Handle content_block_delta event - accumulate text
        if (event.contentBlockDelta().isPresent()) {
            var delta = event.contentBlockDelta().get();
            if (delta.delta().text().isPresent()) {
                contentBuilder.append(delta.delta().text().get().text());
            }
        }

        // Handle message_delta event
        if (event.messageDelta().isPresent()) {
            var messageDelta = event.messageDelta().get();
            if (messageDelta.delta().stopReason().isPresent()) {
                stopReason = messageDelta.delta().stopReason().get().toString();
            }
            if (messageDelta.usage() != null) {
                deltaUsage = messageDelta.usage();
            }
        }

        // Handle content_block_stop - write output
        if (event.contentBlockStop().isPresent()) {
            ArrayNode outputArray = JSON_MAPPER.createArrayNode();
            ObjectNode message = JSON_MAPPER.createObjectNode();
            message.put("role", "assistant");
            message.put("content", contentBuilder.toString());
            outputArray.add(message);

            BraintrustAnthropicSpanAttributes.setOutputJson(
                    Span.fromContext(context), JSON_MAPPER.writeValueAsString(outputArray));
        }
    }

    void endSpan(@Nullable Throwable error) {
        // Use an atomic operation since close() type of methods are exposed to the user
        // and can come from any thread.
        if (!hasEnded.compareAndSet(false, true)) {
            return;
        }

        if (!newSpan) {
            return;
        }

        if (model == null || responseId == null) {
            // Only happens if we got no events, so we have no response.
            instrumenter.end(context, request, null, error);
            return;
        }

        // Set response attributes directly on the span since building a valid Message is complex
        // The content was already written to braintrust.output_json in onEvent
        Span span = Span.fromContext(context);

        // Set model and response ID
        if (model != null) {
            span.setAttribute("gen_ai.response.model", model.asString());
        }
        if (responseId != null) {
            span.setAttribute("gen_ai.response.id", responseId);
        }
        if (stopReason != null) {
            span.setAttribute(
                    AttributeKey.stringArrayKey("gen_ai.response.finish_reasons"),
                    Arrays.asList(stopReason));
        }

        // Set usage metrics - combine from both message_start and message_delta
        // message_start has input_tokens, message_delta has final output_tokens
        if (usage != null) {
            span.setAttribute("gen_ai.usage.input_tokens", usage.inputTokens());
        }
        if (deltaUsage != null) {
            // message_delta may also have input_tokens, prefer it if present
            deltaUsage
                    .inputTokens()
                    .ifPresent(tokens -> span.setAttribute("gen_ai.usage.input_tokens", tokens));
            span.setAttribute("gen_ai.usage.output_tokens", deltaUsage.outputTokens());
        } else if (usage != null) {
            // Fallback to usage from message_start for output_tokens if no delta
            span.setAttribute("gen_ai.usage.output_tokens", usage.outputTokens());
        }

        // Set time to first token if captured
        if (timeToFirstToken != null) {
            span.setAttribute("braintrust.metrics.time_to_first_token", timeToFirstToken);
        }

        instrumenter.end(context, request, null, error);
    }
}
