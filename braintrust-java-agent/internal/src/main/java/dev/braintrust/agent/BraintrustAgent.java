package dev.braintrust.agent;

import dev.braintrust.Braintrust;
import dev.braintrust.agent.dd.BTInterceptor;
import dev.braintrust.bootstrap.BraintrustClassLoader;
import dev.braintrust.instrumentation.Instrumenter;
import java.lang.instrument.Instrumentation;
import lombok.extern.slf4j.Slf4j;

/** The real agent installation logic */
@Slf4j
public class BraintrustAgent {

    /** Called reflectively from AgentBootstrap premain. */
    public static void install(String agentArgs, Instrumentation inst) {
        if (!(BraintrustAgent.class.getClassLoader() instanceof BraintrustClassLoader)) {
            throw new IllegalStateException(
                    "Braintrust agent can only run on a braintrust classloader: "
                            + BraintrustAgent.class.getClassLoader());
        }
        log.info(
                "invoked on classloader: {}",
                BraintrustAgent.class.getClassLoader().getClass().getName());
        log.info("agentArgs: {}", agentArgs);
        log.info("Instrumentation: retransform={}", inst.isRetransformClassesSupported());
        // Fail fast if there are any issues with the Braintrust SDK
        Braintrust.get();
        Instrumenter.install(inst, BraintrustAgent.class.getClassLoader());
        if (jvmRunningWithDatadogOtelConfig() && ddApiOnBootstrapClasspath()) {
            BTInterceptor.install();
        }
    }

    /** Checks whether the Datadog agent is present and configured for OTel integration */
    private static boolean ddApiOnBootstrapClasspath() {
        try {
            BraintrustAgent.class.getClassLoader().loadClass("datadog.trace.api.GlobalTracer");
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
}
