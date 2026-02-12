package dev.braintrust.trace;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

class BraintrustShutdownHook {
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
        addShutdownHook(0, target);
    }

    public static void addShutdownHook(int order, Runnable target) {
        shutdownTargets.add(new OrderedTarget(order, target));
    }
}
