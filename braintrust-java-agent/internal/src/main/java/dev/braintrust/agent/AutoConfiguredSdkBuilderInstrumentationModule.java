package dev.braintrust.agent;

import static net.bytebuddy.matcher.ElementMatchers.named;
import static net.bytebuddy.matcher.ElementMatchers.takesArguments;

import com.google.auto.service.AutoService;
import dev.braintrust.bootstrap.BraintrustBridge;
import dev.braintrust.instrumentation.InstrumentationModule;
import dev.braintrust.instrumentation.TypeInstrumentation;
import dev.braintrust.instrumentation.TypeTransformer;
import java.util.List;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.matcher.ElementMatcher;

/**
 * Intercepts {@code AutoConfiguredOpenTelemetrySdkBuilder.build()} to inject the Braintrust {@code
 * AutoConfigurationCustomizerProvider} into every autoconfigure SDK build — including the one
 * performed by the OTel Java agent.
 *
 * <p>This means Braintrust does not need to register an SPI file visible to the OTel extension
 * classloader; instead it hooks directly into the builder before {@code build()} executes.
 */
@AutoService(InstrumentationModule.class)
public class AutoConfiguredSdkBuilderInstrumentationModule extends InstrumentationModule {

    public AutoConfiguredSdkBuilderInstrumentationModule() {
        super("autoconfigure-sdk-builder");
    }

    @Override
    public List<TypeInstrumentation> typeInstrumentations() {
        return List.of(new SdkBuilderTypeInstrumentation());
    }

    static class SdkBuilderTypeInstrumentation implements TypeInstrumentation {
        @Override
        public ElementMatcher<TypeDescription> typeMatcher() {
            return named(
                    "io.opentelemetry.sdk.autoconfigure.AutoConfiguredOpenTelemetrySdkBuilder");
        }

        @Override
        public void transform(TypeTransformer transformer) {
            transformer.applyAdviceToMethod(
                    named("build").and(takesArguments(0)),
                    AutoConfiguredSdkBuilderInstrumentationModule.class.getName() + "$BuildAdvice");
        }
    }

    @SuppressWarnings("unused")
    public static class BuildAdvice {
        @Advice.OnMethodEnter
        public static void onEnter(@Advice.This Object builder) {
            if (true) return; // TODO FIXME
            ClassLoader btCl = BraintrustBridge.getAgentClassLoader();
            if (btCl == null) {
                // shouldn't happen, but just in case.
                System.err.println(
                        "[braintrust] WARNING: sdk builder instrumentation applied, but braintrust"
                                + " bootstrap classpath is not present. Skipping instrumentation.");
                return;
            }

            try {
                Class<?> agentClass = btCl.loadClass("dev.braintrust.agent.BraintrustAgent");
                Object agentInstance = agentClass.getDeclaredConstructor().newInstance();
                agentClass
                        .getMethod(
                                "customize",
                                Class.forName(
                                        "io.opentelemetry.sdk.autoconfigure.spi"
                                                + ".AutoConfigurationCustomizer",
                                        false,
                                        builder.getClass().getClassLoader()))
                        .invoke(agentInstance, builder);
            } catch (Exception e) {
                System.err.println(
                        "[braintrust] WARNING: failed to inject BraintrustAgent into"
                                + " AutoConfiguredOpenTelemetrySdkBuilder.build(): "
                                + e);
            }
        }
    }
}
