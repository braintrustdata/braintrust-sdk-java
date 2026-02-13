package dev.braintrust.agent.internal;

import java.lang.instrument.Instrumentation;

/**
 * The real agent installation logic, loaded by {@code BraintrustClassLoader} in an isolated
 * classloader.
 *
 * <p>This class and all its dependencies (ByteBuddy, OTel SDK, etc.) are invisible to the
 * application's classpath. They exist as {@code .classdata} entries inside the agent JAR and are
 * only accessible through the agent's custom classloader.
 */
public class AgentInstaller {

    /**
     * Called reflectively from {@code BraintrustAgent.premain()} via the isolated classloader.
     *
     * @param agentArgs the agent arguments from the {@code -javaagent} flag
     * @param inst the JVM instrumentation instance
     */
    public static void install(String agentArgs, Instrumentation inst) {
        System.out.println("[braintrust]   AgentInstaller.install() called");
        System.out.println("[braintrust]   AgentInstaller classloader: "
                + AgentInstaller.class.getClassLoader().getClass().getName());
        System.out.println("[braintrust]   Agent args: " + agentArgs);
        System.out.println("[braintrust]   Instrumentation: retransform="
                + inst.isRetransformClassesSupported());

        // TODO: This is where ByteBuddy AgentBuilder setup will go.
        // For now, just prove the classloader isolation works.
    }
}
