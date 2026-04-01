package dev.braintrust.system;

import static org.junit.jupiter.api.Assertions.*;

import dev.braintrust.bootstrap.BraintrustBridge;
import dev.braintrust.bootstrap.BraintrustClassLoader;
import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.Tracer;
import java.io.File;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.jar.JarFile;
import org.junit.jupiter.api.Test;

class AgentBootstrapTest {

    @Test
    void successfulPremain() throws Exception {
        var systemClassloader = ClassLoader.getSystemClassLoader();
        assertTrue(AgentBootstrap.installed, "premain should install the agent");
        assertEquals(systemClassloader, AgentBootstrap.class.getClassLoader());
        assertNotNull(
                BraintrustBridge.getAgentClassLoader(), "premain should set up agent classloader");
        assertNotEquals(systemClassloader, BraintrustBridge.getAgentClassLoader());
        assertNull(
                BraintrustBridge.class.getClassLoader(), "bt bootstrap must run on tb classloader");
        assertNull(
                BraintrustClassLoader.class.getClassLoader(),
                "bt classloader must be loaded on bootstrap");
        var byteBuddyClazz =
                BraintrustBridge.getAgentClassLoader().loadClass("net.bytebuddy.ByteBuddy");
        assertEquals(BraintrustBridge.getAgentClassLoader(), byteBuddyClazz.getClassLoader());
    }

    @Test
    void correctClasspath() throws Exception {
        final List<String> ALLOWED_BOOTSTRAP_CLASSPATH_PREFIXES =
                List.of("dev.braintrust.bootstrap.", "io.opentelemetry.");
        final List<String> ALLOWED_SYSTEM_CLASSPATH_CLASSES =
                List.of(
                        // only these specific classes are allowed to appear on the system
                        // classpath. everything else must be on the bootstrap or braintrust
                        // classpaths
                        "dev.braintrust.system.AgentBootstrap");

        ClassLoader bootstrapClassLoader = null;
        var systemClassloader = ClassLoader.getSystemClassLoader();
        var braintrustClassloader = BraintrustBridge.getAgentClassLoader();

        final Set<String> systemClasses = new HashSet<>();
        final Set<String> bootstrapClasses = new HashSet<>();
        final Set<String> btLoaderClasses = new HashSet<>();
        // Get the agent JAR from the system-CL-loaded BraintrustAgent's code source.
        // (The bootstrap-loaded copy doesn't have a code source, so we use the system CL copy.)
        var agentJarUrl = AgentBootstrap.class.getProtectionDomain().getCodeSource().getLocation();
        try (var agentJar = new JarFile(new File(agentJarUrl.toURI()))) {
            agentJar.stream()
                    .forEach(
                            entry -> {
                                var resourceName = entry.getName();
                                if (entry.isDirectory()) {
                                    return;
                                }
                                if (resourceName.endsWith(".class")) {
                                    var className = toClassName(resourceName);
                                    try {
                                        var clazz = systemClassloader.loadClass(className);
                                        if (ALLOWED_SYSTEM_CLASSPATH_CLASSES.contains(className)) {
                                            // ---- SYSTEM CLASSES ----
                                            assertEquals(
                                                    systemClassloader,
                                                    clazz.getClassLoader(),
                                                    "unexpected classloader for class %s"
                                                            .formatted(clazz));
                                            assertFalse(
                                                    bootstrapClasses.contains(className),
                                                    "duplicate class: %s".formatted(className));
                                            systemClasses.add(className);
                                            assertFalse(
                                                    btLoaderClasses.contains(className),
                                                    "duplicate class: %s".formatted(className));
                                        } else {
                                            // ---- BOOTSTRAP CLASSES ----
                                            assertEquals(
                                                    bootstrapClassLoader,
                                                    clazz.getClassLoader(),
                                                    "unexpected classloader for class %s"
                                                            .formatted(clazz));
                                            assertTrue(
                                                    startsWithAny(
                                                            className,
                                                            ALLOWED_BOOTSTRAP_CLASSPATH_PREFIXES),
                                                    "unexpected class on bootstrap classpath: %s"
                                                            .formatted(resourceName));
                                            bootstrapClasses.add(className);
                                            assertFalse(
                                                    systemClasses.contains(className),
                                                    "duplicate class: %s".formatted(className));
                                            assertFalse(
                                                    btLoaderClasses.contains(className),
                                                    "duplicate class: %s".formatted(className));
                                        }
                                    } catch (ClassNotFoundException | NoClassDefFoundError e) {
                                        fail(e);
                                    }
                                } else if (resourceName.endsWith(".classdata")) {
                                    // ---- BRAINTRUST CLASSES ----
                                    var className = toClassName(resourceName);
                                    try {
                                        var clazz = braintrustClassloader.loadClass(className);
                                        var actualClassLoader = clazz.getClassLoader();
                                        assertTrue(
                                                actualClassLoader == braintrustClassloader
                                                        || actualClassLoader
                                                                == bootstrapClassLoader,
                                                "unexpected classloader for class %s: expected BraintrustClassLoader or bootstrap, got %s"
                                                        .formatted(clazz, actualClassLoader));
                                        assertFalse(
                                                bootstrapClasses.contains(className),
                                                "duplicate class: %s".formatted(className));
                                        assertFalse(
                                                systemClasses.contains(className),
                                                "duplicate class: %s".formatted(className));
                                        btLoaderClasses.add(className);
                                    } catch (ClassNotFoundException | NoClassDefFoundError e) {
                                        if (className.startsWith("dev.braintrust")
                                                && (!className.startsWith(
                                                        "dev.braintrust.agent.dd."))
                                                && (!className.startsWith(
                                                        "dev.braintrust.instrumentation."))) {
                                            fail(e);
                                        } else {
                                            // some internal dependencies have optional modules so
                                            // class load errors can be ignored
                                        }
                                    }
                                }
                            });
        }
        assertEquals(ALLOWED_SYSTEM_CLASSPATH_CLASSES.size(), systemClasses.size());
        assertFalse(bootstrapClasses.isEmpty());
        assertFalse(btLoaderClasses.isEmpty());
    }

