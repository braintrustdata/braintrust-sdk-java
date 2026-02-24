package dev.braintrust.agent.instrumentation;

import dev.braintrust.agent.muzzle.MuzzleCheck;
import dev.braintrust.agent.muzzle.ReferenceCreator;
import dev.braintrust.agent.muzzle.Reference;
import dev.braintrust.agent.muzzle.ReferenceMatcher;
import java.lang.instrument.Instrumentation;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.ServiceLoader;
import java.util.Set;

import net.bytebuddy.agent.builder.AgentBuilder;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.dynamic.DynamicType;
import net.bytebuddy.matcher.ElementMatcher;
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
     * @param agentClassloader the classloader to use for ServiceLoader discovery and advice
     *     class resolution (typically the BraintrustClassLoader)
     */
    public static void install(Instrumentation inst, ClassLoader agentClassloader) {
        var agentBuilder =
                new AgentBuilder.Default()
                        // Use retransformation so we can instrument classes already loaded
                        .with(AgentBuilder.RedefinitionStrategy.RETRANSFORMATION)
                        .disableClassFormatChanges()
                        .with(new LoggingListener());

        int moduleCount = 0;
        int typeCount = 0;

        for (InstrumentationModule module :
                ServiceLoader.load(InstrumentationModule.class, agentClassloader)) {
            System.out.println("[braintrust] Discovered instrumentation module: " + module.name());

            // Build muzzle references from all advice classes in this module
            ReferenceMatcher muzzle = buildMuzzleReferences(module, agentClassloader);
            MuzzleCheck muzzleCheck = new MuzzleCheck(module, muzzle);

            // Combine the module's classloader matcher with the muzzle check.
            // Module's matcher runs first (cheap hasClassNamed check), then muzzle (expensive).
            ElementMatcher<ClassLoader> classLoaderMatcher =
                    cl -> module.classLoaderMatcher().matches(cl) && muzzleCheck.matches(cl);

            for (TypeInstrumentation typeInst : module.typeInstrumentations()) {
                agentBuilder =
                        agentBuilder
                                .type(typeInst.typeMatcher(), classLoaderMatcher)
                                .transform(
                                        (builder,
                                                typeDescription,
                                                targetClassloader,
                                                javaModule,
                                                protectionDomain) -> {
                                            HelperInjector.injectHelpers(
                                                    targetClassloader,
                                                    agentClassloader,
                                                    module.name(),
                                                    module.getHelperClassNames());

                                            var transformer =
                                                    new TypeTransformerImpl(
                                                            builder, agentClassloader);
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
     * Builds muzzle references by scanning the bytecode of all advice classes used by this
     * module's type instrumentations. References to helper classes (which will be injected)
     * and to the instrumentation framework itself are excluded.
     */
    private static ReferenceMatcher buildMuzzleReferences(
            InstrumentationModule module, ClassLoader agentClassLoader) {
        Map<String, Reference> references = new LinkedHashMap<>();
        Set<String> helperClasses = new HashSet<>(module.getHelperClassNames());

        for (TypeInstrumentation typeInst : module.typeInstrumentations()) {
            // Collect advice class names by using a capturing TypeTransformer
            AdviceCollector collector = new AdviceCollector();
            typeInst.transform(collector);
            for (String adviceClass : collector.adviceClasses) {
                for (Map.Entry<String, Reference> entry :
                        ReferenceCreator.createReferencesFrom(adviceClass, agentClassLoader)
                                .entrySet()) {
                    // Skip helper classes — they'll be injected, not expected on the app classpath
                    if (helperClasses.contains(entry.getKey())) {
                        continue;
                    }
                    Reference existing = references.get(entry.getKey());
                    if (existing == null) {
                        references.put(entry.getKey(), entry.getValue());
                    } else {
                        references.put(entry.getKey(), existing.merge(entry.getValue()));
                    }
                }
            }
        }
        return new ReferenceMatcher(references.values().toArray(new Reference[0]));
    }

    /**
     * A TypeTransformer that just collects advice class names without doing anything.
     */
    private static class AdviceCollector implements TypeTransformer {
        final Set<String> adviceClasses = new HashSet<>();

        @Override
        public void applyAdviceToMethod(
                net.bytebuddy.matcher.ElementMatcher<? super net.bytebuddy.description.method.MethodDescription> methodMatcher,
                String adviceClassName) {
            adviceClasses.add(adviceClassName);
        }
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
