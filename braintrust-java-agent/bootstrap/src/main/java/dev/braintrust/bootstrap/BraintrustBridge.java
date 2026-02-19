package dev.braintrust.bootstrap;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Globally available bootstrap classpath resource class
 */
public class BraintrustBridge {
    /**
     * Diagnostic utility tracking the number of times braintrust otel has been installed.
     *
     * In a production app, this should be zero until global otel get() is invoked, then it should remain at 1 for the rest of app's lifetime.
     */
    public static final AtomicInteger otelInstallCount = new AtomicInteger(0);

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
