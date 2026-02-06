package dev.braintrust.trace;

public class UnitTestShutdownHook {
    public static void addShutdownHook(Runnable target) {
        addShutdownHook(0, target);
    }

    public static void addShutdownHook(int order, Runnable target) {
        BraintrustShutdownHook.addShutdownHook(order, target);
    }
}
