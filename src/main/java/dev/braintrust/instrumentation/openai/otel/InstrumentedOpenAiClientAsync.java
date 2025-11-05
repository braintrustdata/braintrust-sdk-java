/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package dev.braintrust.instrumentation.openai.otel;

import com.openai.client.OpenAIClientAsync;
import com.openai.models.chat.completions.ChatCompletion;
import com.openai.models.chat.completions.ChatCompletionCreateParams;
import com.openai.models.embeddings.CreateEmbeddingResponse;
import com.openai.models.embeddings.EmbeddingCreateParams;
import io.opentelemetry.instrumentation.api.instrumenter.Instrumenter;
import java.lang.reflect.Method;

final class InstrumentedOpenAiClientAsync
        extends DelegatingInvocationHandler<OpenAIClientAsync, InstrumentedOpenAiClientAsync> {

    private final Instrumenter<ChatCompletionCreateParams, ChatCompletion> chatInstrumenter;
    private final Instrumenter<EmbeddingCreateParams, CreateEmbeddingResponse>
            embeddingInstrumenter;
    private final boolean captureMessageContent;

    InstrumentedOpenAiClientAsync(
            OpenAIClientAsync delegate,
            Instrumenter<ChatCompletionCreateParams, ChatCompletion> chatInstrumenter,
            Instrumenter<EmbeddingCreateParams, CreateEmbeddingResponse> embeddingInstrumenter,
            boolean captureMessageContent) {
        super(delegate);
        this.chatInstrumenter = chatInstrumenter;
        this.embeddingInstrumenter = embeddingInstrumenter;
        this.captureMessageContent = captureMessageContent;
    }

    @Override
    protected Class<OpenAIClientAsync> getProxyType() {
        return OpenAIClientAsync.class;
    }

    @Override
    public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
        String methodName = method.getName();
        Class<?>[] parameterTypes = method.getParameterTypes();
        if (methodName.equals("chat") && parameterTypes.length == 0) {
            return createChatServiceAsyncProxy();
        }
        if (methodName.equals("embeddings") && parameterTypes.length == 0) {
            return new InstrumentedEmbeddingServiceAsync(
                            delegate.embeddings(), embeddingInstrumenter)
                    .createProxy();
        }
        if (methodName.equals("sync") && parameterTypes.length == 0) {
            return new InstrumentedOpenAiClient(
                            delegate.sync(),
                            chatInstrumenter,
                            embeddingInstrumenter,
                            captureMessageContent)
                    .createProxy();
        }
        return super.invoke(proxy, method, args);
    }

    private Object createChatServiceAsyncProxy() {
        return java.lang.reflect.Proxy.newProxyInstance(
                com.openai.services.async.ChatServiceAsync.class.getClassLoader(),
                new Class<?>[] {com.openai.services.async.ChatServiceAsync.class},
                (p, m, a) -> {
                    if ("completions".equals(m.getName()) && m.getParameterCount() == 0) {
                        return new InstrumentedChatCompletionServiceAsync(
                                        delegate.chat().completions(),
                                        chatInstrumenter,
                                        captureMessageContent)
                                .createProxy();
                    }
                    return m.invoke(delegate.chat(), a);
                });
    }
}
