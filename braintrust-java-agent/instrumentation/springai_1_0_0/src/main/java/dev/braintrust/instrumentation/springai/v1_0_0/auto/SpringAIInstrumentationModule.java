package dev.braintrust.instrumentation.springai.v1_0_0.auto;

import static net.bytebuddy.matcher.ElementMatchers.named;
import static net.bytebuddy.matcher.ElementMatchers.takesArguments;

import com.google.auto.service.AutoService;
import dev.braintrust.instrumentation.InstrumentationModule;
import dev.braintrust.instrumentation.TypeInstrumentation;
import dev.braintrust.instrumentation.TypeTransformer;
import dev.braintrust.instrumentation.springai.v1_0_0.BraintrustSpringAI;
import io.opentelemetry.api.GlobalOpenTelemetry;
import java.util.List;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.implementation.bytecode.assign.Assigner;
import net.bytebuddy.matcher.ElementMatcher;
import org.springframework.ai.openai.OpenAiChatModel;

@AutoService(InstrumentationModule.class)
public class SpringAIInstrumentationModule extends InstrumentationModule {
    private static final String MANUAL_INSTRUMENTATION_PACKAGE =
            "dev.braintrust.instrumentation.springai.v1_0_0.";

    public SpringAIInstrumentationModule() {
        super("springai_1_0_0");
    }

    @Override
    public List<String> getHelperClassNames() {
        return List.of(
                MANUAL_INSTRUMENTATION_PACKAGE + "BraintrustSpringAI",
                MANUAL_INSTRUMENTATION_PACKAGE + "BraintrustSpringAI$TracingInterceptor");
    }

    @Override
    public List<TypeInstrumentation> typeInstrumentations() {
        return List.of(new OpenAiChatModelBuilderInstrumentation());
    }

    /** Intercepts {@code OpenAiChatModel.Builder.build()} to inject instrumentation. */
    public static class OpenAiChatModelBuilderInstrumentation implements TypeInstrumentation {
        @Override
        public ElementMatcher<TypeDescription> typeMatcher() {
            return named("org.springframework.ai.openai.OpenAiChatModel$Builder");
        }

        @Override
        public void transform(TypeTransformer transformer) {
            transformer.applyAdviceToMethod(
                    named("build").and(takesArguments(0)),
                    SpringAIInstrumentationModule.class.getName()
                            + "$OpenAiChatModelBuilderAdvice");
        }
    }

    private static class OpenAiChatModelBuilderAdvice {
        @Advice.OnMethodExit
        public static void build(
                @Advice.Return(readOnly = false, typing = Assigner.Typing.DYNAMIC)
                        OpenAiChatModel returnedModel) {
            returnedModel =
                    (OpenAiChatModel)
                            BraintrustSpringAI.wrap(GlobalOpenTelemetry.get(), returnedModel);
        }
    }
}
