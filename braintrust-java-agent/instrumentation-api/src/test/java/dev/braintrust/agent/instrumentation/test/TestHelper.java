package dev.braintrust.agent.instrumentation.test;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * A helper class that lives in the agent classloader and gets injected into the app classloader.
 * Advice code references this class — without helper injection, the app classloader wouldn't be
 * able to see it.
 */
public class TestHelper {

    public static final AtomicInteger CALL_COUNT = new AtomicInteger(0);
    public static final AtomicReference<String> LAST_ARG = new AtomicReference<>();

    public static void onGreet(String name) {
        CALL_COUNT.incrementAndGet();
        LAST_ARG.set(name);
    }
}
