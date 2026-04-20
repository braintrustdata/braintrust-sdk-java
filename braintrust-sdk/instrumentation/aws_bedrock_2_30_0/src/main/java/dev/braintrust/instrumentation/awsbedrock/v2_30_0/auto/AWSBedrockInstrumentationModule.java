package dev.braintrust.instrumentation.awsbedrock.v2_30_0.auto;

import static net.bytebuddy.matcher.ElementMatchers.named;
import static net.bytebuddy.matcher.ElementMatchers.takesArguments;

import com.google.auto.service.AutoService;
import dev.braintrust.instrumentation.InstrumentationModule;
import dev.braintrust.instrumentation.TypeInstrumentation;
import dev.braintrust.instrumentation.TypeTransformer;
import dev.braintrust.instrumentation.awsbedrock.v2_30_0.BraintrustAWSBedrock;
import io.opentelemetry.api.GlobalOpenTelemetry;
import java.util.List;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.matcher.ElementMatcher;
import software.amazon.awssdk.services.bedrockruntime.BedrockRuntimeAsyncClientBuilder;
import software.amazon.awssdk.services.bedrockruntime.BedrockRuntimeClientBuilder;

/**
 * Auto-instruments the AWS Bedrock Runtime sync and async client builders by hooking into {@code
 * SdkDefaultClientBuilder.build()} — the single {@code final} method in the AWS SDK builder
 * hierarchy that both {@link BedrockRuntimeClientBuilder} and {@link
 * BedrockRuntimeAsyncClientBuilder} ultimately call. The advice checks the runtime type of {@code
 * this} to limit instrumentation to Bedrock builders only.
 */
@AutoService(InstrumentationModule.class)
public class AWSBedrockInstrumentationModule extends InstrumentationModule {
    private static final String MANUAL_INSTRUMENTATION_PACKAGE =
            "dev.braintrust.instrumentation.awsbedrock.v2_30_0.";

    public AWSBedrockInstrumentationModule() {
        super("aws_bedrock_2_30_0");
    }

    @Override
    public List<String> getHelperClassNames() {
        return List.of(
                MANUAL_INSTRUMENTATION_PACKAGE + "BraintrustAWSBedrock",
                MANUAL_INSTRUMENTATION_PACKAGE + "BraintrustBedrockInterceptor",
                MANUAL_INSTRUMENTATION_PACKAGE + "BraintrustBedrockInterceptor$TeeingSubscriber",
                "dev.braintrust.json.BraintrustJsonMapper",
                "dev.braintrust.instrumentation.InstrumentationSemConv");
    }

    @Override
    public List<TypeInstrumentation> typeInstrumentations() {
        return List.of(new SdkDefaultClientBuilderInstrumentation());
    }

    /**
     * Targets {@code SdkDefaultClientBuilder} — the abstract base that defines the {@code final
     * build()} method inherited by all AWS SDK client builders, including both Bedrock variants.
     */
    public static class SdkDefaultClientBuilderInstrumentation implements TypeInstrumentation {
        @Override
        public ElementMatcher<TypeDescription> typeMatcher() {
            return named("software.amazon.awssdk.core.client.builder.SdkDefaultClientBuilder");
        }

        @Override
        public void transform(TypeTransformer transformer) {
            transformer.applyAdviceToMethod(
                    named("build").and(takesArguments(0)),
                    AWSBedrockInstrumentationModule.class.getName() + "$BedrockBuilderAdvice");
        }
    }

    /**
     * Fires on entry to {@code build()} for any AWS SDK client builder. Uses {@code instanceof}
     * checks to limit actual work to Bedrock builders, then calls the idempotent {@code wrap()}
     * method to register the Braintrust {@code ExecutionInterceptor} before the client is built.
     */
    public static class BedrockBuilderAdvice {
        @Advice.OnMethodEnter
        public static void build(@Advice.This Object builder) {
            if (builder instanceof BedrockRuntimeClientBuilder bedrockBuilder) {
                BraintrustAWSBedrock.wrap(GlobalOpenTelemetry.get(), bedrockBuilder);
            } else if (builder instanceof BedrockRuntimeAsyncClientBuilder bedrockBuilder) {
                BraintrustAWSBedrock.wrap(GlobalOpenTelemetry.get(), bedrockBuilder);
            }
        }
    }
}
