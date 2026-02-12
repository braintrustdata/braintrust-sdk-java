package dev.braintrust.instrumentation.muzzle;

import dev.braintrust.instrumentation.InstrumentationModule;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;
import lombok.extern.slf4j.Slf4j;
import net.bytebuddy.matcher.ElementMatcher;

/**
 * A ByteBuddy {@link ElementMatcher} that checks muzzle references against a classloader before
 * allowing an instrumentation module to be applied.
 *
 * <p>Results are cached per classloader using weak keys so that classloaders can be garbage
 * collected on redeploy in app-server environments.
 */
@Slf4j
public class MuzzleCheck implements ElementMatcher<ClassLoader> {
    private final String moduleName;
    private final ReferenceMatcher referenceMatcher;

    /** Cache of classloader -> muzzle match result. Uses weak keys to avoid classloader leaks. */
    private final Map<ClassLoader, Boolean> cache =
            Collections.synchronizedMap(new WeakHashMap<>());

    public MuzzleCheck(InstrumentationModule module, ReferenceMatcher referenceMatcher) {
        this.moduleName = module.name();
        this.referenceMatcher = referenceMatcher;
    }

    @Override
    public boolean matches(ClassLoader classLoader) {
        if (referenceMatcher.getReferences().length == 0) {
            return true; // no references to check
        }

        if (classLoader == null) {
            // TODO: support fetching resources from the bootstrap classpath
            return true;
        }

        Boolean cached = cache.get(classLoader);
        if (cached != null) {
            return cached;
        }

        boolean matches = referenceMatcher.matches(classLoader);
        if (!matches) {
            List<Reference.Mismatch> mismatches =
                    referenceMatcher.getMismatchedReferenceSources(classLoader);
            log.info("instrumentation skip {} - {}", classLoader, moduleName);
            for (Reference.Mismatch mismatch : mismatches) {
                log.info("  mismatch: {}", mismatch);
            }
        } else {
            log.info("instrumentation apply {} - {}", classLoader, moduleName);
        }

        cache.put(classLoader, matches);
        return matches;
    }
}
