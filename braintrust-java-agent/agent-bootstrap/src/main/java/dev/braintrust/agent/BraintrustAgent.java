package dev.braintrust.agent;

import dev.braintrust.bootstrap.BraintrustAutoConfigCustomizer;
import dev.braintrust.bootstrap.BraintrustClassLoader;

import java.io.File;
import java.lang.instrument.Instrumentation;
import java.lang.reflect.Field;
import java.net.URL;
import java.util.jar.JarFile;

/**
 * Braintrust Java Agent entry point.
 *
 * <p>Attach via {@code -javaagent:braintrust-java-agent.jar} to automatically instrument AI client
 * libraries (OpenAI, Anthropic, Google GenAI, LangChain4j) for Braintrust tracing.
 *
 * <p>This class is loaded by the system classloader (since that's where {@code -javaagent} JARs are
 * placed). After calling {@code appendToBootstrapClassLoaderSearch}, a second copy of this class
 * becomes loadable from the bootstrap classloader. The {@link BraintrustAutoConfigCustomizer} (which
 * runs on the bootstrap CL) accesses the bootstrap-loaded copy's {@code agentClassLoader} field, so
 * we must set that field on the bootstrap copy via reflection.
 */
public class BraintrustAgent {

    private static final String INSTALLER_CLASS = "dev.braintrust.agent.internal.AgentInstaller";
    private static final String INSTALLER_METHOD = "install";

    static volatile boolean installed = false;

    /**
     * The isolated classloader for agent internals. This field is set on the <b>bootstrap-loaded</b>
     * copy of this class (via reflection from premain) so that {@link BraintrustAutoConfigCustomizer}
     * can access it during OTel SDK autoconfiguration.
     */
    public static volatile ClassLoader agentClassLoader;

    /** Entry point when the agent is loaded at JVM startup via {@code -javaagent}. */
    public static void premain(String agentArgs, Instrumentation inst) {
        install(agentArgs, inst);
    }

    /** Entry point when the agent is attached to a running JVM via the Attach API. */
    public static void agentmain(String agentArgs, Instrumentation inst) {
        install(agentArgs, inst);
    }

    private static synchronized void install(String agentArgs, Instrumentation inst) {
        if (installed) {
            log("Agent already installed, skipping.");
            return;
        }
        installed = true;

        log("Braintrust Java Agent starting...");

        try {
            // Locate the agent JAR from our own code source
            URL agentJarURL =
                    BraintrustAgent.class.getProtectionDomain().getCodeSource().getLocation();
            File agentJarFile = new File(agentJarURL.toURI());
            log("Agent JAR: " + agentJarFile);

            // Add the agent JAR to the bootstrap classpath. This makes the unshaded OTel API,
            // OTel SDK, and autoconfigure module visible to all classloaders. The .classdata
            // entries remain invisible since the bootstrap CL only looks for .class files.
            //
            // IMPORTANT: After this call, there are TWO copies of BraintrustAgent — one loaded
            // by the system CL (this one, running premain) and one loadable from the bootstrap
            // CL. They have separate static fields. We must set agentClassLoader on the
            // bootstrap copy since that's what BraintrustAutoConfigCustomizer sees.
            inst.appendToBootstrapClassLoaderSearch(new JarFile(agentJarFile, false));
            log("Added agent JAR to bootstrap classpath.");

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

            // Create the isolated classloader that reads .classdata entries from inst/.
            // Parent is null (bootstrap CL) so the agent internals can see bootstrap classes
            // (OTel API/SDK, JDK) but NOT application classes.
            BraintrustClassLoader btClassLoader = new BraintrustClassLoader(agentJarURL, null);

            // Set agentClassLoader on the BOOTSTRAP-loaded copy of BraintrustAgent.
            // BraintrustAutoConfigCustomizer (loaded by bootstrap CL) reads this field.
            Class<?> bootstrapAgentClass = Class.forName(
                    "dev.braintrust.agent.BraintrustAgent", true, null);
            Field agentCLField = bootstrapAgentClass.getDeclaredField("agentClassLoader");
            agentCLField.setAccessible(true);
            agentCLField.set(null, btClassLoader);
            log("Agent classloader stashed on bootstrap BraintrustAgent.");

            // Load and invoke the real agent installer through the isolated classloader.
            // This sets up ByteBuddy instrumentation for AI client libraries.
            Class<?> installerClass = btClassLoader.loadClass(INSTALLER_CLASS);
            installerClass
                    .getMethod(INSTALLER_METHOD, String.class, Instrumentation.class)
                    .invoke(null, agentArgs, inst);

            log("Braintrust Java Agent installed.");
        } catch (Throwable t) {
            log("ERROR: Failed to install Braintrust Java Agent: " + t.getMessage());
            t.printStackTrace(System.err);
        }
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
}
