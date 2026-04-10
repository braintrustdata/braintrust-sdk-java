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
import net.bytebuddy.matcher.ElementMatcher;

@AutoService(InstrumentationModule.class)
public class SpringAIAnthropicInstrumentationModule extends InstrumentationModule {
    private static final String PACKAGE = "dev.braintrust.instrumentation.springai.v1_0_0.";

    public SpringAIAnthropicInstrumentationModule() {
        super("springai_anthropic_1_0_0");
    }

    @Override
    public List<String> getHelperClassNames() {
        return List.of(
                PACKAGE + "BraintrustSpringAI",
                PACKAGE + "BraintrustSpringAI$BraintrustRestInterceptor",
                PACKAGE + "BraintrustSpringAI$BufferedClientHttpResponse",
                PACKAGE + "BraintrustSpringAI$BraintrustWebClientFilter",
                PACKAGE + "BraintrustSpringAI$BodyCapturingRequest",
                PACKAGE + "BraintrustSpringAI$BodyCapturingOutputMessage",
                PACKAGE + "BraintrustSpringAI$StreamContext",
                "dev.braintrust.json.BraintrustJsonMapper",
                "dev.braintrust.instrumentation.InstrumentationSemConv");
    }

    @Override
    public List<TypeInstrumentation> typeInstrumentations() {
        return List.of(new AnthropicChatModelBuilderInstrumentation());
    }

    public static class AnthropicChatModelBuilderInstrumentation implements TypeInstrumentation {
        @Override
        public ElementMatcher<TypeDescription> typeMatcher() {
            return named("org.springframework.ai.anthropic.AnthropicChatModel$Builder");
        }

        @Override
        public void transform(TypeTransformer transformer) {
            transformer.applyAdviceToMethod(
                    named("build").and(takesArguments(0)),
                    SpringAIAnthropicInstrumentationModule.class.getName()
                            + "$AnthropicChatModelBuilderAdvice");
        }
    }

    private static class AnthropicChatModelBuilderAdvice {
        @Advice.OnMethodEnter
        public static void build(@Advice.This Object builder) {
            BraintrustSpringAI.wrap(GlobalOpenTelemetry.get(), builder);
        }
    }
}
