package dev.braintrust.sdkspecimpl;

import java.io.File;
import java.net.URL;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Wraps a {@link SpecClient} that declares {@link SpecClient#isolation()}: identity and {@link
 * #supports} come from the declaring client, while {@link #executeSpec} is forwarded to the
 * implementation class loaded inside a {@link ChildFirstClassLoader} built from the declared
 * classpath system property.
 */
final class IsolatedClientDelegate implements SpecClient {

    /** Child loaders are cached per classpath property so all clients on it share classes. */
    private static final Map<String, ClassLoader> LOADERS = new ConcurrentHashMap<>();

    private final SpecClient declaration;
    private final SpecClient.Isolation isolation;
    private volatile SpecClient delegate;

    static SpecClient resolve(SpecClient client) {
        return client.isolation()
                .<SpecClient>map(iso -> new IsolatedClientDelegate(client, iso))
                .orElse(client);
    }

    private IsolatedClientDelegate(SpecClient declaration, SpecClient.Isolation isolation) {
        this.declaration = declaration;
        this.isolation = isolation;
    }

    @Override
    public String id() {
        return declaration.id();
    }

    @Override
    public String provider() {
        return declaration.provider();
    }

    @Override
    public boolean supports(LlmSpanSpec spec) {
        return declaration.supports(spec);
    }

    @Override
    public Optional<Isolation> isolation() {
        return Optional.of(isolation);
    }

    @Override
    public void executeSpec(LlmSpanSpec spec, SpecClientContext ctx) throws Exception {
        SpecClient impl = delegate();
        // Some libraries (Jackson, Reactor, Spring) consult the thread context classloader for
        // resource and ServiceLoader lookups; point it at the child loader for the duration.
        Thread thread = Thread.currentThread();
        ClassLoader previous = thread.getContextClassLoader();
        thread.setContextClassLoader(impl.getClass().getClassLoader());
        try {
            impl.executeSpec(spec, ctx);
        } finally {
            thread.setContextClassLoader(previous);
        }
    }

    private SpecClient delegate() throws Exception {
        SpecClient result = delegate;
        if (result == null) {
            synchronized (this) {
                result = delegate;
                if (result == null) {
                    ClassLoader loader =
                            LOADERS.computeIfAbsent(
                                    isolation.classpathSystemProperty(),
                                    IsolatedClientDelegate::buildLoader);
                    result =
                            (SpecClient)
                                    loader.loadClass(isolation.implClassName())
                                            .getDeclaredConstructor()
                                            .newInstance();
                    delegate = result;
                }
            }
        }
        return result;
    }

    private static ClassLoader buildLoader(String classpathSystemProperty) {
        String classpath = System.getProperty(classpathSystemProperty);
        if (classpath == null || classpath.isBlank()) {
            throw new IllegalStateException(
                    "System property '"
                            + classpathSystemProperty
                            + "' is not set — expected btx/build.gradle to pass the isolated"
                            + " client classpath to the test JVM");
        }
        URL[] urls =
                Arrays.stream(classpath.split(File.pathSeparator))
                        .filter(entry -> !entry.isBlank())
                        .map(
                                entry -> {
                                    try {
                                        return Paths.get(entry).toUri().toURL();
                                    } catch (Exception e) {
                                        throw new IllegalStateException(
                                                "Bad classpath entry: " + entry, e);
                                    }
                                })
                        .toArray(URL[]::new);
        return new ChildFirstClassLoader(urls, IsolatedClientDelegate.class.getClassLoader());
    }
}
