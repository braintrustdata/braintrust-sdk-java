package dev.braintrust.agent.instrumentation;

import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.matcher.ElementMatcher;

/**
 * Defines bytecode transformations for a single target type.
 *
 * <p>A {@link TypeInstrumentation} specifies which class to instrument via {@link #typeMatcher()}
 * and what advice to apply via {@link #transform(TypeTransformer)}.
 */
public interface TypeInstrumentation {

    /**
     * Returns a matcher that selects the target type to instrument.
     *
     * <p>For example, {@code named("com.openai.client.OpenAIClientImpl")} to instrument a
     * specific class.
     */
    ElementMatcher<TypeDescription> typeMatcher();

    /**
     * Registers advice on the target type's methods.
     *
     * <p>Use the provided {@link TypeTransformer} to apply advice to specific methods:
     * <pre>{@code
     * transformer.applyAdviceToMethod(
     *     named("execute").and(takesArguments(1)),
     *     MyAdvice.class.getName());
     * }</pre>
     */
    void transform(TypeTransformer transformer);
}
