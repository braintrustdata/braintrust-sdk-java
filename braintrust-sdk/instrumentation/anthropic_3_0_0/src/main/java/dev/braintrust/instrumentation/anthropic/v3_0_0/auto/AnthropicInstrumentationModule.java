package dev.braintrust.instrumentation.anthropic.v3_0_0.auto;

import static net.bytebuddy.matcher.ElementMatchers.named;
import static net.bytebuddy.matcher.ElementMatchers.takesArguments;

import com.anthropic.client.AnthropicClient;
import com.anthropic.client.AnthropicClientAsync;
import com.google.auto.service.AutoService;
import dev.braintrust.instrumentation.InstrumentationModule;
import dev.braintrust.instrumentation.TypeInstrumentation;
import dev.braintrust.instrumentation.TypeTransformer;
import dev.braintrust.instrumentation.anthropic.v3_0_0.BraintrustAnthropic;
import dev.braintrust.instrumentation.muzzle.ClassLoaderMatchers;
import io.opentelemetry.api.GlobalOpenTelemetry;
import java.util.List;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.implementation.bytecode.assign.Assigner;
import net.bytebuddy.matcher.ElementMatcher;

@AutoService(InstrumentationModule.class)
public class AnthropicInstrumentationModule extends InstrumentationModule {
    private static final String MANUAL_INSTRUMENTATION_PACKAGE =
            "dev.braintrust.instrumentation.anthropic.v3_0_0.";

    public AnthropicInstrumentationModule() {
        super("anthropic_3_0_0");
    }

    /**
     * Gates this module to anthropic-java >= 3.0.0, keyed on {@code AssistantMessageParam} (new in
     * 3.0.0). The {@code anthropic_2_2_0} module's matcher excludes the same class, so exactly one
     * module applies for any given anthropic-java version.
     */
    @Override
    public ElementMatcher<ClassLoader> classLoaderMatcher() {
        return ClassLoaderMatchers.hasClassNamed(
                "com.anthropic.models.messages.AssistantMessageParam");
    }

    @Override
    public List<String> getHelperClassNames() {
        return List.of(
                MANUAL_INSTRUMENTATION_PACKAGE + "TracingHttpClient",
                MANUAL_INSTRUMENTATION_PACKAGE + "TracingHttpClient$BufferedRequest",
                MANUAL_INSTRUMENTATION_PACKAGE + "TracingHttpClient$ByteSink",
                MANUAL_INSTRUMENTATION_PACKAGE + "TracingHttpClient$BufferedBody",
                MANUAL_INSTRUMENTATION_PACKAGE + "TracingHttpClient$TeeingStreamHttpResponse",
                MANUAL_INSTRUMENTATION_PACKAGE + "TracingHttpClient$TeeInputStream",
                MANUAL_INSTRUMENTATION_PACKAGE + "TracingHttpClient$ExtractedRequest",
                MANUAL_INSTRUMENTATION_PACKAGE + "BraintrustAnthropic",
                MANUAL_INSTRUMENTATION_PACKAGE + "ContextCapturingProxy",
                MANUAL_INSTRUMENTATION_PACKAGE + "ResponseReassembler",
                MANUAL_INSTRUMENTATION_PACKAGE + "ResponseReassembler$Result",
                "dev.braintrust.json.BraintrustJsonMapper",
                "dev.braintrust.instrumentation.InstrumentationSemConv");
    }

    @Override
    public List<TypeInstrumentation> typeInstrumentations() {
        return List.of(
                new AnthropicOkHttpClientBuilderInstrumentation(),
                new AnthropicOkHttpClientAsyncBuilderInstrumentation());
    }

    public static class AnthropicOkHttpClientBuilderInstrumentation implements TypeInstrumentation {
        @Override
        public ElementMatcher<TypeDescription> typeMatcher() {
            return named("com.anthropic.client.okhttp.AnthropicOkHttpClient$Builder");
        }

        @Override
        public void transform(TypeTransformer transformer) {
            transformer.applyAdviceToMethod(
                    named("build").and(takesArguments(0)),
                    AnthropicInstrumentationModule.class.getName()
                            + "$AnthropicOkHttpClientBuilderAdvice");
        }
    }

    private static class AnthropicOkHttpClientBuilderAdvice {
        @Advice.OnMethodExit
        public static void build(
                @Advice.Return(readOnly = false, typing = Assigner.Typing.DYNAMIC)
                        Object returnedObject) {
            returnedObject =
                    BraintrustAnthropic.wrap(
                            GlobalOpenTelemetry.get(), (AnthropicClient) returnedObject);
        }
    }

    public static class AnthropicOkHttpClientAsyncBuilderInstrumentation
            implements TypeInstrumentation {
        @Override
        public ElementMatcher<TypeDescription> typeMatcher() {
            return named("com.anthropic.client.okhttp.AnthropicOkHttpClientAsync$Builder");
        }

        @Override
        public void transform(TypeTransformer transformer) {
            transformer.applyAdviceToMethod(
                    named("build").and(takesArguments(0)),
                    AnthropicInstrumentationModule.class.getName()
                            + "$AnthropicOkHttpClientAsyncBuilderAdvice");
        }
    }

    private static class AnthropicOkHttpClientAsyncBuilderAdvice {
        @Advice.OnMethodExit
        public static void build(
                @Advice.Return(readOnly = false, typing = Assigner.Typing.DYNAMIC)
                        Object returnedObject) {
            returnedObject =
                    BraintrustAnthropic.wrap(
                            GlobalOpenTelemetry.get(), (AnthropicClientAsync) returnedObject);
        }
    }
}
