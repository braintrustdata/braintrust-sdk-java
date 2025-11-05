package dev.braintrust.instrumentation.anthropic.otel;

import com.anthropic.client.AnthropicClient;
import com.anthropic.models.messages.Message;
import com.anthropic.models.messages.MessageCreateParams;
import io.opentelemetry.instrumentation.api.instrumenter.Instrumenter;
import java.lang.reflect.Method;

final class InstrumentedAnthropicClient
        extends DelegatingInvocationHandler<AnthropicClient, InstrumentedAnthropicClient> {

    private final Instrumenter<MessageCreateParams, Message> messageInstrumenter;
    private final boolean captureMessageContent;

    InstrumentedAnthropicClient(
            AnthropicClient delegate,
            Instrumenter<MessageCreateParams, Message> messageInstrumenter,
            boolean captureMessageContent) {
        super(delegate);
        this.messageInstrumenter = messageInstrumenter;
        this.captureMessageContent = captureMessageContent;
    }

    @Override
    protected Class<AnthropicClient> getProxyType() {
        return AnthropicClient.class;
    }

    @Override
    public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
        String methodName = method.getName();
        Class<?>[] parameterTypes = method.getParameterTypes();
        if (methodName.equals("messages") && parameterTypes.length == 0) {
            return new InstrumentedMessageService(
                            delegate.messages(), messageInstrumenter, captureMessageContent)
                    .createProxy();
        }
        return super.invoke(proxy, method, args);
    }
}
