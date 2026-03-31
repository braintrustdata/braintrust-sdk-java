package dev.braintrust.bootstrap;

import java.net.URL;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/** Globally available bootstrap classpath resource class */
public class BraintrustBridge {
    public static final String INSTRUMENTATION_NAME = "braintrust-java";

    /**
     * Diagnostic utility tracking the number of times braintrust otel has been installed.
     *
     * <p>In a production app, this should be zero until global otel get() is invoked, then it
     * should remain at 1 for the rest of app's lifetime.
     */
    public static final AtomicInteger otelInstallCount = new AtomicInteger(0);

    private static final AtomicReference<BraintrustClassLoader> agentClassLoaderRef =
            new AtomicReference<>();

    public static BraintrustClassLoader getAgentClassLoader() {
        return agentClassLoaderRef.get();
    }

    public static BraintrustClassLoader createBraintrustClassLoader(
            URL agentJarURL, ClassLoader btClassLoaderParent) throws Exception {
        BraintrustClassLoader btClassLoader =
                new BraintrustClassLoader(agentJarURL, btClassLoaderParent);
        var witness = agentClassLoaderRef.compareAndExchange(null, btClassLoader);
        if (null != witness) {
            throw new IllegalStateException("agent classloader must only be set once");
        }
        return btClassLoader;
    }
}
