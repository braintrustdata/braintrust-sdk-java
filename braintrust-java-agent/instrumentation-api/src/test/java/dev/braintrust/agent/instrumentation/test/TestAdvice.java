package dev.braintrust.agent.instrumentation.test;

import net.bytebuddy.asm.Advice;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * ByteBuddy advice that tracks how many times the advised method was called.
 */
public class TestAdvice {

    public static final AtomicInteger ENTER_COUNT = new AtomicInteger(0);

    @Advice.OnMethodEnter
    public static void onEnter() {
        ENTER_COUNT.incrementAndGet();
    }
}
