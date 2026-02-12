package dev.braintrust.instrumentation.openai;

import net.bytebuddy.asm.Advice;

/**
 * Advice that doesn't reference any external classes. Used to verify that ReferenceCreator produces
 * no references for a no-op advice.
 */
public class EmptyAdvice {

    @Advice.OnMethodEnter
    public static void onEnter() {
        // no-op — no external references
    }
}
