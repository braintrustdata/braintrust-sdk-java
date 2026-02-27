package dev.braintrust.agent.instrumentation.openai.manual;

import java.util.concurrent.atomic.AtomicBoolean;

/** Braintrust OpenAI client manual instrumentation. */
public class BraintrustOpenAI {
    public static final AtomicBoolean autoInstrumentationApplied = new AtomicBoolean(false);
}
