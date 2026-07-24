package dev.braintrust.sdkspecimpl.springai2;

import dev.braintrust.instrumentation.springai.v2_0_0.BraintrustSpringAI;
import dev.braintrust.sdkspecimpl.LlmSpanSpec;
import dev.braintrust.sdkspecimpl.SpecClient;
import dev.braintrust.sdkspecimpl.SpecClientContext;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import org.springframework.ai.anthropic.AnthropicChatModel;
import org.springframework.ai.anthropic.AnthropicChatOptions;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.content.Media;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.util.MimeType;

/**
 * Spring AI 2.x Anthropic client. Runs inside the isolated {@code springai2} classloader (see
 * {@code SpecClient.isolation()}); drives the real user path — {@code AnthropicChatModel
 * .call/stream} — with spec requests translated into {@link Prompt} + {@link AnthropicChatOptions}.
 */
public final class SpringAi2AnthropicSpecClient implements SpecClient {

    @Override
    public String id() {
        return "springai2-anthropic";
    }

    @Override
    public String provider() {
        return "anthropic";
    }

    @Override
    public void executeSpec(LlmSpanSpec spec, SpecClientContext ctx) throws Exception {
        for (Map<String, Object> request : spec.requests()) {
            executeMessages(ctx, spec, request);
        }
    }

    private void executeMessages(
            SpecClientContext ctx, LlmSpanSpec spec, Map<String, Object> request) throws Exception {
        boolean stream = Boolean.TRUE.equals(request.get("stream"));

        // In Spring AI 2.0, connection details (apiKey/baseUrl) live on the chat options and
        // the model builder constructs the official anthropic-java client internally.
        var options = AnthropicChatOptions.builder();
        options.apiKey(ctx.anthropicApiKey());
        options.baseUrl(ctx.anthropicBaseUrl());
        options.model((String) request.get("model"));
        if (request.get("temperature") instanceof Number temperature) {
            options.temperature(temperature.doubleValue());
        }
        if (request.get("max_tokens") instanceof Number maxTokens) {
            options.maxTokens(maxTokens.intValue());
        }
        if (spec.headers() != null && !spec.headers().isEmpty()) {
            options.httpHeaders(spec.headers());
        }

        var model = AnthropicChatModel.builder().options(options.build()).build();
        BraintrustSpringAI.wrap(ctx.otel(), model);

        List<Message> messages = new ArrayList<>();
        if (request.get("system") instanceof String system) {
            messages.add(new SystemMessage(system));
        }
        messages.addAll(mapMessages(request.get("messages")));

        Prompt prompt = new Prompt(messages);
        if (stream) {
            model.stream(prompt).blockLast();
        } else {
            model.call(prompt);
        }
    }

    private static List<Message> mapMessages(Object messagesObj) {
        List<Message> out = new ArrayList<>();
        for (Object m : (List<?>) messagesObj) {
            @SuppressWarnings("unchecked")
            Map<String, Object> msg = (Map<String, Object>) m;
            String role = (String) msg.get("role");
            Object content = msg.get("content");
            switch (role) {
                case "user" -> out.add(mapUserMessage(content));
                case "assistant" -> out.add(new AssistantMessage((String) content));
                default ->
                        throw new UnsupportedOperationException(
                                "Unsupported message role for springai2: " + role);
            }
        }
        return out;
    }

    private static UserMessage mapUserMessage(Object content) {
        if (content instanceof String text) {
            return new UserMessage(text);
        }
        StringBuilder text = new StringBuilder();
        List<Media> media = new ArrayList<>();
        for (Object p : (List<?>) content) {
            @SuppressWarnings("unchecked")
            Map<String, Object> part = (Map<String, Object>) p;
            String type = (String) part.get("type");
            switch (type) {
                case "text" -> text.append(part.get("text"));
                    // Anthropic image and document blocks both carry a base64 `source`;
                    // Spring AI maps Media back to the right block type by MIME type.
                case "image", "document" -> {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> source = (Map<String, Object>) part.get("source");
                    byte[] bytes = Base64.getDecoder().decode((String) source.get("data"));
                    media.add(
                            new Media(
                                    MimeType.valueOf((String) source.get("media_type")),
                                    new ByteArrayResource(bytes)));
                }
                default ->
                        throw new UnsupportedOperationException(
                                "Unsupported content part for springai2: " + type);
            }
        }
        return UserMessage.builder().text(text.toString()).media(media).build();
    }
}
