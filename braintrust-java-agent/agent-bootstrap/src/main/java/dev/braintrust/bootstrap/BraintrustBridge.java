package dev.braintrust.bootstrap;

import java.util.concurrent.atomic.AtomicReference;

/**
 * Globally available bootstrap classpath resource class
 */
public class BraintrustBridge {
    private static final AtomicReference<BraintrustClassLoader> agentClassLoaderRef = new AtomicReference<>();

    public static BraintrustClassLoader getAgentClassLoader() {
        return agentClassLoaderRef.get();
    }

    public static void setAgentClassloaderIfAbsent(BraintrustClassLoader classLoader) {
        var witness = agentClassLoaderRef.compareAndExchange(null, classLoader);
        if (null != witness) {
            throw new IllegalStateException("agent classloader must only be set once");
        }
    }
}
