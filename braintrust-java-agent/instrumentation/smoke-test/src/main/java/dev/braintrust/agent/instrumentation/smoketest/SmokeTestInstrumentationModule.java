package dev.braintrust.agent.instrumentation.smoketest;

import com.google.auto.service.AutoService;
import dev.braintrust.agent.instrumentation.InstrumentationModule;
import dev.braintrust.agent.instrumentation.TypeInstrumentation;
import dev.braintrust.agent.instrumentation.TypeTransformer;
import io.opentelemetry.api.GlobalOpenTelemetry;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.matcher.ElementMatcher;

import java.util.List;

import static net.bytebuddy.matcher.ElementMatchers.named;
import static net.bytebuddy.matcher.ElementMatchers.takesArguments;

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
            var span = GlobalOpenTelemetry.get().getTracer("smoke-test").spanBuilder("smoke-test").startSpan();
            span.end();
            returnValue = true;
        }
    }
}
