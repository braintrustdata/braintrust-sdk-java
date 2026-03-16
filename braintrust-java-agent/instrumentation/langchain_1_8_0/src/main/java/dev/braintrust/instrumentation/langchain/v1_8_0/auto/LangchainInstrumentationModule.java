package dev.braintrust.instrumentation.langchain.v1_8_0.auto;

import static net.bytebuddy.matcher.ElementMatchers.*;

import com.google.auto.service.AutoService;
import dev.braintrust.instrumentation.InstrumentationModule;
import dev.braintrust.instrumentation.TypeInstrumentation;
import dev.braintrust.instrumentation.TypeTransformer;
import dev.braintrust.instrumentation.langchain.v1_8_0.manual.BraintrustLangchain;
import dev.langchain4j.model.openai.OpenAiChatModel;
import dev.langchain4j.model.openai.OpenAiStreamingChatModel;
import dev.langchain4j.service.AiServices;
import io.opentelemetry.api.GlobalOpenTelemetry;
import java.util.List;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.implementation.bytecode.assign.Assigner;
import net.bytebuddy.matcher.ElementMatcher;

@AutoService(InstrumentationModule.class)
public class LangchainInstrumentationModule extends InstrumentationModule {
    private static final String MANUAL_PACKAGE =
            "dev.braintrust.instrumentation.langchain.v1_8_0.manual.";

    public LangchainInstrumentationModule() {
        super("langchain_1_8_0");
    }

    @Override
    public List<String> getHelperClassNames() {
        return List.of(
                MANUAL_PACKAGE + "BraintrustLangchain",
                MANUAL_PACKAGE + "BraintrustLangchain$Options",
                MANUAL_PACKAGE + "WrappedHttpClient",
                MANUAL_PACKAGE + "WrappedHttpClient$WrappedServerSentEventListener",
                MANUAL_PACKAGE + "WrappedHttpClientBuilder",
                MANUAL_PACKAGE + "TracingProxy",
                MANUAL_PACKAGE + "TracingToolExecutor",
                MANUAL_PACKAGE + "OtelContextPassingExecutor",
                // FIXME: bridge these helpers
                "dev.braintrust.instrumentation.InstrumentationSemConv",
                "dev.braintrust.json.BraintrustJsonMapper");
    }

    @Override
    public List<TypeInstrumentation> typeInstrumentations() {
        return List.of(
                new OpenAiChatModelBuilderInstrumentation(),
                new OpenAiStreamingChatModelBuilderInstrumentation(),
                new AiServicesInstrumentation());
    }

    // -------------------------------------------------------------------------
    // Intercept OpenAiChatModel.Builder.build() to wrap the HTTP client
    // -------------------------------------------------------------------------

    public static class OpenAiChatModelBuilderInstrumentation implements TypeInstrumentation {
        @Override
        public ElementMatcher<TypeDescription> typeMatcher() {
            return named("dev.langchain4j.model.openai.OpenAiChatModel$OpenAiChatModelBuilder");
        }

        @Override
        public void transform(TypeTransformer transformer) {
            transformer.applyAdviceToMethod(
                    named("build").and(takesArguments(0)),
                    LangchainInstrumentationModule.class.getName()
                            + "$OpenAiChatModelBuilderAdvice");
        }
    }

    private static class OpenAiChatModelBuilderAdvice {
        @Advice.OnMethodExit
        public static void build(
                @Advice.Return(readOnly = false, typing = Assigner.Typing.DYNAMIC)
                        OpenAiChatModel returnedModel) {
            returnedModel =
                    BraintrustLangchain.wrapChatModel(GlobalOpenTelemetry.get(), returnedModel);
        }
    }

    // -------------------------------------------------------------------------
    // Intercept OpenAiStreamingChatModel.Builder.build() to wrap the HTTP client
    // -------------------------------------------------------------------------

    public static class OpenAiStreamingChatModelBuilderInstrumentation
            implements TypeInstrumentation {
        @Override
        public ElementMatcher<TypeDescription> typeMatcher() {
            return named(
                    "dev.langchain4j.model.openai.OpenAiStreamingChatModel$OpenAiStreamingChatModelBuilder");
        }

        @Override
        public void transform(TypeTransformer transformer) {
            transformer.applyAdviceToMethod(
                    named("build").and(takesArguments(0)),
                    LangchainInstrumentationModule.class.getName()
                            + "$OpenAiStreamingChatModelBuilderAdvice");
        }
    }

    private static class OpenAiStreamingChatModelBuilderAdvice {
        @Advice.OnMethodExit
        public static void build(
                @Advice.Return(readOnly = false, typing = Assigner.Typing.DYNAMIC)
                        OpenAiStreamingChatModel returnedModel) {
            returnedModel =
                    BraintrustLangchain.wrapStreamingChatModel(
                            GlobalOpenTelemetry.get(), returnedModel);
        }
    }

    // -------------------------------------------------------------------------
    // Intercept AiServices.build() to wrap with TracingProxy + TracingToolExecutor
    // -------------------------------------------------------------------------

    public static class AiServicesInstrumentation implements TypeInstrumentation {
        @Override
        public ElementMatcher<TypeDescription> typeMatcher() {
            return hasSuperType(named("dev.langchain4j.service.AiServices"))
                    .and(
                            declaresMethod(
                                    named("build").and(takesArguments(0)).and(not(isAbstract()))));
        }

        @Override
        public void transform(TypeTransformer transformer) {
            transformer.applyAdviceToMethod(
                    named("build").and(takesArguments(0)),
                    LangchainInstrumentationModule.class.getName() + "$AiServicesAdvice");
        }
    }

    private static class AiServicesAdvice {
        @Advice.OnMethodExit
        public static void build(
                @Advice.This AiServices<?> aiServices,
                @Advice.Return(readOnly = false, typing = Assigner.Typing.DYNAMIC)
                        Object returnedService) {
            returnedService =
                    BraintrustLangchain.wrapAiService(
                            GlobalOpenTelemetry.get(), aiServices, returnedService);
        }
    }
}
