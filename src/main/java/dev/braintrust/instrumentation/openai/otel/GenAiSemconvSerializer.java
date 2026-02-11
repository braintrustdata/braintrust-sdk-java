package dev.braintrust.instrumentation.openai.otel;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.JsonSerializer;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializerProvider;
import com.fasterxml.jackson.databind.module.SimpleModule;
import com.openai.models.chat.completions.*;
import dev.braintrust.json.BraintrustJsonMapper;
import dev.braintrust.trace.Base64Attachment;
import java.io.IOException;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Collectors;
import javax.annotation.Nullable;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;

@Slf4j
final class GenAiSemconvSerializer {

    private static final ObjectMapper JSON_MAPPER = createObjectMapper();

    private static ObjectMapper createObjectMapper() {
        final JsonSerializer<Base64Attachment> attachmentSerializer =
                Base64Attachment.createSerializer();
        // Start with the base mapper configuration and add OpenAI-specific serializers
        ObjectMapper mapper = BraintrustJsonMapper.get().copy();
        SimpleModule module = new SimpleModule();
        module.addSerializer(
                ChatCompletionContentPartImage.class,
                new JsonSerializer<>() {
                    @Override
                    public void serialize(
                            ChatCompletionContentPartImage value,
                            JsonGenerator gen,
                            SerializerProvider serializers)
                            throws IOException {
                        try {
                            var attachment =
                                    Base64Attachment.of(
                                            value.validate().imageUrl().validate().url());
                            attachmentSerializer.serialize(attachment, gen, serializers);
                        } catch (Exception e) {
                            JsonSerializer<Object> defaultSerializer =
                                    serializers.findValueSerializer(
                                            ChatCompletionContentPartImage.class, null);
                            defaultSerializer.serialize(value, gen, serializers);
                        }
                    }
                });
        mapper.registerModule(module);
        return mapper;
    }

    // OTel GenAI Semantic Convention structures
    static class SemconvChatMessage {
        @JsonProperty("role")
        public final String role;

        @JsonProperty("parts")
        public final List<Object> parts;

