/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package dev.braintrust.instrumentation.openai.otel;

import com.openai.client.OpenAIClient;
import com.openai.models.chat.completions.ChatCompletion;
import com.openai.models.chat.completions.ChatCompletionCreateParams;
import com.openai.models.embeddings.CreateEmbeddingResponse;
import com.openai.models.embeddings.EmbeddingCreateParams;
import io.opentelemetry.instrumentation.api.instrumenter.Instrumenter;
import java.lang.reflect.Method;

final class InstrumentedOpenAiClient
        extends DelegatingInvocationHandler<OpenAIClient, InstrumentedOpenAiClient> {

    private final Instrumenter<ChatCompletionCreateParams, ChatCompletion> chatInstrumenter;
    private final Instrumenter<EmbeddingCreateParams, CreateEmbeddingResponse>
            embeddingInstrumenter;
    private final boolean captureMessageContent;

    InstrumentedOpenAiClient(
            OpenAIClient delegate,
            Instrumenter<ChatCompletionCreateParams, ChatCompletion> chatInstrumenter,
            Instrumenter<EmbeddingCreateParams, CreateEmbeddingResponse> embeddingInstrumenter,
            boolean captureMessageContent) {
        super(delegate);
        this.chatInstrumenter = chatInstrumenter;
        this.embeddingInstrumenter = embeddingInstrumenter;
        this.captureMessageContent = captureMessageContent;
    }

    @Override
    protected Class<OpenAIClient> getProxyType() {
        return OpenAIClient.class;
    }

    @Override
    public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
        String methodName = method.getName();
        Class<?>[] parameterTypes = method.getParameterTypes();
        if (methodName.equals("chat") && parameterTypes.length == 0) {
            return createChatServiceProxy();
        }
        if (methodName.equals("embeddings") && parameterTypes.length == 0) {
            return new InstrumentedEmbeddingService(delegate.embeddings(), embeddingInstrumenter)
                    .createProxy();
        }
        if (methodName.equals("async") && parameterTypes.length == 0) {
            return new InstrumentedOpenAiClientAsync(
                            delegate.async(),
                            chatInstrumenter,
                            embeddingInstrumenter,
                            captureMessageContent)
                    .createProxy();
        }
        return super.invoke(proxy, method, args);
    }

    private Object createChatServiceProxy() {
        return java.lang.reflect.Proxy.newProxyInstance(
                com.openai.services.blocking.ChatService.class.getClassLoader(),
                new Class<?>[] {com.openai.services.blocking.ChatService.class},
                (p, m, a) -> {
                    if ("completions".equals(m.getName()) && m.getParameterCount() == 0) {
                        return new InstrumentedChatCompletionService(
                                        delegate.chat().completions(),
                                        chatInstrumenter,
                                        captureMessageContent)
                                .createProxy();
                    }
                    return m.invoke(delegate.chat(), a);
                });
    }
}
