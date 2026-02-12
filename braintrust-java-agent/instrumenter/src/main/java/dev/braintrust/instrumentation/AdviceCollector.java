package dev.braintrust.instrumentation;

import java.util.HashSet;
import java.util.Set;
import net.bytebuddy.description.method.MethodDescription;
import net.bytebuddy.matcher.ElementMatcher;

/**
 * A {@link TypeTransformer} that collects advice class names without applying any transformations.
 *
 * <p>Used by the instrumenter and muzzle generator to discover which advice classes a {@link
 * TypeInstrumentation} references.
 */
public class AdviceCollector implements TypeTransformer {
    private final Set<String> adviceClasses = new HashSet<>();

    @Override
    public void applyAdviceToMethod(
            ElementMatcher<? super MethodDescription> methodMatcher, String adviceClassName) {
        adviceClasses.add(adviceClassName);
    }

    /** Returns the collected advice class names. */
    public Set<String> getAdviceClasses() {
        return adviceClasses;
    }
}