        public SemconvChatMessage(String role, List<Object> parts) {
            this.role = role;
            this.parts = parts;
        }
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    static class SemconvOutputChatMessage {
        @JsonProperty("role")
        public final String role;

        @JsonProperty("parts")
        public final List<Object> parts;

        @JsonProperty("finish_reason")
        public final String finishReason;

        public SemconvOutputChatMessage(String role, List<Object> parts, String finishReason) {
            this.role = role;
            this.parts = parts;
            this.finishReason = finishReason;
        }
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    static class TextPart {
        @JsonProperty("type")
        public final String type = "text";

        @JsonProperty("content")
        public final String content;

        public TextPart(String content) {
            this.content = content;
        }
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    static class ToolCallRequestPart {
        @JsonProperty("type")
        public final String type = "tool_call";

        @JsonProperty("name")
        public final String name;

        @JsonProperty("id")
        public final String id;

        @JsonProperty("arguments")
        public final String arguments;

        public ToolCallRequestPart(String name, String id, String arguments) {
            this.name = name;
            this.id = id;
            this.arguments = arguments;
        }
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    static class ToolCallResponsePart {
        @JsonProperty("type")
        public final String type = "tool_call_response";

        @JsonProperty("id")
        public final String id;

        @JsonProperty("response")
        public final String response;

        public ToolCallResponsePart(String id, String response) {
            this.id = id;
            this.response = response;
        }
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    static class GenericPart {
        @JsonProperty("type")
        public final String type;

        @JsonProperty("data")
        public final Object data;

        public GenericPart(String type, Object data) {
            this.type = type;
            this.data = data;
        }
    }

    // Transform OpenAI messages to OTel GenAI semconv format
    static List<SemconvChatMessage> transformToSemconvMessages(
            List<ChatCompletionMessageParam> messages) {
        return messages.stream()
                .map(GenAiSemconvSerializer::transformMessage)
                .filter(Objects::nonNull)
                .collect(Collectors.toList());
    }

    @Nullable
    private static SemconvChatMessage transformMessage(ChatCompletionMessageParam message) {
        // Try each union type accessor
        var userOpt = tryGetUser(message);
        if (userOpt.isPresent()) {
            return transformUserMessage(userOpt.get());
        }

        var systemOpt = tryGetSystem(message);
        if (systemOpt.isPresent()) {
            return transformSystemMessage(systemOpt.get());
        }

        var assistantOpt = tryGetAssistant(message);
        if (assistantOpt.isPresent()) {
            return transformAssistantMessage(assistantOpt.get());
        }

        var toolOpt = tryGetTool(message);
        if (toolOpt.isPresent()) {
            return transformToolMessage(toolOpt.get());
        }

        var developerOpt = tryGetDeveloper(message);
        if (developerOpt.isPresent()) {
            return transformDeveloperMessage(developerOpt.get());
        }

        return null;
    }

    private static Optional<ChatCompletionUserMessageParam> tryGetUser(
            ChatCompletionMessageParam message) {
        try {
            var method = message.getClass().getMethod("user");
            @SuppressWarnings("unchecked")
            var result = (Optional<ChatCompletionUserMessageParam>) method.invoke(message);
            return result;
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    private static Optional<ChatCompletionSystemMessageParam> tryGetSystem(
            ChatCompletionMessageParam message) {
        try {
            var method = message.getClass().getMethod("system");
            @SuppressWarnings("unchecked")
            var result = (Optional<ChatCompletionSystemMessageParam>) method.invoke(message);
            return result;
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    private static Optional<ChatCompletionAssistantMessageParam> tryGetAssistant(
            ChatCompletionMessageParam message) {
        try {
            var method = message.getClass().getMethod("assistant");
            @SuppressWarnings("unchecked")
            var result = (Optional<ChatCompletionAssistantMessageParam>) method.invoke(message);
            return result;
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    private static Optional<ChatCompletionToolMessageParam> tryGetTool(
            ChatCompletionMessageParam message) {
        try {
            var method = message.getClass().getMethod("tool");
            @SuppressWarnings("unchecked")
            var result = (Optional<ChatCompletionToolMessageParam>) method.invoke(message);
            return result;
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    private static Optional<ChatCompletionDeveloperMessageParam> tryGetDeveloper(
            ChatCompletionMessageParam message) {
        try {
            var method = message.getClass().getMethod("developer");
            @SuppressWarnings("unchecked")
            var result = (Optional<ChatCompletionDeveloperMessageParam>) method.invoke(message);
            return result;
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    private static SemconvChatMessage transformUserMessage(ChatCompletionUserMessageParam message) {
        List<Object> parts = new ArrayList<>();
        ChatCompletionUserMessageParam.Content content = message.content();

        if (content.isText()) {
            parts.add(new TextPart(content.asText()));
        } else if (content.isArrayOfContentParts()) {
            for (var part : content.asArrayOfContentParts()) {
                if (part.isText()) {
                    parts.add(new TextPart(part.asText().text()));
                } else {
                    // Try to get image part using union type accessor
                    var imageOpt = tryGetImagePart(part);
                    if (imageOpt.isPresent()) {
                        try {
                            var imageUrl = imageOpt.get().imageUrl().url();
                            var attachment = Base64Attachment.of(imageUrl);
                            parts.add(attachment);
                        } catch (Exception e) {
                            log.debug("Failed to parse image URL", e);
                        }
                    }
                }
            }
        }

        return new SemconvChatMessage("user", parts);
    }

    private static Optional<ChatCompletionContentPartImage> tryGetImagePart(
            com.openai.models.chat.completions.ChatCompletionContentPart part) {
        try {
            var method = part.getClass().getMethod("imageUrl");
            @SuppressWarnings("unchecked")
            var result = (Optional<ChatCompletionContentPartImage>) method.invoke(part);
            return result;
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    private static SemconvChatMessage transformSystemMessage(
            ChatCompletionSystemMessageParam message) {
        List<Object> parts = new ArrayList<>();
        ChatCompletionSystemMessageParam.Content content = message.content();

        if (content.isText()) {
            parts.add(new TextPart(content.asText()));
        } else if (content.isArrayOfContentParts()) {
            for (var part : content.asArrayOfContentParts()) {
                parts.add(new TextPart(part.text()));
            }
        }

        return new SemconvChatMessage("system", parts);
    }

    private static SemconvChatMessage transformDeveloperMessage(
            ChatCompletionDeveloperMessageParam message) {
        List<Object> parts = new ArrayList<>();
        ChatCompletionDeveloperMessageParam.Content content = message.content();

        if (content.isText()) {
            parts.add(new TextPart(content.asText()));
        } else if (content.isArrayOfContentParts()) {
            for (var part : content.asArrayOfContentParts()) {
                parts.add(new TextPart(part.text()));
            }
        }

        return new SemconvChatMessage("system", parts);
    }

    private static SemconvChatMessage transformAssistantMessage(
            ChatCompletionAssistantMessageParam message) {
        List<Object> parts = new ArrayList<>();

        // Handle text content
        message.content()
                .ifPresent(
                        content -> {
                            if (content.isText()) {
                                parts.add(new TextPart(content.asText()));
                            } else if (content.isArrayOfContentParts()) {
                                for (var part : content.asArrayOfContentParts()) {
                                    if (part.isText()) {
                                        parts.add(new TextPart(part.asText().text()));
                                    } else if (part.isRefusal()) {
                                        parts.add(new TextPart(part.asRefusal().refusal()));
                                    }
                                }
                            }
                        });

        // Handle tool calls
        message.toolCalls()
                .ifPresent(
                        toolCalls -> {
                            for (var toolCall : toolCalls) {
                                FunctionAccess functionAccess = getFunctionAccess(toolCall);
                                if (functionAccess != null) {
                                    parts.add(
                                            new ToolCallRequestPart(
                                                    functionAccess.name(),
                                                    functionAccess.id(),
                                                    functionAccess.arguments()));
                                }
                            }
                        });

        return new SemconvChatMessage("assistant", parts);
    }

    private static SemconvChatMessage transformToolMessage(ChatCompletionToolMessageParam message) {
        List<Object> parts = new ArrayList<>();
        String toolCallId = message.toolCallId();
        ChatCompletionToolMessageParam.Content content = message.content();

        String responseContent = "";
        if (content.isText()) {
            responseContent = content.asText();
        } else if (content.isArrayOfContentParts()) {
            responseContent = joinContentParts(content.asArrayOfContentParts());
        }

        parts.add(new ToolCallResponsePart(toolCallId, responseContent));
        return new SemconvChatMessage("tool", parts);
    }

    // Transform ChatCompletionMessage (output) to OTel GenAI semconv format
    static SemconvOutputChatMessage transformOutputMessage(
            ChatCompletionMessage message, String finishReason) {
        List<Object> parts = new ArrayList<>();

        // Handle text content
        message.content()
                .ifPresent(
                        content -> {
                            if (!content.isEmpty()) {
                                parts.add(new TextPart(content));
                            }
                        });

        // Handle tool calls
        message.toolCalls()
                .ifPresent(
                        toolCalls -> {
                            for (var toolCall : toolCalls) {
                                FunctionAccess functionAccess = getFunctionAccess(toolCall);
                                if (functionAccess != null) {
                                    parts.add(
                                            new ToolCallRequestPart(
                                                    functionAccess.name(),
                                                    functionAccess.id(),
                                                    functionAccess.arguments()));
                                }
                            }
                        });

        // The role from ChatCompletionMessage is always "assistant" for output messages
        return new SemconvOutputChatMessage("assistant", parts, finishReason);
    }

    private static String joinContentParts(List<ChatCompletionContentPartText> contentParts) {
        return contentParts.stream()
                .map(ChatCompletionContentPartText::text)
                .collect(Collectors.joining());
    }

    @SneakyThrows
    static String serializeInputMessages(List<ChatCompletionMessageParam> messages) {
        List<SemconvChatMessage> semconvMessages = transformToSemconvMessages(messages);
        return JSON_MAPPER.writeValueAsString(semconvMessages);
    }

    @SneakyThrows
    static String serializeOutputMessages(List<ChatCompletion.Choice> choices) {
        var semConvMessages =
                choices.stream()
                        .map(c -> transformOutputMessage(c.message(), c.finishReason().toString()))
                        .toList();
        return JSON_MAPPER.writeValueAsString(semConvMessages);
    }

    @Nullable
    static FunctionAccess getFunctionAccess(ChatCompletionMessageToolCall call) {
        if (V1FunctionAccess.isAvailable()) {
            return V1FunctionAccess.create(call);
        }
        if (V3FunctionAccess.isAvailable()) {
            return V3FunctionAccess.create(call);
        }

        return null;
    }

    interface FunctionAccess {
        String id();

        String name();

        String arguments();
    }

    private static String invokeStringHandle(@Nullable MethodHandle methodHandle, Object object) {
        if (methodHandle == null) {
            return "";
        }

        try {
            return (String) methodHandle.invoke(object);
        } catch (Throwable ignore) {
            return "";
        }
    }

    private static class V1FunctionAccess implements FunctionAccess {
        @Nullable private static final MethodHandle idHandle;
        @Nullable private static final MethodHandle functionHandle;
        @Nullable private static final MethodHandle nameHandle;
        @Nullable private static final MethodHandle argumentsHandle;

        static {
            MethodHandle id;
            MethodHandle function;
            MethodHandle name;
            MethodHandle arguments;

            try {
                MethodHandles.Lookup lookup = MethodHandles.lookup();
                id =
                        lookup.findVirtual(
                                ChatCompletionMessageToolCall.class,
                                "id",
                                MethodType.methodType(String.class));
                Class<?> functionClass =
                        Class.forName(
                                "com.openai.models.chat.completions.ChatCompletionMessageToolCall$Function");
                function =
                        lookup.findVirtual(
                                ChatCompletionMessageToolCall.class,
                                "function",
                                MethodType.methodType(functionClass));
                name =
                        lookup.findVirtual(
                                functionClass, "name", MethodType.methodType(String.class));
                arguments =
                        lookup.findVirtual(
                                functionClass, "arguments", MethodType.methodType(String.class));
            } catch (Exception exception) {
                id = null;
                function = null;
                name = null;
                arguments = null;
            }
            idHandle = id;
            functionHandle = function;
            nameHandle = name;
            argumentsHandle = arguments;
        }

        private final ChatCompletionMessageToolCall toolCall;
        private final Object function;

        V1FunctionAccess(ChatCompletionMessageToolCall toolCall, Object function) {
            this.toolCall = toolCall;
            this.function = function;
        }

        @Nullable
        static FunctionAccess create(ChatCompletionMessageToolCall toolCall) {
            if (functionHandle == null) {
                return null;
            }

            try {
                return new V1FunctionAccess(toolCall, functionHandle.invoke(toolCall));
            } catch (Throwable ignore) {
                return null;
            }
        }

        static boolean isAvailable() {
            return idHandle != null;
        }

        @Override
        public String id() {
            return invokeStringHandle(idHandle, toolCall);
        }

        @Override
        public String name() {
            return invokeStringHandle(nameHandle, function);
        }

        @Override
        public String arguments() {
            return invokeStringHandle(argumentsHandle, function);
        }
    }

    static class V3FunctionAccess implements FunctionAccess {
        @Nullable private static final MethodHandle functionToolCallHandle;
        @Nullable private static final MethodHandle idHandle;
        @Nullable private static final MethodHandle functionHandle;
        @Nullable private static final MethodHandle nameHandle;
        @Nullable private static final MethodHandle argumentsHandle;

        static {
            MethodHandle functionToolCall;
            MethodHandle id;
            MethodHandle function;
            MethodHandle name;
            MethodHandle arguments;

            try {
                MethodHandles.Lookup lookup = MethodHandles.lookup();
                functionToolCall =
                        lookup.findVirtual(
                                ChatCompletionMessageToolCall.class,
                                "function",
                                MethodType.methodType(Optional.class));
                Class<?> functionToolCallClass =
                        Class.forName(
                                "com.openai.models.chat.completions.ChatCompletionMessageFunctionToolCall");
                id =
                        lookup.findVirtual(
                                functionToolCallClass, "id", MethodType.methodType(String.class));
                Class<?> functionClass =
                        Class.forName(
                                "com.openai.models.chat.completions.ChatCompletionMessageFunctionToolCall$Function");
                function =
                        lookup.findVirtual(
                                functionToolCallClass,
                                "function",
                                MethodType.methodType(functionClass));
                name =
                        lookup.findVirtual(
                                functionClass, "name", MethodType.methodType(String.class));
                arguments =
                        lookup.findVirtual(
                                functionClass, "arguments", MethodType.methodType(String.class));
            } catch (Exception exception) {
                functionToolCall = null;
                id = null;
                function = null;
                name = null;
                arguments = null;
            }
            functionToolCallHandle = functionToolCall;
            idHandle = id;
            functionHandle = function;
            nameHandle = name;
            argumentsHandle = arguments;
        }

        private final Object functionToolCall;
        private final Object function;

        V3FunctionAccess(Object functionToolCall, Object function) {
            this.functionToolCall = functionToolCall;
            this.function = function;
        }

        @Nullable
        @SuppressWarnings("unchecked")
        static FunctionAccess create(ChatCompletionMessageToolCall toolCall) {
            if (functionToolCallHandle == null || functionHandle == null) {
                return null;
            }

            try {
                Optional<Object> optional =
                        (Optional<Object>) functionToolCallHandle.invoke(toolCall);
                if (!optional.isPresent()) {
                    return null;
                }
                Object functionToolCall = optional.get();
                return new V3FunctionAccess(
                        functionToolCall, functionHandle.invoke(functionToolCall));
            } catch (Throwable ignore) {
                return null;
            }
        }

        static boolean isAvailable() {
            return idHandle != null;
        }

        @Override
        public String id() {
            return invokeStringHandle(idHandle, functionToolCall);
        }

        @Override
        public String name() {
            return invokeStringHandle(nameHandle, function);
        }

        @Override
        public String arguments() {
            return invokeStringHandle(argumentsHandle, function);
        }
    }

    private GenAiSemconvSerializer() {}
}
