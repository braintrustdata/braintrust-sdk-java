package dev.braintrust.instrumentation.test;

import dev.braintrust.instrumentation.InstrumentationModule;
import dev.braintrust.instrumentation.TypeInstrumentation;
import java.util.List;

public class TestInstrumentationModule extends InstrumentationModule {

    public TestInstrumentationModule() {
        super("test");
    }

    @Override
    public List<String> getHelperClassNames() {
        return List.of("dev.braintrust.instrumentation.test.TestHelper");
    }

    @Override
    public List<TypeInstrumentation> typeInstrumentations() {
        return List.of(new TestTypeInstrumentation());
    }
}
