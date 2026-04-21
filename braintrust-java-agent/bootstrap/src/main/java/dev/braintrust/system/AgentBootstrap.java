package dev.braintrust.system;

import java.io.File;
import java.lang.instrument.Instrumentation;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.ArrayList;
import java.util.List;
import java.util.jar.JarFile;
import java.util.jar.Manifest;

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
        var javaAgents = detectAgents();

        log("Braintrust Java Agent starting...");
        if (jvmRunningWithDatadogOtelConfig() && (!isRunningAfterDatadogAgent(javaAgents))) {
            log("ERROR: Braintrust agent must run _after_ datadog -javaagent. aborting install.");
            return;
        }

        boolean installOnBootstrap =
                !jvmRunningWithDatadogOtelConfig() && !jvmRunningWithOtelAgent(javaAgents);
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

    enum AgentKind {
        BRAINTRUST,
        OPENTELEMETRY,
        DATADOG,
        UNKNOWN
    }

    /**
     * Inspects all {@code -javaagent} JARs on the command line and returns their kinds by peeking
     * at each JAR's {@code META-INF/MANIFEST.MF} {@code Premain-Class} attribute. Works regardless
     * of agent ordering since it reads the command line directly rather than relying on premain
     * side-effects.
     */
    static List<AgentKind> detectAgents() {
        var agents = new ArrayList<AgentKind>();
        try {
            var info = ProcessHandle.current().info();
            var arguments = info.arguments();
            if (arguments.isPresent()) {
                for (var arg : arguments.get()) {
                    if (!arg.startsWith("-javaagent:")) continue;
                    agents.add(classifyAgentJar(stripAgentPath(arg)));
                }
            } else {
                // arguments() is often empty on Linux; fall back to commandLine()
                var cmdLine = info.commandLine();
                if (cmdLine.isPresent()) {
                    agents.addAll(detectAgentsFromCommandLine(cmdLine.get()));
                }
            }
        } catch (Throwable t) {
            log("error detecting agents: " + t.getMessage());
            log(t);
        }
        return agents;
    }

    /** Extracts {@code -javaagent:} entries from a raw command line string. */
    static List<AgentKind> detectAgentsFromCommandLine(String cmdLine) {
        var agents = new ArrayList<AgentKind>();
        var prefix = "-javaagent:";
        int idx = 0;
        while ((idx = cmdLine.indexOf(prefix, idx)) >= 0) {
            int start = idx + prefix.length();
            // The jar path ends at the next whitespace (or end of string).
            // An '=' before whitespace separates jar path from agent args.
            int end = start;
            while (end < cmdLine.length() && cmdLine.charAt(end) != ' ') {
                end++;
            }
            var token = cmdLine.substring(start, end);
            var eqIdx = token.indexOf('=');
            var jarPath = eqIdx >= 0 ? token.substring(0, eqIdx) : token;
            agents.add(classifyAgentJar(jarPath));
            idx = end;
        }
        return agents;
    }

    private static String stripAgentPath(String arg) {
        var jarPath = arg.substring("-javaagent:".length());
        var eqIdx = jarPath.indexOf('=');
        if (eqIdx >= 0) jarPath = jarPath.substring(0, eqIdx);
        return jarPath;
    }

    private static AgentKind classifyAgentJar(String jarPath) {
        try (var jar = new JarFile(jarPath, false)) {
            Manifest manifest = jar.getManifest();
            if (manifest == null) return AgentKind.UNKNOWN;
            var premain = manifest.getMainAttributes().getValue("Premain-Class");
            if (premain == null) return AgentKind.UNKNOWN;
            if (premain.startsWith("dev.braintrust.")) return AgentKind.BRAINTRUST;
            if (premain.startsWith("io.opentelemetry.javaagent.")) return AgentKind.OPENTELEMETRY;
            if (premain.startsWith("datadog.")) return AgentKind.DATADOG;
            return AgentKind.UNKNOWN;
        } catch (Throwable ignored) {
            return AgentKind.UNKNOWN;
        }
    }

    private static boolean jvmRunningWithOtelAgent(List<AgentKind> agentKinds) {
        return agentKinds.contains(AgentKind.OPENTELEMETRY);
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

    /** Returns true if dd agent is present and bt agent's premain runs after the dd premain */
    private static boolean isRunningAfterDatadogAgent(List<AgentKind> agents) {
        Integer ddIndex = null;
        Integer btIndex = null;
        for (int i = 0; i < agents.size(); i++) {
            if (agents.get(i).equals(AgentKind.DATADOG)) {
                ddIndex = i;
            } else if (agents.get(i).equals(AgentKind.BRAINTRUST)) {
                btIndex = i;
            }
            if (ddIndex != null && btIndex != null) {
                break;
            }
        }
        return (ddIndex != null) && (btIndex != null) && btIndex > ddIndex;
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
