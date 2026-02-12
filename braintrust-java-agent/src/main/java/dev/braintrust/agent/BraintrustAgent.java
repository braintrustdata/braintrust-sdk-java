package dev.braintrust.agent;

import java.lang.instrument.Instrumentation;

/**
 * Braintrust Java Agent entry point.
 *
 * <p>Attach via {@code -javaagent:braintrust-java-agent.jar} to automatically instrument AI client
 * libraries (OpenAI, Anthropic, Google GenAI, LangChain4j) for Braintrust tracing.
 */
public class BraintrustAgent {

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
            log("[braintrust] Agent already installed, skipping.");
            return;
        }
        installed = true;

        log("[braintrust] Braintrust Java Agent starting...");
        log("[braintrust] Agent args: " + agentArgs);
        log(
                "[braintrust] Instrumentation available: "
                        + (inst != null
                                ? "yes (retransform="
                                        + inst.isRetransformClassesSupported()
                                        + ")"
                                : "no"));
        log("[braintrust] Braintrust Java Agent installed.");
    }

    private static void log(String msg) {
      System.out.println(msg);
    }
}
