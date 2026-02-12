package dev.braintrust.agent;

import static net.bytebuddy.matcher.ElementMatchers.isStatic;
import static net.bytebuddy.matcher.ElementMatchers.named;
import static net.bytebuddy.matcher.ElementMatchers.takesArguments;

import com.google.auto.service.AutoService;
import dev.braintrust.bootstrap.BraintrustBridge;
import dev.braintrust.instrumentation.InstrumentationModule;
import dev.braintrust.instrumentation.TypeInstrumentation;
import dev.braintrust.instrumentation.TypeTransformer;
import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.OpenTelemetry;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.reflect.Method;
import java.util.List;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.matcher.ElementMatcher;

/**
 * Replaces {@code GlobalOpenTelemetry.maybeAutoConfigureAndSetGlobal()} so that the Braintrust
 * agent controls OTel SDK initialization lazily — triggered on the first call to {@code
 * GlobalOpenTelemetry.get()}.
 */
@AutoService(InstrumentationModule.class)
public class GlobalOpenTelemetryInstrumentationModule extends InstrumentationModule {

    public GlobalOpenTelemetryInstrumentationModule() {
        super("global-opentelemetry");
    }

    @Override
    public List<TypeInstrumentation> typeInstrumentations() {
        return List.of(new GlobalOpenTelemetryTypeInstrumentation());
    }

    static class GlobalOpenTelemetryTypeInstrumentation implements TypeInstrumentation {
        @Override
        public ElementMatcher<TypeDescription> typeMatcher() {
            return named("io.opentelemetry.api.GlobalOpenTelemetry");
        }

        @Override
        public void transform(TypeTransformer transformer) {
            transformer.applyAdviceToMethod(
                    named("maybeAutoConfigureAndSetGlobal").and(isStatic()).and(takesArguments(0)),
                    GlobalOpenTelemetryInstrumentationModule.class.getName()
                            + "$MaybeAutoConfigureAdvice");
        }
    }

    @SuppressWarnings("unused")
    static class MaybeAutoConfigureAdvice {
        @Advice.OnMethodEnter(skipOn = Advice.OnNonDefaultValue.class)
        static boolean onEnter() {
            // Returning true (non-default for boolean) skips the original method body.
            return true;
        }

        @Advice.OnMethodExit
        static void onExit(@Advice.Return(readOnly = false) OpenTelemetry result) {
            // almost identical to the original method, but load autoconfigure out of the braintrust
            // classloader
            ClassLoader braintrustClassLoader = BraintrustBridge.getAgentClassLoader();
            Class<?> openTelemetrySdkAutoConfiguration;
            try {
                openTelemetrySdkAutoConfiguration =
                        Class.forName(
                                "io.opentelemetry.sdk.autoconfigure.AutoConfiguredOpenTelemetrySdk",
                                true,
                                braintrustClassLoader);
            } catch (ClassNotFoundException e) {
                result = null;
                return;
            }

            try {
                Method initialize = openTelemetrySdkAutoConfiguration.getMethod("initialize");
                Object autoConfiguredSdk = initialize.invoke(null);
                Method getOpenTelemetrySdk =
                        openTelemetrySdkAutoConfiguration.getMethod("getOpenTelemetrySdk");
                OpenTelemetry raw = (OpenTelemetry) getOpenTelemetrySdk.invoke(autoConfiguredSdk);
                result =
                        (OpenTelemetry)
                                MethodHandles.lookup()
                                        .findStatic(
                                                GlobalOpenTelemetry.class,
                                                "obfuscatedOpenTelemetry",
                                                MethodType.methodType(
                                                        OpenTelemetry.class, OpenTelemetry.class))
                                        .invoke(raw);
                return;
            } catch (Throwable t) {
                System.err.println(
                        "[braintrust-java-agent] Warning: failed to initialize global OpenTelemetry"
                                + ": "
                                + t);
            }
            result = null;
            return;
        }
    }
}
