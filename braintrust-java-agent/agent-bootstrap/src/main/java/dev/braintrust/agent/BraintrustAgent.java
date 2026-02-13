package dev.braintrust.agent;

import java.lang.instrument.Instrumentation;
import java.net.URL;

/**
 * Braintrust Java Agent entry point.
 *
 * <p>Attach via {@code -javaagent:braintrust-java-agent.jar} to automatically instrument AI client
 * libraries (OpenAI, Anthropic, Google GenAI, LangChain4j) for Braintrust tracing.
 *
 * <p>This class is intentionally dependency-free. It lives on the system classloader (since that's
 * where {@code -javaagent} JARs are loaded) and its only job is to create a {@link
 * BraintrustClassLoader} that loads the real agent implementation from hidden {@code .classdata}
 * entries inside the agent JAR.
 */
public class BraintrustAgent {

    private static final String INSTALLER_CLASS = "dev.braintrust.agent.internal.AgentInstaller";
    private static final String INSTALLER_METHOD = "install";

    private static volatile boolean installed = false;

    /**
     * Entry point when the agent is loaded at JVM startup via {@code -javaagent}.
     */
    public static void premain(String agentArgs, Instrumentation inst) {
        install(agentArgs, inst);
    }

    /**
     * Entry point when the agent is attached to a running JVM via the Attach API.
     */
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
            log("Agent JAR: " + agentJarURL);

            // Create the isolated classloader that reads .classdata entries from inst/
            ClassLoader parent = BraintrustAgent.class.getClassLoader();
            BraintrustClassLoader agentClassLoader = new BraintrustClassLoader(agentJarURL, parent);

            // Load and invoke the real agent installer through the isolated classloader
            Class<?> installerClass = agentClassLoader.loadClass(INSTALLER_CLASS);
            installerClass
                    .getMethod(INSTALLER_METHOD, String.class, Instrumentation.class)
                    .invoke(null, agentArgs, inst);

            log("Braintrust Java Agent installed.");
        } catch (Throwable t) {
            log("ERROR: Failed to install Braintrust Java Agent: " + t.getMessage());
            t.printStackTrace(System.err);
        }
    }

    private static void log(String msg) {
        System.out.println("[braintrust] " + msg);
    }
}
