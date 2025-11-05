package dev.braintrust.instrumentation.anthropic.otel;

import com.anthropic.core.RequestOptions;
import com.anthropic.core.http.StreamResponse;
import com.anthropic.models.messages.Message;
import com.anthropic.models.messages.MessageCreateParams;
import com.anthropic.models.messages.MessageParam;
import com.anthropic.models.messages.RawMessageStreamEvent;
import com.anthropic.models.messages.TextBlockParam;
import com.anthropic.services.blocking.MessageService;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.Scope;
import io.opentelemetry.instrumentation.api.instrumenter.Instrumenter;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

final class InstrumentedMessageService
        extends DelegatingInvocationHandler<MessageService, InstrumentedMessageService> {

    private final Instrumenter<MessageCreateParams, Message> instrumenter;
    private final boolean captureMessageContent;

    InstrumentedMessageService(
            MessageService delegate,
            Instrumenter<MessageCreateParams, Message> instrumenter,
            boolean captureMessageContent) {
        super(delegate);
        this.instrumenter = instrumenter;
        this.captureMessageContent = captureMessageContent;
    }

    @Override
    protected Class<MessageService> getProxyType() {
        return MessageService.class;
    }

    @Override
    public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
        String methodName = method.getName();
        Class<?>[] parameterTypes = method.getParameterTypes();

        switch (methodName) {
            case "create":
                if (parameterTypes.length >= 1 && parameterTypes[0] == MessageCreateParams.class) {
                    if (parameterTypes.length == 1) {
                        return create((MessageCreateParams) args[0], RequestOptions.none());
                    } else if (parameterTypes.length == 2
                            && parameterTypes[1] == RequestOptions.class) {
                        return create((MessageCreateParams) args[0], (RequestOptions) args[1]);
                    }
                }
                break;
            case "createStreaming":
                if (parameterTypes.length >= 1 && parameterTypes[0] == MessageCreateParams.class) {
                    if (parameterTypes.length == 1) {
                        return createStreaming(
                                (MessageCreateParams) args[0], RequestOptions.none());
                    } else if (parameterTypes.length == 2
                            && parameterTypes[1] == RequestOptions.class) {
                        return createStreaming(
                                (MessageCreateParams) args[0], (RequestOptions) args[1]);
                    }
                }
                break;
            default:
                // fallthrough
        }

        return super.invoke(proxy, method, args);
    }

    private Message create(MessageCreateParams inputMessage, RequestOptions requestOptions) {
        Context parentContext = Context.current();
        if (!instrumenter.shouldStart(parentContext, inputMessage)) {
            return delegate.create(inputMessage, requestOptions);
        }

        Context context = instrumenter.start(parentContext, inputMessage);
        Message outputMessage;
        try (Scope ignored = context.makeCurrent()) {
            List<MessageParam> inputMessages = new ArrayList<>(inputMessage.messages());
            // Put system in the input message so the backend will pick it up in the LLM display
            if (inputMessage.system().isPresent()) {
                inputMessages.add(
                        0,
                        MessageParam.builder()
                                .role(MessageParam.Role.of("system"))
                                .content(inputMessage.system().get().asString())
                                .build());
            }
            BraintrustAnthropicSpanAttributes.setInputMessages(Span.current(), inputMessages);
            outputMessage = delegate.create(inputMessage, requestOptions);
            BraintrustAnthropicSpanAttributes.setOutputMessage(Span.current(), outputMessage);
        } catch (Throwable t) {
            instrumenter.end(context, inputMessage, null, t);
            throw t;
        }

        instrumenter.end(context, inputMessage, outputMessage, null);
        return outputMessage;
    }

    private StreamResponse<RawMessageStreamEvent> createStreaming(
            MessageCreateParams inputMessage, RequestOptions requestOptions) {
        Context parentContext = Context.current();
        if (!instrumenter.shouldStart(parentContext, inputMessage)) {
            return createStreamingWithAttributes(
                    parentContext, inputMessage, requestOptions, false);
        }

        Context context = instrumenter.start(parentContext, inputMessage);
        try (Scope ignored = context.makeCurrent()) {
            return createStreamingWithAttributes(context, inputMessage, requestOptions, true);
        } catch (Throwable t) {
            instrumenter.end(context, inputMessage, null, t);
            throw t;
        }
    }

    private StreamResponse<RawMessageStreamEvent> createStreamingWithAttributes(
            Context context,
            MessageCreateParams inputMessage,
            RequestOptions requestOptions,
            boolean newSpan) {
        List<MessageParam> inputMessages = new ArrayList<>(inputMessage.messages());
        // Put system in the input message so the backend will pick it up in the LLM display
        if (inputMessage.system().isPresent()) {
            inputMessages.add(
                    0,
                    MessageParam.builder()
                            .role(MessageParam.Role.of("system"))
                            .content(inputMessage.system().get().asString())
                            .build());
        }
        BraintrustAnthropicSpanAttributes.setInputMessages(
                Span.fromContext(context), inputMessages);

        StreamResponse<RawMessageStreamEvent> result =
                delegate.createStreaming(inputMessage, requestOptions);
        return new TracingStreamedResponse(
                result,
                new StreamListener(
                        context, inputMessage, instrumenter, captureMessageContent, newSpan));
    }

    private static String contentToString(MessageCreateParams.System content) {
        if (content.isString()) {
            return content.asString();
        } else if (content.isTextBlockParams()) {
            return content.asTextBlockParams().stream()
                    .map(TextBlockParam::text)
                    .collect(Collectors.joining());
        }
        return "";
    }
}
