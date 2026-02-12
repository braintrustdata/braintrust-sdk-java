package dev.braintrust.agent.instrumentation.test;

import dev.braintrust.agent.instrumentation.InstrumentationModule;
import dev.braintrust.agent.instrumentation.TypeInstrumentation;

import java.util.List;

public class TestInstrumentationModule extends InstrumentationModule {

    public TestInstrumentationModule() {
        super("test");
    }

    @Override
    public List<String> getHelperClassNames() {
        return List.of("dev.braintrust.agent.instrumentation.test.TestHelper");
    }

    @Override
    public List<TypeInstrumentation> typeInstrumentations() {
        return List.of(new TestTypeInstrumentation());
    }
}
