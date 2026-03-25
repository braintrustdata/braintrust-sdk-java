package dev.braintrust.instrumentation.genai.v1_18_0.auto;

import static net.bytebuddy.matcher.ElementMatchers.named;
import static net.bytebuddy.matcher.ElementMatchers.takesArguments;

import com.google.auto.service.AutoService;
import com.google.genai.Client;
import dev.braintrust.instrumentation.InstrumentationModule;
import dev.braintrust.instrumentation.TypeInstrumentation;
import dev.braintrust.instrumentation.TypeTransformer;
import dev.braintrust.instrumentation.genai.v1_18_0.BraintrustGenAI;
import io.opentelemetry.api.GlobalOpenTelemetry;
import java.util.List;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.implementation.bytecode.assign.Assigner;
import net.bytebuddy.matcher.ElementMatcher;

@AutoService(InstrumentationModule.class)
public class GenAIInstrumentationModule extends InstrumentationModule {
    private static final String MANUAL_PACKAGE = "dev.braintrust.instrumentation.genai.v1_18_0.";
    private static final String GENAI_PACKAGE = "com.google.genai.";

    public GenAIInstrumentationModule() {
        super("genai_1_18_0");
    }

    @Override
    public List<String> getHelperClassNames() {
        return List.of(
                MANUAL_PACKAGE + "BraintrustGenAI",
                GENAI_PACKAGE + "BraintrustInstrumentation",
                GENAI_PACKAGE + "BraintrustApiClient",
                GENAI_PACKAGE + "BraintrustApiClient$BufferedApiResponse",
                "dev.braintrust.json.BraintrustJsonMapper",
                "dev.braintrust.instrumentation.InstrumentationSemConv");
    }

    @Override
    public List<TypeInstrumentation> typeInstrumentations() {
        return List.of(new ClientBuilderInstrumentation());
    }

    public static class ClientBuilderInstrumentation implements TypeInstrumentation {
        @Override
        public ElementMatcher<TypeDescription> typeMatcher() {
            return named("com.google.genai.Client$Builder");
        }

        @Override
        public void transform(TypeTransformer transformer) {
            transformer.applyAdviceToMethod(
                    named("build").and(takesArguments(0)),
                    GenAIInstrumentationModule.class.getName() + "$ClientBuilderAdvice");
        }
    }

    private static class ClientBuilderAdvice {
        @Advice.OnMethodExit
        public static void build(
                @Advice.Return(readOnly = false, typing = Assigner.Typing.DYNAMIC)
                        Object returnedObject) {
            Client returnedClient = (Client) returnedObject;
            returnedClient = BraintrustGenAI.wrap(GlobalOpenTelemetry.get(), returnedClient);
        }
    }
}
