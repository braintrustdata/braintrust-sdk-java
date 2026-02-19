package dev.braintrust.bootstrap;

import java.util.concurrent.atomic.AtomicReference;

public class BraintrustBootstrap {
    private static final AtomicReference<ClassLoader> agentClassLoaderRef = new AtomicReference<>();

    public static ClassLoader getAgentClassLoader() {
        throw new RuntimeException("TODO");
    }

    public static ClassLoader setAgentClassLoader(ClassLoader classLoader) {
        throw new RuntimeException("TODO");
    }
}
