package dev.braintrust.instrumentation.anthropic.auto;

import com.anthropic.client.AnthropicClient;
import com.google.auto.service.AutoService;
import dev.braintrust.agent.instrumentation.InstrumentationModule;
import dev.braintrust.agent.instrumentation.TypeInstrumentation;
import dev.braintrust.agent.instrumentation.TypeTransformer;
import dev.braintrust.instrumentation.anthropic.manual.BraintrustAnthropic;
import io.opentelemetry.api.GlobalOpenTelemetry;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.implementation.bytecode.assign.Assigner;
import net.bytebuddy.matcher.ElementMatcher;

import java.util.List;

import static net.bytebuddy.matcher.ElementMatchers.named;
import static net.bytebuddy.matcher.ElementMatchers.takesArguments;

@AutoService(InstrumentationModule.class)
public class AnthropicInstrumentationModule extends InstrumentationModule {
    private static final String MANUAL_INSTRUMENTATION_PACKAGE = "dev.braintrust.agent.instrumentation.anthropic.manual.";

    public AnthropicInstrumentationModule() {
        super("anthropic");
    }

    @Override
    public List<String> getHelperClassNames() {
        return List.of(
                MANUAL_INSTRUMENTATION_PACKAGE + "TracingHttpClient",
                MANUAL_INSTRUMENTATION_PACKAGE + "TracingHttpClient$1",
                MANUAL_INSTRUMENTATION_PACKAGE + "TracingHttpClient$TeeingStreamHttpResponse",
                MANUAL_INSTRUMENTATION_PACKAGE + "TracingHttpClient$TeeInputStream",
                MANUAL_INSTRUMENTATION_PACKAGE + "BraintrustAnthropic"
        );
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
                    AnthropicInstrumentationModule.class.getName() + "$AnthropicOkHttpClientBuilderAdvice");
        }
    }

    private static class AnthropicOkHttpClientBuilderAdvice {
        @Advice.OnMethodExit
        public static void build(
                @Advice.Return(readOnly = false, typing = Assigner.Typing.DYNAMIC) AnthropicClient returnedClient) {
            returnedClient = BraintrustAnthropic.wrap(GlobalOpenTelemetry.get(), returnedClient);
        }
    }
}
