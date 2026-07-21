package dev.braintrust.instrumentation.springai.v2_0_0.auto;

import static net.bytebuddy.matcher.ElementMatchers.named;
import static net.bytebuddy.matcher.ElementMatchers.takesArguments;

import com.google.auto.service.AutoService;
import dev.braintrust.instrumentation.InstrumentationModule;
import dev.braintrust.instrumentation.TypeInstrumentation;
import dev.braintrust.instrumentation.TypeTransformer;
import dev.braintrust.instrumentation.springai.v2_0_0.BraintrustSpringAI;
import io.opentelemetry.api.GlobalOpenTelemetry;
import java.util.List;
import java.util.Set;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.matcher.ElementMatcher;

@AutoService(InstrumentationModule.class)
public class SpringAIOpenAIInstrumentationModule extends InstrumentationModule {
    private static final String SPRINGAI_PACKAGE =
            "dev.braintrust.instrumentation.springai.v2_0_0.";
    private static final String OPENAI_PACKAGE = "dev.braintrust.instrumentation.openai.v2_15_0.";

    public SpringAIOpenAIInstrumentationModule() {
        super("springai_openai_2_0_0");
    }

    @Override
    public List<String> getHelperClassNames() {
        return List.of(
                SPRINGAI_PACKAGE + "BraintrustSpringAI",
                SPRINGAI_PACKAGE + "SpringAIOpenAI",
                OPENAI_PACKAGE + "BraintrustOpenAI",
                OPENAI_PACKAGE + "ContextCapturingProxy",
                OPENAI_PACKAGE + "TracingHttpClient",
                OPENAI_PACKAGE + "TracingHttpClient$1",
                OPENAI_PACKAGE + "TracingHttpClient$TeeingStreamHttpResponse",
                OPENAI_PACKAGE + "TracingHttpClient$TeeInputStream",
                OPENAI_PACKAGE + "TracingHttpClient$ExtractedRequest",
                "dev.braintrust.json.BraintrustJsonMapper",
                "dev.braintrust.instrumentation.InstrumentationSemConv");
    }

    @Override
    public Set<String> getMuzzleIgnoredClassNames() {
        return Set.of(
                // prompt fetching only applies to manual instrumentation
                "dev.braintrust.prompt.BraintrustPrompt",
                // BraintrustSpringAI dispatches by class name; the Anthropic branch never
                // executes (and so never links) when only spring-ai-openai is present.
                SPRINGAI_PACKAGE + "SpringAIAnthropic");
    }

    @Override
    public List<TypeInstrumentation> typeInstrumentations() {
        return List.of(new OpenAiChatModelBuilderInstrumentation());
    }

    /**
     * Intercepts {@code OpenAiChatModel.Builder.build()} and wraps the official openai-java clients
     * held by the returned model.
     */
    public static class OpenAiChatModelBuilderInstrumentation implements TypeInstrumentation {
        @Override
        public ElementMatcher<TypeDescription> typeMatcher() {
            return named("org.springframework.ai.openai.OpenAiChatModel$Builder");
        }

        @Override
        public void transform(TypeTransformer transformer) {
            transformer.applyAdviceToMethod(
                    named("build").and(takesArguments(0)),
                    SpringAIOpenAIInstrumentationModule.class.getName()
                            + "$OpenAiChatModelBuilderAdvice");
        }
    }

    private static class OpenAiChatModelBuilderAdvice {
        @Advice.OnMethodExit
        public static void build(@Advice.Return Object chatModel) {
            BraintrustSpringAI.wrap(GlobalOpenTelemetry.get(), chatModel);
        }
    }
}