    @SafeVarargs
    private static void assertNotInSets(String value, Set<String>... sets) {
        for (var set : sets) {
            assertFalse(set.contains(value), "unexpected value for set %s".formatted(value));
        }
    }

    @Test
    void otelAutoconfigureProducesRealTracer() {
        assertEquals(
                0, BraintrustBridge.otelInstallCount.get(), "premain should not initialize otel");
        var otel = GlobalOpenTelemetry.get();
        assertNotNull(otel, "GlobalOpenTelemetry should never be null");
        assertEquals(
                1, BraintrustBridge.otelInstallCount.get(), "calling get() should initialize otel");
        GlobalOpenTelemetry.get();
        assertEquals(
                1,
                BraintrustBridge.otelInstallCount.get(),
                "calling get() multiple times should not do additional installs");

        // The tracer provider should be an SdkTracerProvider, not a noop
        String tracerProviderClassName = otel.getTracerProvider().getClass().getName();
        assertFalse(
                tracerProviderClassName.toLowerCase().contains("noop"),
                "Expected a real TracerProvider from autoconfigure, got: "
                        + tracerProviderClassName);

        Tracer tracer = otel.getTracer("braintrust-agent-test", "1.0.0");
        assertNotNull(tracer);

        Span span = tracer.spanBuilder("test-span").startSpan();
        assertNotNull(span);
        span.end();
        assertNotEquals(
                "io.opentelemetry.api.trace.PropagatedSpan",
                span.getClass().getName(),
                "Expected a real (recording) span, not a propagated/noop span");
    }

    private static boolean startsWithAny(String str, List<String> prefixes) {
        for (String prefix : prefixes) {
            if (str.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }

    /** Converts a JAR entry name to a fully qualified class name. */
    private static String toClassName(String resourceName) {
        if (resourceName.endsWith(".class")) {
            return resourceName.replaceFirst("\\.class$", "").replace('/', '.');
        }
        if (resourceName.endsWith(".classdata")) {
            return resourceName
                    .replaceFirst("^internal/", "")
                    .replaceFirst("\\.classdata$", "")
                    .replace('/', '.');
        } else {
            throw new RuntimeException("unexpected resource: " + resourceName);
        }
    }
}
