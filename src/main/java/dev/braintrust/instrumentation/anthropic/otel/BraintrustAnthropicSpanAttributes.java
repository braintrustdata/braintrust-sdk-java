package dev.braintrust.instrumentation.anthropic.otel;

import com.anthropic.models.messages.Message;
import com.anthropic.models.messages.MessageParam;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.opentelemetry.api.trace.Span;
import java.util.List;
import lombok.SneakyThrows;

/** Centralized class for setting all Anthropic-related span attributes. */
final class BraintrustAnthropicSpanAttributes {
    private static final ObjectMapper JSON_MAPPER = new ObjectMapper();

    // GenAI semantic convention constants
    static final String OPERATION_CHAT = "chat";
    static final String SYSTEM_ANTHROPIC = "anthropic";

    private BraintrustAnthropicSpanAttributes() {}

    /**
     * Sets the braintrust.input_json attribute with the input messages. This captures the user's
     * prompt and system messages before sending to Anthropic.
     */
    @SneakyThrows
    public static void setInputMessages(Span span, List<MessageParam> messages) {
        span.setAttribute("braintrust.input_json", JSON_MAPPER.writeValueAsString(messages));
    }

    /**
     * Sets the braintrust.output_json attribute with the output message. This captures the
     * assistant's response from Anthropic.
     */
    @SneakyThrows
    public static void setOutputMessage(Span span, Message message) {
        span.setAttribute("braintrust.output_json", JSON_MAPPER.writeValueAsString(message));
    }

    /**
     * Sets the braintrust.output_json attribute with a JSON array. This is used for streaming
     * responses where the output is built incrementally.
     */
    @SneakyThrows
    public static void setOutputJson(Span span, String outputJson) {
        span.setAttribute("braintrust.output_json", outputJson);
    }
}
