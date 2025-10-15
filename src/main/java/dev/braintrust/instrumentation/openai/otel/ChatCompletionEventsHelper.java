/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package dev.braintrust.instrumentation.openai.otel;

import static io.opentelemetry.api.common.AttributeKey.stringKey;

import com.openai.models.chat.completions.ChatCompletion;
import com.openai.models.chat.completions.ChatCompletionAssistantMessageParam;
import com.openai.models.chat.completions.ChatCompletionContentPartText;
import com.openai.models.chat.completions.ChatCompletionCreateParams;
import com.openai.models.chat.completions.ChatCompletionDeveloperMessageParam;
import com.openai.models.chat.completions.ChatCompletionMessage;
import com.openai.models.chat.completions.ChatCompletionMessageToolCall;
import com.openai.models.chat.completions.ChatCompletionSystemMessageParam;
import com.openai.models.chat.completions.ChatCompletionToolMessageParam;
import com.openai.models.chat.completions.ChatCompletionUserMessageParam;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Value;
import io.opentelemetry.api.logs.LogRecordBuilder;
import io.opentelemetry.api.logs.Logger;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.context.Context;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;

@Slf4j
final class ChatCompletionEventsHelper {

    private static final AttributeKey<String> EVENT_NAME = stringKey("event.name");

    @SneakyThrows
    public static void emitPromptLogEvents(
            Context context,
            Logger eventLogger,
            ChatCompletionCreateParams request,
            boolean captureMessageContent) {
        String semconvJson = GenAiSemconvSerializer.serializeInputMessages(request.messages());
        Span span = Span.current();
        span.setAttribute("gen_ai.input.messages", semconvJson);
    }

    private static String contentToString(ChatCompletionToolMessageParam.Content content) {
        if (content.isText()) {
            return content.asText();
        } else if (content.isArrayOfContentParts()) {
            return joinContentParts(content.asArrayOfContentParts());
        } else {
            return "";
        }
    }

    private static String contentToString(ChatCompletionAssistantMessageParam.Content content) {
        if (content.isText()) {
            return content.asText();
        } else if (content.isArrayOfContentParts()) {
            return content.asArrayOfContentParts().stream()
                    .map(
                            part -> {
                                if (part.isText()) {
                                    return part.asText().text();
                                }
                                if (part.isRefusal()) {
                                    return part.asRefusal().refusal();
                                }
                                return null;
                            })
                    .filter(Objects::nonNull)
                    .collect(Collectors.joining());
        } else {
            return "";
        }
    }

    private static String contentToString(ChatCompletionSystemMessageParam.Content content) {
        if (content.isText()) {
            return content.asText();
        } else if (content.isArrayOfContentParts()) {
            return joinContentParts(content.asArrayOfContentParts());
        } else {
            return "";
        }
    }

    private static String contentToString(ChatCompletionDeveloperMessageParam.Content content) {
        if (content.isText()) {
            return content.asText();
        } else if (content.isArrayOfContentParts()) {
            return joinContentParts(content.asArrayOfContentParts());
        } else {
            return "";
        }
    }

    private static String contentToString(ChatCompletionUserMessageParam.Content content) {
        if (content.isText()) {
            return content.asText();
        } else if (content.isArrayOfContentParts()) {
            return content.asArrayOfContentParts().stream()
                    .map(part -> part.isText() ? part.asText().text() : null)
                    .filter(Objects::nonNull)
                    .collect(Collectors.joining());
        } else {
            return "";
        }
    }

    private static String joinContentParts(List<ChatCompletionContentPartText> contentParts) {
        return contentParts.stream()
                .map(ChatCompletionContentPartText::text)
                .collect(Collectors.joining());
    }

    @SneakyThrows
    public static void emitCompletionLogEvents(
            Context context,
            Logger eventLogger,
            ChatCompletion completion,
            boolean captureMessageContent) {
        if (completion.choices().isEmpty()) {
            log.debug("no choices in OAI response");
        } else if (completion.choices().size() > 1) {
            log.debug("multiple choices in OAI response: {}", completion.choices().size());
        } else {
            // Set gen_ai.output.messages attribute for single choice (most common case)
            ChatCompletion.Choice choice = completion.choices().get(0);
            String outputJson =
                    GenAiSemconvSerializer.serializeOutputMessage(
                            choice.message(), choice.finishReason().toString());
            Span.current().setAttribute("gen_ai.output.messages", outputJson);
        }
        for (ChatCompletion.Choice choice : completion.choices()) {
            ChatCompletionMessage choiceMsg = choice.message();
            Map<String, Value<?>> message = new HashMap<>();
            if (captureMessageContent) {
                choiceMsg
                        .content()
                        .ifPresent(
                                content -> {
                                    message.put("content", Value.of(content));
                                });
            }
            choiceMsg
                    .toolCalls()
                    .ifPresent(
                            toolCalls -> {
                                message.put(
                                        "tool_calls",
                                        Value.of(
                                                toolCalls.stream()
                                                        .map(
                                                                call ->
                                                                        buildToolCallEventObject(
                                                                                call,
                                                                                captureMessageContent))
                                                        .collect(Collectors.toList())));
                            });
            emitCompletionLogEvent(
                    context,
                    eventLogger,
                    choice.index(),
                    choice.finishReason().toString(),
                    Value.of(message));
        }
    }

    public static void emitCompletionLogEvent(
            Context context,
            Logger eventLogger,
            long index,
            String finishReason,
            Value<?> eventMessageObject) {
        Map<String, Value<?>> body = new HashMap<>();
        body.put("finish_reason", Value.of(finishReason));
        body.put("index", Value.of(index));
        body.put("message", eventMessageObject);
        // newEvent(eventLogger,
        // "gen_ai.choice").setContext(context).setBody(Value.of(body)).emit();
    }

    private static LogRecordBuilder newEvent(Logger eventLogger, String name) {
        // NOTE: disabling logger events in braintrust instrumentation. We don't use these events.
        // Will have to properly hanlde this if we want to merge braintrust attributes upstream into
        // otel instrumentation
        /*
        return eventLogger
                .logRecordBuilder()
                .setAttribute(EVENT_NAME, name)
                .setAttribute(GEN_AI_PROVIDER_NAME, "openai");
         */
        throw new RuntimeException("Should not invoke");
    }

    private static Value<?> buildToolCallEventObject(
            ChatCompletionMessageToolCall call, boolean captureMessageContent) {
        Map<String, Value<?>> result = new HashMap<>();
        GenAiSemconvSerializer.FunctionAccess functionAccess =
                GenAiSemconvSerializer.getFunctionAccess(call);
        if (functionAccess != null) {
            result.put("id", Value.of(functionAccess.id()));
            result.put(
                    "type",
                    Value.of("function")); // "function" is the only currently supported type
            result.put("function", buildFunctionEventObject(functionAccess, captureMessageContent));
        }
        return Value.of(result);
    }

    private static Value<?> buildFunctionEventObject(
            GenAiSemconvSerializer.FunctionAccess functionAccess, boolean captureMessageContent) {
        Map<String, Value<?>> result = new HashMap<>();
        result.put("name", Value.of(functionAccess.name()));
        if (captureMessageContent) {
            result.put("arguments", Value.of(functionAccess.arguments()));
        }
        return Value.of(result);
    }

    private ChatCompletionEventsHelper() {}
}
