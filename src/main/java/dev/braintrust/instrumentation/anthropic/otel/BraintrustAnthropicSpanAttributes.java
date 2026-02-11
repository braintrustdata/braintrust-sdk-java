package dev.braintrust.instrumentation.anthropic.otel;

import static dev.braintrust.json.BraintrustJsonMapper.toJson;

import com.anthropic.models.messages.Message;
import com.anthropic.models.messages.MessageParam;
import io.opentelemetry.api.trace.Span;
import java.util.List;

/** Centralized class for setting all Anthropic-related span attributes. */
final class BraintrustAnthropicSpanAttributes {

    // GenAI semantic convention constants
    static final String OPERATION_CHAT = "chat";
    static final String SYSTEM_ANTHROPIC = "anthropic";

    private BraintrustAnthropicSpanAttributes() {}

    /**
     * Sets the braintrust.input_json attribute with the input messages. This captures the user's
     * prompt and system messages before sending to Anthropic.
     */
    public static void setInputMessages(Span span, List<MessageParam> messages) {
        span.setAttribute("braintrust.input_json", toJson(messages));
    }

    /**
     * Sets the braintrust.output_json attribute with the output message. This captures the
     * assistant's response from Anthropic.
     */
    public static void setOutputMessage(Span span, Message message) {
        span.setAttribute("braintrust.output_json", toJson(message));
    }

    /**
     * Sets the braintrust.output_json attribute with a JSON array. This is used for streaming
     * responses where the output is built incrementally.
     */
    public static void setOutputJson(Span span, String outputJson) {
        span.setAttribute("braintrust.output_json", outputJson);
    }
}
