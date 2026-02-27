package dev.braintrust.agent.instrumentation.openai.auto;

import com.google.auto.service.AutoService;
import dev.braintrust.agent.instrumentation.InstrumentationModule;
import dev.braintrust.agent.instrumentation.TypeInstrumentation;
import dev.braintrust.agent.instrumentation.openai.manual.BraintrustOpenAI;

import java.util.List;

@AutoService(InstrumentationModule.class)
public class OpenAIInstrumentationModule extends InstrumentationModule {

    public OpenAIInstrumentationModule() {
        super("openai");
    }

    @Override
    public List<String> getHelperClassNames() {
        return List.of(BraintrustOpenAI.class.getName());
    }

    @Override
    public List<TypeInstrumentation> typeInstrumentations() {
        return List.of(new OpenAIOkHttpClientBuilderInstrumentation());
    }
}
