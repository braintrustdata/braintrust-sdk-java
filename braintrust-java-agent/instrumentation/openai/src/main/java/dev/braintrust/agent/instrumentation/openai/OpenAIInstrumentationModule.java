package dev.braintrust.agent.instrumentation.openai;

import com.google.auto.service.AutoService;
import dev.braintrust.agent.instrumentation.InstrumentationModule;
import dev.braintrust.agent.instrumentation.TypeInstrumentation;
import net.bytebuddy.matcher.ElementMatcher;

import java.util.Collections;
import java.util.List;

@AutoService(InstrumentationModule.class)
public class OpenAIInstrumentationModule extends InstrumentationModule {

    public OpenAIInstrumentationModule() {
        super("openai");
    }

    @Override
    public List<TypeInstrumentation> typeInstrumentations() {
        // TODO: add OpenAI type instrumentations
        return Collections.emptyList();
    }
}
