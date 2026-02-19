package dev.braintrust.agent;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;

import dev.braintrust.bootstrap.BraintrustClassLoader;
import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.Tracer;
import java.io.File;
import java.util.jar.JarFile;
import org.junit.jupiter.api.Test;

class BraintrustAgentTest {

    @Test
    void successfulPremain() throws Exception {
        var systemClassloader = ClassLoader.getSystemClassLoader();
        assertTrue(BraintrustAgent.installed, "premain should install the agent");
        assertEquals(systemClassloader, BraintrustAgent.class.getClassLoader());
        var agentClassLoader = (ClassLoader) Class.forName(BraintrustAgent.class.getName(), true, null).getDeclaredField("agentClassLoader").get(null);
        assertNotNull(agentClassLoader);
        assertNotEquals(systemClassloader, agentClassLoader);
        var byteBuddyClazz = agentClassLoader.loadClass("net.bytebuddy.ByteBuddy");
        assertEquals(agentClassLoader, byteBuddyClazz.getClassLoader());
    }

    @Test
    void correctClasspath() throws Exception {
        final List<String> ALLOWED_BOOTSTRAP_CLASSPATH_PREFIXES = List.of(
                "dev.braintrust.bootstrap.",
                "io.opentelemetry."
        );
        final List<String> ALLOWED_SYSTEM_CLASSPATH_PREFIXES = List.of(
                "dev.braintrust.agent."
        );

        ClassLoader bootstrapClassLoader = null;
        var systemClassloader = ClassLoader.getSystemClassLoader();
        var braintrustClassloader = (BraintrustClassLoader) Class.forName(BraintrustAgent.class.getName(), true, null).getDeclaredField("agentClassLoader").get(null);

        // Get the agent JAR from the system-CL-loaded BraintrustAgent's code source.
        // (The bootstrap-loaded copy doesn't have a code source, so we use the system CL copy.)
        var agentJarUrl = BraintrustAgent.class.getProtectionDomain().getCodeSource().getLocation();
        try (var agentJar = new JarFile(new File(agentJarUrl.toURI()))) {
            agentJar.stream().forEach(entry -> {
                var resourceName = entry.getName();
                if (entry.isDirectory()) {
                    return;
                }
                if (resourceName.endsWith(".class")) {
                    var className = resourceName.replaceFirst(".class$", "").replace('/', '.');
                    try {
                        var clazz = systemClassloader.loadClass(className);
                        if (className.startsWith("dev.braintrust.agent.")) {
                            // ---- SYSTEM CLASSES ----
                            assertEquals(systemClassloader, clazz.getClassLoader(), "unexpected classloader for class %s".formatted(clazz));
                            assertTrue(startsWithAny(className, ALLOWED_SYSTEM_CLASSPATH_PREFIXES), "unexpected class on bootstrap classpath: %s".formatted(resourceName));
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
                    var className = resourceName.replaceFirst(".classdata$", "").replace('/', '.');
                    try {
                        var clazz = braintrustClassloader.loadClass(className);
                        assertEquals(braintrustClassloader, clazz.getClassLoader(), "unexpected classloader for class %s".formatted(clazz));
                    } catch (ClassNotFoundException e) {
                        fail(e);
                    }
                }
            });

        }
    }

    @Test
    void otelAutoconfigureProducesRealTracer() {
        // When the agent is attached, GlobalOpenTelemetry.get() should trigger autoconfigure
        // and return a real (non-noop) OpenTelemetry instance with the Braintrust span processor.
        var otel = GlobalOpenTelemetry.get();
        assertNotNull(otel, "GlobalOpenTelemetry should not be null");

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
}