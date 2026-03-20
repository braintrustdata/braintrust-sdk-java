package dev.braintrust.instrumentation.test;

import static net.bytebuddy.matcher.ElementMatchers.named;

import dev.braintrust.instrumentation.TypeInstrumentation;
import dev.braintrust.instrumentation.TypeTransformer;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.matcher.ElementMatcher;

public class TestTypeInstrumentation implements TypeInstrumentation {

    @Override
    public ElementMatcher<TypeDescription> typeMatcher() {
        return named("dev.braintrust.instrumentation.test.TestTarget");
    }

    @Override
    public void transform(TypeTransformer transformer) {
        transformer.applyAdviceToMethod(named("greet"), TestAdvice.class.getName());
    }
}
