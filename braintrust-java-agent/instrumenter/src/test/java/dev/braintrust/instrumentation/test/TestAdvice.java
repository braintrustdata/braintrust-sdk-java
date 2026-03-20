package dev.braintrust.instrumentation.test;

import net.bytebuddy.asm.Advice;

/**
 * ByteBuddy advice that delegates to {@link TestHelper} — exercising both the advice pipeline and
 * the helper injection path.
 */
public class TestAdvice {

    @Advice.OnMethodEnter
    public static void onEnter(@Advice.Argument(0) String name) {
        TestHelper.onGreet(name);
    }
}
