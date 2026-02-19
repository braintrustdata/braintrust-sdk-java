package dev.braintrust.system;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;

import dev.braintrust.bootstrap.BraintrustBridge;
import dev.braintrust.bootstrap.BraintrustClassLoader;
import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.Tracer;
import java.io.File;
import java.util.jar.JarFile;
import org.junit.jupiter.api.Test;

class AgentBootstrapTest {

    @Test
    void successfulPremain() throws Exception {
        var systemClassloader = ClassLoader.getSystemClassLoader();
        assertTrue(AgentBootstrap.installed, "premain should install the agent");
        assertEquals(systemClassloader, AgentBootstrap.class.getClassLoader());
        assertNotNull(BraintrustBridge.getAgentClassLoader(), "premain should set up agent classloader");
        assertNotEquals(systemClassloader, BraintrustBridge.getAgentClassLoader());
        assertNull(BraintrustBridge.class.getClassLoader(), "bt bootstrap must run on tb classloader");
        assertNull(BraintrustClassLoader.class.getClassLoader(), "bt classloader must be loaded on bootstrap");
        var byteBuddyClazz = BraintrustBridge.getAgentClassLoader().loadClass("net.bytebuddy.ByteBuddy");
        assertEquals(BraintrustBridge.getAgentClassLoader(), byteBuddyClazz.getClassLoader());
    }

    @Test
    void correctClasspath() throws Exception {
        final List<String> ALLOWED_BOOTSTRAP_CLASSPATH_PREFIXES = List.of(
                "dev.braintrust.bootstrap.",
                "io.opentelemetry."
        );
        final List<String> ALLOWED_SYSTEM_CLASSPATH_CLASSES = List.of(
                // only these specific classes are allowed to appear on the system classpath. everything else must be on the bootstrap or braintrust classpaths
                "dev.braintrust.system.AgentBootstrap"
        );

        ClassLoader bootstrapClassLoader = null;
        var systemClassloader = ClassLoader.getSystemClassLoader();
        var braintrustClassloader = BraintrustBridge.getAgentClassLoader();

        // Get the agent JAR from the system-CL-loaded BraintrustAgent's code source.
        // (The bootstrap-loaded copy doesn't have a code source, so we use the system CL copy.)
        var agentJarUrl = AgentBootstrap.class.getProtectionDomain().getCodeSource().getLocation();
        try (var agentJar = new JarFile(new File(agentJarUrl.toURI()))) {
            agentJar.stream().forEach(entry -> {
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
                            assertEquals(systemClassloader, clazz.getClassLoader(), "unexpected classloader for class %s".formatted(clazz));
                        } else {
                            // ---- BOOTSTRAP CLASSES ----
                            assertEquals(bootstrapClassLoader, clazz.getClassLoader(), "unexpected classloader for class %s".formatted(clazz));
                            assertTrue(startsWithAny(className, ALLOWED_BOOTSTRAP_CLASSPATH_PREFIXES), "unexpected class on bootstrap classpath: %s".formatted(resourceName));
                        }
                    } catch (ClassNotFoundException e) {
                        // NOTE: class load failures can happen in some of the transitive deps that have optional modules. We can ignore those failures
                        if (className.startsWith("dev.braintrust.")) {
                            fail(e);
                        }
                    }
                } else if (resourceName.endsWith(".classdata")) {
                    // ---- BRAINTRUST CLASSES ----
                    var className = toClassName(resourceName);
                    try {
                        var clazz = braintrustClassloader.loadClass(className);
                        assertEquals(braintrustClassloader, clazz.getClassLoader(), "unexpected classloader for class %s".formatted(clazz));
                    } catch (ClassNotFoundException | NoClassDefFoundError e) {
                        // Linkage failures can happen for transitive optional deps (e.g. gRPC).
                        // Only fail for our own classes which must always load cleanly.
                        if (className.startsWith("dev.braintrust.")) {
                            fail(e);
                        }
                    }
                }
            });

        }
    }

    @Test
    void otelAutoconfigureProducesRealTracer() {
        assertEquals(0, BraintrustBridge.otelInstallCount.get(), "premain should not initialize otel");
        var otel = GlobalOpenTelemetry.get();
        assertNotNull(otel, "GlobalOpenTelemetry should never be null");
        assertEquals(1, BraintrustBridge.otelInstallCount.get(), "calling get() should initialize otel");
        GlobalOpenTelemetry.get();
        assertEquals(1, BraintrustBridge.otelInstallCount.get(), "calling get() multiple times should not do additional installs");

        // The tracer provider should be an SdkTracerProvider, not a noop
        String tracerProviderClassName = otel.getTracerProvider().getClass().getName();
        assertFalse(tracerProviderClassName.toLowerCase().contains("noop"), "Expected a real TracerProvider from autoconfigure, got: " + tracerProviderClassName);

        Tracer tracer = otel.getTracer("braintrust-agent-test", "1.0.0");
        assertNotNull(tracer);

        Span span = tracer.spanBuilder("test-span").startSpan();
        assertNotNull(span);
        span.end();
        assertNotEquals("io.opentelemetry.api.trace.PropagatedSpan", span.getClass().getName(), "Expected a real (recording) span, not a propagated/noop span");
    }

    private static boolean startsWithAny(String str, List<String> prefixes) {
        for (String prefix : prefixes) {
            if (str.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Converts a JAR entry name to a fully qualified class name.
     */
    private static String toClassName(String resourceName) {
        return resourceName
                .replaceFirst("\\.(class|classdata)$", "")
                .replace('/', '.');
    }
}
