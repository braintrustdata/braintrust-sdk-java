package dev.braintrust.instrumentation.springai.v2_0_0.auto;

import static net.bytebuddy.matcher.ElementMatchers.named;
import static net.bytebuddy.matcher.ElementMatchers.not;
import static net.bytebuddy.matcher.ElementMatchers.takesArguments;

import com.google.auto.service.AutoService;
import dev.braintrust.instrumentation.InstrumentationModule;
import dev.braintrust.instrumentation.TypeInstrumentation;
import dev.braintrust.instrumentation.TypeTransformer;
import dev.braintrust.instrumentation.muzzle.ClassLoaderMatchers;
import dev.braintrust.instrumentation.springai.v2_0_0.BraintrustSpringAI;
import io.opentelemetry.api.GlobalOpenTelemetry;
import java.util.List;
import java.util.Set;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.matcher.ElementMatcher;

@AutoService(InstrumentationModule.class)
public class SpringAIAnthropicInstrumentationModule extends InstrumentationModule {
    private static final String SPRINGAI_PACKAGE =
            "dev.braintrust.instrumentation.springai.v2_0_0.";
    private static final String ANTHROPIC_PACKAGE =
            "dev.braintrust.instrumentation.anthropic.v2_2_0.";

    public SpringAIAnthropicInstrumentationModule() {
        super("springai_anthropic_2_0_0");
    }

    /**
     * Gates this module to anthropic-java < 3.0.0, since its helpers are the v2_2_0 classes.
     * Runtime muzzle would also reject them on 3.x ({@code HttpRequest.body()} changed signature);
     * this makes the boundary explicit. On 3.x, Spring AI's clients are left uninstrumented (Spring
     * AI builds {@code AnthropicClientImpl} directly, bypassing the builder anthropic_3_0_0 hooks).
     */
    @Override
    public ElementMatcher<ClassLoader> classLoaderMatcher() {
        return not(
                ClassLoaderMatchers.hasClassNamed(
                        "com.anthropic.models.messages.AssistantMessageParam"));
    }

    @Override
    public List<String> getHelperClassNames() {
        return List.of(
                SPRINGAI_PACKAGE + "BraintrustSpringAI",
                SPRINGAI_PACKAGE + "SpringAIAnthropic",
                "dev.braintrust.instrumentation.anthropic.BraintrustAnthropic",
                "dev.braintrust.instrumentation.anthropic.BraintrustAnthropic$1",
                "dev.braintrust.instrumentation.anthropic.BraintrustAnthropic$V3",
                ANTHROPIC_PACKAGE + "BraintrustAnthropic",
                ANTHROPIC_PACKAGE + "ContextCapturingProxy",
                ANTHROPIC_PACKAGE + "TracingHttpClient",
                ANTHROPIC_PACKAGE + "TracingHttpClient$BufferedRequest",
                ANTHROPIC_PACKAGE + "TracingHttpClient$ByteSink",
                ANTHROPIC_PACKAGE + "TracingHttpClient$BufferedBody",
                ANTHROPIC_PACKAGE + "TracingHttpClient$TeeingStreamHttpResponse",
                ANTHROPIC_PACKAGE + "TracingHttpClient$TeeInputStream",
                ANTHROPIC_PACKAGE + "TracingHttpClient$ExtractedRequest",
                ANTHROPIC_PACKAGE + "ResponseReassembler",
                ANTHROPIC_PACKAGE + "ResponseReassembler$Result",
                "dev.braintrust.json.BraintrustJsonMapper",
                "dev.braintrust.instrumentation.InstrumentationSemConv");
    }

    @Override
    public Set<String> getMuzzleIgnoredClassNames() {
        // BraintrustSpringAI dispatches by class name; the OpenAI branch never executes
        // (and so never links) when only spring-ai-anthropic is present.
        return Set.of(SPRINGAI_PACKAGE + "SpringAIOpenAI");
    }

    @Override
    public List<TypeInstrumentation> typeInstrumentations() {
        return List.of(new AnthropicChatModelBuilderInstrumentation());
    }

    /**
     * Intercepts {@code AnthropicChatModel.Builder.build()} and wraps the official anthropic-java
     * clients held by the returned model.
     */
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
        @Advice.OnMethodExit
        public static void build(@Advice.Return Object chatModel) {
            BraintrustSpringAI.wrap(GlobalOpenTelemetry.get(), chatModel);
        }
    }
}
