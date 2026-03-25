package dev.braintrust.instrumentation.openai.v2_8_0.auto;

import static net.bytebuddy.matcher.ElementMatchers.named;
import static net.bytebuddy.matcher.ElementMatchers.takesArguments;

import com.google.auto.service.AutoService;
import com.openai.client.OpenAIClient;
import dev.braintrust.instrumentation.InstrumentationModule;
import dev.braintrust.instrumentation.TypeInstrumentation;
import dev.braintrust.instrumentation.TypeTransformer;
import dev.braintrust.instrumentation.openai.v2_8_0.BraintrustOpenAI;
import io.opentelemetry.api.GlobalOpenTelemetry;
import java.util.List;
import java.util.Set;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.implementation.bytecode.assign.Assigner;
import net.bytebuddy.matcher.ElementMatcher;

@AutoService(InstrumentationModule.class)
public class OpenAIInstrumentationModule extends InstrumentationModule {
    private static final String MANUAL_INSTRUMENTATION_PACKAGE =
            "dev.braintrust.instrumentation.openai.v2_8_0.";

    public OpenAIInstrumentationModule() {
        super("openai_2_8_0");
    }

    @Override
    public List<String> getHelperClassNames() {
        return List.of(
                MANUAL_INSTRUMENTATION_PACKAGE + "TracingHttpClient",
                MANUAL_INSTRUMENTATION_PACKAGE + "TracingHttpClient$1",
                MANUAL_INSTRUMENTATION_PACKAGE + "TracingHttpClient$TeeingStreamHttpResponse",
                MANUAL_INSTRUMENTATION_PACKAGE + "TracingHttpClient$TeeInputStream",
                MANUAL_INSTRUMENTATION_PACKAGE + "BraintrustOpenAI",
                "dev.braintrust.json.BraintrustJsonMapper",
                "dev.braintrust.instrumentation.InstrumentationSemConv");
    }

    @Override
    public Set<String> getMuzzleIgnoredClassNames() {
        // prompt fetching only applies to manual instrumentation
        return Set.of("dev.braintrust.prompt.BraintrustPrompt");
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
            transformer.applyAdviceToMethod(
                    named("build").and(takesArguments(0)),
                    OpenAIInstrumentationModule.class.getName()
                            + "$OpenAIOkHttpClientBuilderAdvice");
        }
    }

    private static class OpenAIOkHttpClientBuilderAdvice {
        @Advice.OnMethodExit
        public static void build(
                @Advice.Return(readOnly = false, typing = Assigner.Typing.DYNAMIC)
                        Object returnedObject) {
            OpenAIClient returnedClient = (OpenAIClient) returnedObject;
            returnedClient = BraintrustOpenAI.wrapOpenAI(GlobalOpenTelemetry.get(), returnedClient);
        }
    }
}
