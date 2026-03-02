package dev.braintrust.instrumentation.anthropic.otel;

import static dev.braintrust.json.BraintrustJsonMapper.toJson;

import com.anthropic.core.RequestOptions;
import com.anthropic.core.http.StreamResponse;
import com.anthropic.models.beta.messages.BetaMessage;
import com.anthropic.models.beta.messages.BetaMessageParam;
import com.anthropic.models.beta.messages.BetaRawMessageStreamEvent;
import com.anthropic.models.beta.messages.BetaTextBlockParam;
import com.anthropic.models.beta.messages.MessageCreateParams;
import com.anthropic.services.blocking.beta.MessageService;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.Scope;
import io.opentelemetry.instrumentation.api.instrumenter.Instrumenter;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

final class InstrumentedBetaMessageService
        extends DelegatingInvocationHandler<MessageService, InstrumentedBetaMessageService> {

    private final Instrumenter<MessageCreateParams, BetaMessage> instrumenter;
    private final boolean captureMessageContent;

    InstrumentedBetaMessageService(
            MessageService delegate,
            Instrumenter<MessageCreateParams, BetaMessage> instrumenter,
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

    private BetaMessage create(MessageCreateParams inputMessage, RequestOptions requestOptions) {
        Context parentContext = Context.current();
        if (!instrumenter.shouldStart(parentContext, inputMessage)) {
            return delegate.create(inputMessage, requestOptions);
        }

        Context context = instrumenter.start(parentContext, inputMessage);
        long startTimeNanos = System.nanoTime();
        BetaMessage outputMessage;
        try (Scope ignored = context.makeCurrent()) {
            Span currentSpan = Span.current();
            // Set provider metadata
            currentSpan.setAttribute("provider", "anthropic");

            List<BetaMessageParam> inputMessages = new ArrayList<>(inputMessage.messages());
            // Append system message to the end so the backend will pick it up in the LLM display
            if (inputMessage.system().isPresent()) {
                inputMessages.add(
                        BetaMessageParam.builder()
                                .role(BetaMessageParam.Role.of("system"))
                                .content(
                                        BetaMessageParam.Content.ofString(
                                                betaSystemToString(inputMessage.system().get())))
                                .build());
            }
            currentSpan.setAttribute("braintrust.input_json", toJson(inputMessages));
            outputMessage = delegate.create(inputMessage, requestOptions);
            long endTimeNanos = System.nanoTime();
            double timeToFirstTokenSeconds = (endTimeNanos - startTimeNanos) / 1_000_000_000.0;
            currentSpan.setAttribute(
                    "braintrust.metrics.time_to_first_token", timeToFirstTokenSeconds);
            BraintrustAnthropicSpanAttributes.setOutputMessage(currentSpan, outputMessage);
        } catch (Throwable t) {
            instrumenter.end(context, inputMessage, null, t);
            throw t;
        }

        instrumenter.end(context, inputMessage, outputMessage, null);
        return outputMessage;
    }

    private StreamResponse<BetaRawMessageStreamEvent> createStreaming(
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

    private StreamResponse<BetaRawMessageStreamEvent> createStreamingWithAttributes(
            Context context,
            MessageCreateParams inputMessage,
            RequestOptions requestOptions,
            boolean newSpan) {
        Span span = Span.fromContext(context);
        // Set provider metadata
        span.setAttribute("provider", "anthropic");

        List<BetaMessageParam> inputMessages = new ArrayList<>(inputMessage.messages());
        // Append system message to the end so the backend will pick it up in the LLM display
        if (inputMessage.system().isPresent()) {
            inputMessages.add(
                    BetaMessageParam.builder()
                            .role(BetaMessageParam.Role.of("system"))
                            .content(
                                    BetaMessageParam.Content.ofString(
                                            betaSystemToString(inputMessage.system().get())))
                            .build());
        }
        span.setAttribute("braintrust.input_json", toJson(inputMessages));

        long startTimeNanos = System.nanoTime();
        StreamResponse<BetaRawMessageStreamEvent> result =
                delegate.createStreaming(inputMessage, requestOptions);
        return new BetaTracingStreamedResponse(
                result,
                new BetaStreamListener(
                        context,
                        inputMessage,
                        instrumenter,
                        captureMessageContent,
                        newSpan,
                        startTimeNanos));
    }

    private static String betaSystemToString(MessageCreateParams.System system) {
        if (system.isString()) {
            return system.asString();
        } else if (system.isBetaTextBlockParams()) {
            return system.asBetaTextBlockParams().stream()
                    .map(BetaTextBlockParam::text)
                    .collect(Collectors.joining());
        }
        return "";
    }
}
