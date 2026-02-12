package dev.braintrust.agent.instrumentation;

import net.bytebuddy.description.method.MethodDescription;
import net.bytebuddy.matcher.ElementMatcher;

/**
 * API for registering bytecode advice on methods of an instrumented type.
 *
 * <p>Passed to {@link TypeInstrumentation#transform(TypeTransformer)} to let each instrumentation
 * declare which methods to advise and with which advice class.
 */
public interface TypeTransformer {

    /**
     * Applies a ByteBuddy advice class to methods matching the given matcher.
     *
     * <p>The advice class should be a class with static methods annotated with
     * {@link net.bytebuddy.asm.Advice.OnMethodEnter} and/or
     * {@link net.bytebuddy.asm.Advice.OnMethodExit}.
     *
     * @param methodMatcher selects which methods to advise
     * @param adviceClassName the fully-qualified name of the advice class
     */
    void applyAdviceToMethod(
            ElementMatcher<? super MethodDescription> methodMatcher, String adviceClassName);
}
