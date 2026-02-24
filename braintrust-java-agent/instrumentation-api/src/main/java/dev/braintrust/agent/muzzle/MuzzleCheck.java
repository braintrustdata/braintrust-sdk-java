package dev.braintrust.agent.muzzle;

import dev.braintrust.agent.instrumentation.InstrumentationModule;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import net.bytebuddy.matcher.ElementMatcher;

/**
 * A ByteBuddy {@link ElementMatcher} that checks muzzle references against a classloader
 * before allowing an instrumentation module to be applied.
 *
 * <p>Results are cached per classloader to avoid repeated checking.
 */
public class MuzzleCheck implements ElementMatcher<ClassLoader> {

    private final String moduleName;
    private final ReferenceMatcher referenceMatcher;

    /** Cache of classloader -> muzzle match result. */
    private final Map<ClassLoader, Boolean> cache = new ConcurrentHashMap<>();

    public MuzzleCheck(InstrumentationModule module, ReferenceMatcher referenceMatcher) {
        this.moduleName = module.name();
        this.referenceMatcher = referenceMatcher;
    }

    @Override
    public boolean matches(ClassLoader classLoader) {
        if (referenceMatcher.getReferences().length == 0) {
            return true; // no references to check
        }

        // Use identity-based key for classloader caching
        return cache.computeIfAbsent(classLoader, cl -> {
            boolean matches = referenceMatcher.matches(cl);
            if (!matches) {
                List<Reference.Mismatch> mismatches =
                        referenceMatcher.getMismatchedReferenceSources(cl);
                System.out.println("[braintrust] Muzzled " + moduleName
                        + " — classloader=" + cl);
                for (Reference.Mismatch mismatch : mismatches) {
                    System.out.println("[braintrust]   mismatch: " + mismatch);
                }
            }
            return matches;
        });
    }
}
