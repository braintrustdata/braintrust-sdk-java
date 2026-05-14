package dev.braintrust.trace;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

class BraintrustShutdownHook {

    /** Shutdown ordering. Lower ordinal values run first. */
    enum ShutdownOrder {
        SPAN_PROCESSOR,
        ATTACHMENT_UPLOADER,
        TEST_HARNESS;
    }

    private record OrderedTarget(int order, Runnable target) {}

    private static final List<OrderedTarget> shutdownTargets = new CopyOnWriteArrayList<>();

    static {
        Runtime.getRuntime()
                .addShutdownHook(
                        new Thread(
                                () -> {
                                    List<OrderedTarget> targets = new ArrayList<>(shutdownTargets);
                                    targets.sort(Comparator.comparingInt(OrderedTarget::order));
                                    targets.forEach(t -> t.target().run());
                                }));
    }

    public static void addShutdownHook(Runnable target) {
        addShutdownHook(ShutdownOrder.SPAN_PROCESSOR, target);
    }

    public static void addShutdownHook(ShutdownOrder order, Runnable target) {
        addShutdownHook(order.ordinal(), target);
    }

    /**
     * Add a jvm shutdown hook.
     *
     * @param order lower numbers run first. targets with the same order number can run in any
     *     order. Span processor/exporter flush runs at level 0
     * @param target the shutdown code to run
     */
    private static void addShutdownHook(int order, Runnable target) {
        shutdownTargets.add(new OrderedTarget(order, target));
    }
}
