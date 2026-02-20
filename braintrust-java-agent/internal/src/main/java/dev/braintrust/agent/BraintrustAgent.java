package dev.braintrust.agent;

import java.lang.instrument.Instrumentation;
import java.util.ServiceLoader;

import dev.braintrust.Braintrust;
import dev.braintrust.agent.instrumentation.InstrumentationModule;
import dev.braintrust.agent.instrumentation.TypeInstrumentation;
import dev.braintrust.bootstrap.BraintrustBridge;
import dev.braintrust.bootstrap.BraintrustClassLoader;
import io.opentelemetry.sdk.autoconfigure.spi.AutoConfigurationCustomizer;
import io.opentelemetry.sdk.logs.SdkLoggerProvider;
import io.opentelemetry.sdk.metrics.SdkMeterProvider;
import lombok.extern.slf4j.Slf4j;
import net.bytebuddy.agent.builder.AgentBuilder;

/**
 * The real agent installation logic
 */
@Slf4j
public class BraintrustAgent {

    /**
     * Called reflectively from AgentBootstrap premain.
     */
    public static void install(String agentArgs, Instrumentation inst) {
        if (!(BraintrustAgent.class.getClassLoader() instanceof BraintrustClassLoader)) {
            throw new IllegalCallerException(
                    "Braintrust agent can only run on a braintrust classloader");
        }
        log.info("invoked on classloader: {}", BraintrustAgent.class.getClassLoader().getClass().getName());
        log.info("agentArgs: {}", agentArgs);
        log.info("Instrumentation: retransform={}", inst.isRetransformClassesSupported());
        // Fail fast if there are any issues with the Braintrust SDK
        Braintrust.get();

        installInstrumentation(inst);
    }

    /**
     * Discovers all {@link InstrumentationModule}s via ServiceLoader and wires them into a
     * ByteBuddy {@link AgentBuilder} that gets installed on the JVM.
     */
    private static void installInstrumentation(Instrumentation inst) {
        ClassLoader agentClassLoader = BraintrustAgent.class.getClassLoader();

        var agentBuilder =
                new AgentBuilder.Default()
                        // Use retransformation so we can instrument classes already loaded
                        .with(AgentBuilder.RedefinitionStrategy.RETRANSFORMATION)
                        .disableClassFormatChanges()
                        .with(new LoggingListener());

        int moduleCount = 0;
        int typeCount = 0;

        for (InstrumentationModule module :
                ServiceLoader.load(InstrumentationModule.class, agentClassLoader)) {
            log.info("-- discovered instrumentation module: {}", module.name());
            for (TypeInstrumentation typeInst : module.typeInstrumentations()) {
                agentBuilder =
                        agentBuilder
                                .type(typeInst.typeMatcher(), module.classLoaderMatcher())
                                .transform(
                                        (builder,
                                                typeDescription,
                                                classLoader,
                                                javaModule,
                                                protectionDomain) -> {
                                            var transformer =
                                                    new TypeTransformerImpl(
                                                            builder, agentClassLoader);
                                            typeInst.transform(transformer);
                                            return transformer.getBuilder();
                                        });
                typeCount++;
            }
            moduleCount++;
        }

        agentBuilder.installOn(inst);
        log.info("ByteBuddy instrumentation installed. moduleCount: {}, typeCount: {}", moduleCount, typeCount);
    }

    /**
     * Called reflectively from OtelAutoConfiguration.
     */
    public void configureOpenTelemetry(AutoConfigurationCustomizer autoConfiguration) {
        autoConfiguration.addTracerProviderCustomizer(
                ((sdkTracerProviderBuilder, configProperties) -> {
                    var loggerBuilder = SdkLoggerProvider.builder();
                    var meterBuilder = SdkMeterProvider.builder();
                    Braintrust.get()
                            .openTelemetryEnable(
                                    sdkTracerProviderBuilder, loggerBuilder, meterBuilder);
                    var installCount = BraintrustBridge.otelInstallCount.incrementAndGet();
                    if (installCount > 1) {
                        log.warn("unexpected otel install count: {}", installCount);
                    }
                    return sdkTracerProviderBuilder;
                }));
    }

    /**
     * ByteBuddy listener for logging transformation events.
     */
    private static class LoggingListener extends AgentBuilder.Listener.Adapter {
        @Override
        public void onError(
                String typeName,
                ClassLoader classLoader,
                net.bytebuddy.utility.JavaModule module,
                boolean loaded,
                Throwable throwable) {
            log.error("transforming " + typeName, throwable);
        }

        @Override
        public void onTransformation(
                net.bytebuddy.description.type.TypeDescription typeDescription,
                ClassLoader classLoader,
                net.bytebuddy.utility.JavaModule module,
                boolean loaded,
                net.bytebuddy.dynamic.DynamicType dynamicType) {
            log.debug("transformed {}", typeDescription.getName());
        }
    }
}
