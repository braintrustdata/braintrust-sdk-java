package dev.braintrust.agent.instrumentation.openai.auto;

import com.openai.client.okhttp.OpenAIOkHttpClient;
import dev.braintrust.agent.instrumentation.TypeInstrumentation;
import dev.braintrust.agent.instrumentation.TypeTransformer;
import dev.braintrust.agent.instrumentation.openai.manual.BraintrustOpenAI;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.matcher.ElementMatcher;

import static net.bytebuddy.matcher.ElementMatchers.named;

/**
 * Instruments {@code com.openai.client.okhttp.OpenAIOkHttpClient.Builder} to intercept
 * the {@code build()} method.
 */
public class OpenAIOkHttpClientBuilderInstrumentation implements TypeInstrumentation {

    @Override
    public ElementMatcher<TypeDescription> typeMatcher() {
        return named("com.openai.client.okhttp.OpenAIOkHttpClient$Builder");
    }

    @Override
    public void transform(TypeTransformer transformer) {
        transformer.applyAdviceToMethod(
                named("build"),
                OpenAIOkHttpClientBuilderAdvice.class.getName());
    }

    public static class OpenAIOkHttpClientBuilderAdvice {
        @Advice.OnMethodEnter
        public static void onEnter(@Advice.This Object thisObject) {
            OpenAIOkHttpClient.Builder thisBuilder = (OpenAIOkHttpClient.Builder) thisObject;
            thisBuilder.hashCode();
            BraintrustOpenAI.autoInstrumentationApplied.set(true);
        }
    }
}
