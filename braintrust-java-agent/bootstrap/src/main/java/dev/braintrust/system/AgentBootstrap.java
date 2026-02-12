package dev.braintrust.system;

import dev.braintrust.bootstrap.BraintrustBridge;
import dev.braintrust.bootstrap.BraintrustClassLoader;

import java.io.File;
import java.lang.instrument.Instrumentation;
import java.net.URL;
import java.util.jar.JarFile;
import java.util.logging.LogManager;

/**
 * Braintrust Java Agent entry point.
 *
 * Minimal code which bootstraps braintrust classloading and instrumentation.
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
            log("WARNING: install attempted on non-system classloader. aborting. classloader: " + AgentBootstrap.class.getClassLoader() );
            return;
        }
        if (installed) {
            log("Agent already installed, skipping.");
            return;
        }

        log("Braintrust Java Agent starting...");

        try {
            // Locate the agent JAR from our own code source
            URL agentJarURL =
                    AgentBootstrap.class.getProtectionDomain().getCodeSource().getLocation();
            File agentJarFile = new File(agentJarURL.toURI());
            log("Agent JAR: " + agentJarFile);

            // Enable OTel autoconfigure BEFORE adding to bootstrap, so system properties
            // are set before anything can trigger GlobalOpenTelemetry.get().
            enableOtelSDKAutoconfiguration();

            inst.appendToBootstrapClassLoaderSearch(new JarFile(agentJarFile, false));
            log("Added agent JAR to bootstrap classpath.");

            // Create the isolated braintrust classloader.
            // Parent is the platform classloader so agent internals can see:
            //   - Bootstrap classes (OTel API/SDK added via appendToBootstrapClassLoaderSearch)
            //   - JDK platform modules (java.net.http, java.sql, etc.)
            // but NOT application classes (those are on the system/app classloader).
            BraintrustClassLoader btClassLoader = new BraintrustClassLoader(agentJarURL, ClassLoader.getPlatformClassLoader());
            BraintrustBridge.setAgentClassloaderIfAbsent(btClassLoader);


            // Load and invoke the real agent installer through the isolated classloader.
            Class<?> installerClass = btClassLoader.loadClass(AGENT_CLASS);
            installerClass.getMethod(INSTALLER_METHOD, String.class, Instrumentation.class).invoke(null, agentArgs, inst);
            log("Braintrust Java Agent installed.");
            installed = true;
        } catch (Throwable t) {
            log("ERROR: Failed to install Braintrust Java Agent: " + t.getMessage());
            log(t);
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
