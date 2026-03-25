package dev.braintrust.instrumentation.anthropic.v2_2_0.auto;

import static net.bytebuddy.matcher.ElementMatchers.named;
import static net.bytebuddy.matcher.ElementMatchers.takesArguments;

import com.anthropic.client.AnthropicClient;
import com.google.auto.service.AutoService;
import dev.braintrust.instrumentation.InstrumentationModule;
import dev.braintrust.instrumentation.TypeInstrumentation;
import dev.braintrust.instrumentation.TypeTransformer;
import dev.braintrust.instrumentation.anthropic.v2_2_0.BraintrustAnthropic;
import io.opentelemetry.api.GlobalOpenTelemetry;
import java.util.List;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.implementation.bytecode.assign.Assigner;
import net.bytebuddy.matcher.ElementMatcher;

@AutoService(InstrumentationModule.class)
public class AnthropicInstrumentationModule extends InstrumentationModule {
    private static final String MANUAL_INSTRUMENTATION_PACKAGE =
            "dev.braintrust.instrumentation.anthropic.v2_2_0.";

    public AnthropicInstrumentationModule() {
        super("anthropic_2_2_0");
    }

    @Override
    public List<String> getHelperClassNames() {
        return List.of(
                MANUAL_INSTRUMENTATION_PACKAGE + "TracingHttpClient",
                MANUAL_INSTRUMENTATION_PACKAGE + "TracingHttpClient$1",
                MANUAL_INSTRUMENTATION_PACKAGE + "TracingHttpClient$TeeingStreamHttpResponse",
                MANUAL_INSTRUMENTATION_PACKAGE + "TracingHttpClient$TeeInputStream",
                MANUAL_INSTRUMENTATION_PACKAGE + "BraintrustAnthropic",
                "dev.braintrust.json.BraintrustJsonMapper",
                "dev.braintrust.instrumentation.InstrumentationSemConv");
    }

    @Override
    public List<TypeInstrumentation> typeInstrumentations() {
        return List.of(new AnthropicOkHttpClientBuilderInstrumentation());
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
            AnthropicClient returnedClient = (AnthropicClient) returnedObject;
            returnedClient = BraintrustAnthropic.wrap(GlobalOpenTelemetry.get(), returnedClient);
        }
    }
}
