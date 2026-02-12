package dev.braintrust.agent.instrumentation.openai.auto;

import com.google.auto.service.AutoService;
import com.openai.client.OpenAIClient;
import dev.braintrust.agent.instrumentation.InstrumentationModule;
import dev.braintrust.agent.instrumentation.TypeInstrumentation;
import dev.braintrust.agent.instrumentation.TypeTransformer;
import dev.braintrust.agent.instrumentation.openai.manual.BraintrustOpenAI;
import dev.braintrust.instrumentation.InstrumentationSemConv;
import io.opentelemetry.api.GlobalOpenTelemetry;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.implementation.bytecode.assign.Assigner;
import net.bytebuddy.matcher.ElementMatcher;

import java.util.List;

import static net.bytebuddy.matcher.ElementMatchers.named;
import static net.bytebuddy.matcher.ElementMatchers.takesArguments;

@AutoService(InstrumentationModule.class)
public class OpenAIInstrumentationModule extends InstrumentationModule {
    private static final String MANUAL_INSTRUMENTATION_PACKAGE = "dev.braintrust.agent.instrumentation.openai.manual.";

    public OpenAIInstrumentationModule() {
        super("openai");
    }

    @Override
    public List<String> getHelperClassNames() {
        return List.of(MANUAL_INSTRUMENTATION_PACKAGE + "TracingHttpClient",
                MANUAL_INSTRUMENTATION_PACKAGE + "TracingHttpClient$1",
                MANUAL_INSTRUMENTATION_PACKAGE + "TracingHttpClient$TeeingStreamHttpResponse",
                MANUAL_INSTRUMENTATION_PACKAGE + "TracingHttpClient$TeeInputStream",
                MANUAL_INSTRUMENTATION_PACKAGE + "BraintrustOpenAI"
        );
    }

    @Override
    public List<TypeInstrumentation> typeInstrumentations() {
        return List.of(new OpenAIOkHttpClientBuilderInstrumentation());
    }


    public static class OpenAIOkHttpClientBuilderInstrumentation implements TypeInstrumentation {
        @Override
        public ElementMatcher<TypeDescription> typeMatcher() {
            return named("com.openai.client.okhttp.OpenAIOkHttpClient$Builder");
        }

        @Override
        public void transform(TypeTransformer transformer) {
            transformer.applyAdviceToMethod(named("build").and(takesArguments(0)), OpenAIInstrumentationModule.class.getName() + "$OpenAIOkHttpClientBuilderAdvice");
        }
    }


    private static class OpenAIOkHttpClientBuilderAdvice {
        @Advice.OnMethodExit
        public static void build(
                @Advice.Return(readOnly = false, typing = Assigner.Typing.DYNAMIC) OpenAIClient returnedClient) {
            returnedClient = BraintrustOpenAI.wrapOpenAI(GlobalOpenTelemetry.get(), returnedClient);
        }
    }
}
