package dev.braintrust.agent.instrumentation;

import net.bytebuddy.asm.Advice;
import net.bytebuddy.description.method.MethodDescription;
import net.bytebuddy.dynamic.ClassFileLocator;
import net.bytebuddy.dynamic.DynamicType;
import net.bytebuddy.matcher.ElementMatcher;

/**
 * Implementation of {@link TypeTransformer} that translates {@link #applyAdviceToMethod} calls
 * into ByteBuddy {@link Advice} visitors on a {@link DynamicType.Builder}.
 */
class TypeTransformerImpl implements TypeTransformer {

    private DynamicType.Builder<?> builder;
    private final ClassLoader agentClassLoader;
    private final ClassFileLocator classFileLocator;

    TypeTransformerImpl(DynamicType.Builder<?> builder, ClassLoader agentClassLoader) {
        this.builder = builder;
        this.agentClassLoader = agentClassLoader;
        this.classFileLocator = ClassFileLocator.ForClassLoader.of(agentClassLoader);
    }

    @Override
    public void applyAdviceToMethod(
            ElementMatcher<? super MethodDescription> methodMatcher, String adviceClassName) {
        // TODO: helper injection — when advice references helper classes that need to be injected
        // into the app classloader, that injection should happen here (or in the transform callback).
        // For now, advice classes must only reference types visible to the app classloader.
        builder =
                builder.visit(
                        Advice.to(loadAdviceClass(adviceClassName), classFileLocator)
                                .on(methodMatcher));
    }

    DynamicType.Builder<?> getBuilder() {
        return builder;
    }

    private Class<?> loadAdviceClass(String adviceClassName) {
        try {
            return Class.forName(adviceClassName, false, agentClassLoader);
        } catch (ClassNotFoundException e) {
            throw new IllegalArgumentException(
                    "Advice class not found: " + adviceClassName, e);
        }
    }
}
