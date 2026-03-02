package dev.braintrust.instrumentation.anthropic.otel;

import com.anthropic.models.beta.messages.BetaMessage;
import com.anthropic.models.beta.messages.MessageCreateParams;
import com.anthropic.services.blocking.BetaService;
import io.opentelemetry.instrumentation.api.instrumenter.Instrumenter;
import java.lang.reflect.Method;

final class InstrumentedBetaService
        extends DelegatingInvocationHandler<BetaService, InstrumentedBetaService> {

    private final Instrumenter<MessageCreateParams, BetaMessage> betaMessageInstrumenter;
    private final boolean captureMessageContent;

    InstrumentedBetaService(
            BetaService delegate,
            Instrumenter<MessageCreateParams, BetaMessage> betaMessageInstrumenter,
            boolean captureMessageContent) {
        super(delegate);
        this.betaMessageInstrumenter = betaMessageInstrumenter;
        this.captureMessageContent = captureMessageContent;
    }

    @Override
    protected Class<BetaService> getProxyType() {
        return BetaService.class;
    }

    @Override
    public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
        String methodName = method.getName();
        Class<?>[] parameterTypes = method.getParameterTypes();
        if (methodName.equals("messages") && parameterTypes.length == 0) {
            return new InstrumentedBetaMessageService(
                            delegate.messages(), betaMessageInstrumenter, captureMessageContent)
                    .createProxy();
        }
        return super.invoke(proxy, method, args);
    }
}
