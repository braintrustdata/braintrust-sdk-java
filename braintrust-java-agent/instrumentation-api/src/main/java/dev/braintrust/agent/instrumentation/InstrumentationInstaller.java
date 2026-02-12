package dev.braintrust.agent.instrumentation;

import java.lang.instrument.Instrumentation;
import java.util.ServiceLoader;

import net.bytebuddy.agent.builder.AgentBuilder;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.dynamic.DynamicType;
import net.bytebuddy.utility.JavaModule;

/**
 * Discovers all {@link InstrumentationModule}s via {@link ServiceLoader} and wires them into a
 * ByteBuddy {@link AgentBuilder} that gets installed on the JVM.
 */
public class InstrumentationInstaller {

    /**
     * Discovers instrumentation modules from the given classloader, builds the ByteBuddy
     * agent, and installs it on the JVM instrumentation instance.
     *
     * @param inst the JVM {@link Instrumentation} instance from premain/agentmain
     * @param classloader the classloader to use for ServiceLoader discovery and advice
     *     class resolution (typically the BraintrustClassLoader)
     */
    public static void install(Instrumentation inst, ClassLoader classloader) {
        var agentBuilder =
                new AgentBuilder.Default()
                        // Use retransformation so we can instrument classes already loaded
                        .with(AgentBuilder.RedefinitionStrategy.RETRANSFORMATION)
                        .disableClassFormatChanges()
                        .with(new LoggingListener());

        int moduleCount = 0;
        int typeCount = 0;

        for (InstrumentationModule module :
                ServiceLoader.load(InstrumentationModule.class, classloader)) {
            System.out.println("[braintrust] Discovered instrumentation module: " + module.name());

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
                                                            builder, classloader);
                                            typeInst.transform(transformer);
                                            return transformer.getBuilder();
                                        });
                typeCount++;
            }
            moduleCount++;
        }

        agentBuilder.installOn(inst);
        System.out.println(
                "[braintrust] ByteBuddy instrumentation installed: "
                        + moduleCount
                        + " module(s), "
                        + typeCount
                        + " type instrumentation(s).");
    }

    /**
     * ByteBuddy listener for logging transformation events.
     */
    private static class LoggingListener extends AgentBuilder.Listener.Adapter {
        @Override
        public void onError(
                String typeName,
                ClassLoader classLoader,
                JavaModule module,
                boolean loaded,
                Throwable throwable) {
            System.err.println("[braintrust] ERROR transforming " + typeName + ": " + throwable.getMessage());
        }

        @Override
        public void onTransformation(
                TypeDescription typeDescription,
                ClassLoader classLoader,
                JavaModule module,
                boolean loaded,
                DynamicType dynamicType) {
            System.out.println("[braintrust] Transformed: " + typeDescription.getName());
        }
    }
}
