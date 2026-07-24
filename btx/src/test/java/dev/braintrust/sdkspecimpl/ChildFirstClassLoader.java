package dev.braintrust.sdkspecimpl;

import java.io.IOException;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.Enumeration;

/**
 * A child-first (parent-last) {@link URLClassLoader} used to run {@link SpecClient}s whose target
 * libraries conflict with the main test classpath (e.g. Spring AI 1.x vs 2.x, which share Maven
 * coordinates and package names).
 *
 * <p>Lookup order: already-loaded → force-delegated prefixes (parent) → own URLs → parent fallback.
 * The force-delegated prefixes are the types that cross the loader boundary — JDK, OTel API (so
 * spans land in the shared SDK), and slf4j (unified logging). Everything else (the target library,
 * its SDK transitives, the braintrust instrumentation modules, Jackson) is deliberately
 * child-loaded so it links against the child classpath's versions.
 */
final class ChildFirstClassLoader extends URLClassLoader {

    private static final String[] PARENT_FIRST_PREFIXES = {
        "java.", "javax.", "jdk.", "sun.", "com.sun.", "io.opentelemetry.", "org.slf4j.",
    };

    ChildFirstClassLoader(URL[] urls, ClassLoader parent) {
        super(urls, parent);
    }

    @Override
    protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
        synchronized (getClassLoadingLock(name)) {
            Class<?> loaded = findLoadedClass(name);
            if (loaded == null) {
                loaded = parentFirst(name) ? super.loadClass(name, false) : childFirst(name);
            }
            if (resolve) {
                resolveClass(loaded);
            }
            return loaded;
        }
    }

    private Class<?> childFirst(String name) throws ClassNotFoundException {
        try {
            return findClass(name);
        } catch (ClassNotFoundException e) {
            // Not on the child classpath — fall back to the parent. This is how shared
            // boundary types (SpecClient, LlmSpanSpec, TestHarness, ...) resolve.
            return super.loadClass(name, false);
        }
    }

    private static boolean parentFirst(String name) {
        for (String prefix : PARENT_FIRST_PREFIXES) {
            if (name.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }

    @Override
    public URL getResource(String name) {
        URL own = findResource(name);
        return own != null ? own : super.getResource(name);
    }

    @Override
    public Enumeration<URL> getResources(String name) throws IOException {
        // ServiceLoader isolation: when the child classpath declares providers for a service,
        // expose ONLY those. Combining child and parent provider files splits the brain — a
        // parent-loaded provider class does not implement the child-loaded service interface
        // (e.g. reactor-netty's ChannelContextAccessor vs the child's micrometer
        // ContextAccessor), and ServiceLoader fails with "not a subtype".
        if (name.startsWith("META-INF/services/")) {
            Enumeration<URL> own = findResources(name);
            if (own.hasMoreElements()) {
                return own;
            }
        }
        return super.getResources(name);
    }
}
