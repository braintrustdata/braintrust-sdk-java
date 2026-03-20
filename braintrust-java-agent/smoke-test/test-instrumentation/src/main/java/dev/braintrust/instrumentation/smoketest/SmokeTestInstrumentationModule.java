package dev.braintrust.instrumentation.smoketest;

import static net.bytebuddy.matcher.ElementMatchers.named;
import static net.bytebuddy.matcher.ElementMatchers.takesArguments;

import com.google.auto.service.AutoService;
import dev.braintrust.instrumentation.InstrumentationModule;
import dev.braintrust.instrumentation.TypeInstrumentation;
import dev.braintrust.instrumentation.TypeTransformer;
import io.opentelemetry.api.GlobalOpenTelemetry;
import java.util.List;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.matcher.ElementMatcher;

@AutoService(InstrumentationModule.class)
public class SmokeTestInstrumentationModule extends InstrumentationModule {

    public SmokeTestInstrumentationModule() {
        super("smoke-test");
    }

    @Override
    public List<TypeInstrumentation> typeInstrumentations() {
        return List.of(new InstrumentationReflectionInstrumentation());
    }

    public static class InstrumentationReflectionInstrumentation implements TypeInstrumentation {
        @Override
        public ElementMatcher<TypeDescription> typeMatcher() {
            return named("dev.braintrust.InstrumentationReflection");
        }

        @Override
        public void transform(TypeTransformer transformer) {
            transformer.applyAdviceToMethod(
                    named("isInstrumented").and(takesArguments(0)),
                    SmokeTestInstrumentationModule.class.getName() + "$IsInstrumentedAdvice");
        }
    }

    public static class IsInstrumentedAdvice {
        @Advice.OnMethodExit
        public static void onExit(@Advice.Return(readOnly = false) boolean returnValue) {
            // otel api should work
            var span =
                    GlobalOpenTelemetry.get()
                            .getTracer("smoke-test")
                            .spanBuilder("smoke-test")
                            .startSpan();
            span.end();
            returnValue = true;
        }
    }
}
