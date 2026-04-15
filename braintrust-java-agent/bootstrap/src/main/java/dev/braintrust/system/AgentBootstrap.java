package dev.braintrust.system;

import java.io.File;
import java.lang.instrument.Instrumentation;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.jar.JarFile;

/**
 * Braintrust Java Agent entry point.
 *
 * <p>Minimal code which bootstraps braintrust classloading and instrumentation.
 */
public class AgentBootstrap {
    private static final String AGENT_CLASS = "dev.braintrust.agent.BraintrustAgent";
    private static final String INSTALLER_METHOD = "install";

    static volatile boolean installed = false;

    /** Entry point when the agent is loaded at JVM startup via {@code -javaagent}. */
    public static void premain(String agentArgs, Instrumentation inst) {
        install(agentArgs, inst);
    }

    /** Entry point when the agent is attached to a running JVM via the Attach API. */
    public static void agentmain(String agentArgs, Instrumentation inst) {
        install(agentArgs, inst);
    }

    private static synchronized void install(String agentArgs, Instrumentation inst) {
        if (AgentBootstrap.class.getClassLoader() != ClassLoader.getSystemClassLoader()) {
            log(
                    "WARNING: install attempted on non-system classloader. aborting. classloader: "
                            + AgentBootstrap.class.getClassLoader());
            return;
        }
        if (installed) {
            log("Agent already installed, skipping.");
            return;
        }

        log("Braintrust Java Agent starting...");
        if (jvmRunningWithDatadogOtelConfig() && (!isRunningAfterDatadogAgent())) {
            log("ERROR: Braintrust agent must run _after_ datadog -javaagent. aborting install.");
            return;
        }

        boolean installOnBootstrap =
                !jvmRunningWithDatadogOtelConfig() && !jvmRunningWithOtelAgent();
        try {
            // Locate the agent JAR from our own code source
            URL agentJarURL =
                    AgentBootstrap.class.getProtectionDomain().getCodeSource().getLocation();
            File agentJarFile = new File(agentJarURL.toURI());
            log("Agent JAR: " + agentJarFile);

            // Enable OTel autoconfigure BEFORE adding to bootstrap, so system properties
            // are set before anything can trigger GlobalOpenTelemetry.get().
            enableOtelSDKAutoconfiguration();

            ClassLoader btClassLoaderParent;
            if (installOnBootstrap) {
                inst.appendToBootstrapClassLoaderSearch(new JarFile(agentJarFile, false));
                btClassLoaderParent = ClassLoader.getPlatformClassLoader();
                log("Added agent JAR to bootstrap classpath.");
            } else {
                btClassLoaderParent =
                        new URLClassLoader(
                                new URL[] {agentJarFile.toURI().toURL()},
                                ClassLoader.getPlatformClassLoader());
                log("skipping bootstrap classpath setup");
            }

            // Load and invoke the real agent installer through the isolated classloader.
            ClassLoader btClassLoader = createBTClassLoader(agentJarURL, btClassLoaderParent);
            Class<?> installerClass = btClassLoader.loadClass(AGENT_CLASS);
            installerClass
                    .getMethod(INSTALLER_METHOD, String.class, Instrumentation.class)
                    .invoke(null, agentArgs, inst);
            log("Braintrust Java Agent installed.");
            installed = true;
        } catch (Throwable t) {
            log("ERROR: Failed to install Braintrust Java Agent: " + t.getMessage());
            log(t);
        }
    }

    private static ClassLoader createBTClassLoader(URL agentJarURL, ClassLoader btClassLoaderParent)
            throws Exception {
        // NOTE: not caching because we only invoke this once
        var bridgeClass =
                btClassLoaderParent.loadClass("dev.braintrust.bootstrap.BraintrustBridge");
        var createMethod =
                bridgeClass.getMethod("createBraintrustClassLoader", URL.class, ClassLoader.class);
        return (ClassLoader) createMethod.invoke(null, agentJarURL, btClassLoaderParent);
    }

    /**
     * Checks whether the OpenTelemetry Java agent is present by looking for its premain class on
     * the system classloader. Since {@code -javaagent} JARs are always on the system classpath,
     * this works regardless of agent ordering.
     */
    private static boolean jvmRunningWithOtelAgent() {
        try {
            Class.forName(
                    "io.opentelemetry.javaagent.OpenTelemetryAgent",
                    false,
                    ClassLoader.getSystemClassLoader());
            return true;
        } catch (ClassNotFoundException e) {
            return false;
        }
    }

    /** Checks whether the Datadog agent is present and configured for OTel integration */
    private static boolean jvmRunningWithDatadogOtelConfig() {
        String sysProp = System.getProperty("dd.trace.otel.enabled");
        if (sysProp != null) {
            return Boolean.parseBoolean(sysProp);
        }
        String envVar = System.getenv("DD_TRACE_OTEL_ENABLED");
        return Boolean.parseBoolean(envVar);
    }

    /**
     * Returns true if the Datadog agent's premain has already executed, meaning it was listed
     * before the Braintrust agent in the {@code -javaagent} flags.
     */
    private static boolean isRunningAfterDatadogAgent() {
        // DD's premain appends its jars to the bootstrap classpath, making
        // {@code datadog.trace.bootstrap.Agent} loadable from the bootstrap (null)
        // classloader. If that class is not found on bootstrap, DD either isn't
        // present or hasn't run its premain yet (i.e. BT is first).
        try {
            Class.forName("datadog.trace.bootstrap.Agent", false, null);
            return true;
        } catch (ClassNotFoundException e) {
            return false;
        }
    }

    private static void enableOtelSDKAutoconfiguration() {
        // Enable OTel SDK autoconfiguration. When anyone first calls
        // GlobalOpenTelemetry.get(), the SDK will be built using autoconfigure, which
        // discovers our BraintrustAutoConfigCustomizer via ServiceLoader and injects the
        // Braintrust span processor/exporter into the tracer provider.
        setPropertyIfAbsent("otel.java.global-autoconfigure.enabled", "true");

        // Set default exporter config. We don't want autoconfigure to try loading
        // OTLP exporters from the bootstrap classpath (they live in BraintrustClassLoader).
        // Our BraintrustAutoConfigCustomizer adds the real span processor/exporter.
        setPropertyIfAbsent("otel.traces.exporter", "none");
        setPropertyIfAbsent("otel.metrics.exporter", "none");
        setPropertyIfAbsent("otel.logs.exporter", "none");
        log("Enabled OTel SDK autoconfiguration.");
    }

    /** Sets a system property only if it hasn't been set already (respects user overrides). */
    private static void setPropertyIfAbsent(String key, String value) {
        if (System.getProperty(key) == null) {
            System.setProperty(key, value);
        }
    }

    private static void log(String msg) {
        System.out.println("[braintrust] " + msg);
    }

    private static void log(Throwable t) {
        t.printStackTrace(System.err);
    }
}
