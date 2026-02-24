package dev.braintrust.agent.instrumentation;

import net.bytebuddy.dynamic.loading.ClassInjector;

import java.io.IOException;
import java.io.InputStream;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Injects helper classes from the agent classloader into target classloaders.
 *
 * <p>When ByteBuddy advice is inlined into a target class, it may reference "helper" classes
 * that live in the agent's isolated classloader. These helpers are not visible to the target
 * classloader by default. This class reads the helper class bytes from the agent classloader
 * and defines them in the target classloader using {@link ClassInjector.UsingReflection}.
 *
 * <p>Injection is idempotent per (target classloader, module name) pair. The classloader keys
 * are held weakly so they can be garbage collected.
 */
class HelperInjector {

    /**
     * Tracks which modules have been injected into each classloader.
     * Weak keys so classloaders can be GC'd. The inner set tracks module names.
     */
    private static final Map<ClassLoader, Set<String>> INJECTED =
            Collections.synchronizedMap(new WeakHashMap<>());

    private HelperInjector() {}

    /**
     * Injects helper classes into the target classloader if they haven't been injected yet
     * for the given module.
     *
     * @param targetClassLoader the classloader that loaded the class being transformed
     * @param agentClassLoader the agent classloader to read helper class bytes from
     * @param moduleName the instrumentation module name (for dedup and logging)
     * @param helperClassNames the fully-qualified names of helper classes to inject
     */
    static void injectHelpers(
            ClassLoader targetClassLoader,
            ClassLoader agentClassLoader,
            String moduleName,
            List<String> helperClassNames) {
        if (helperClassNames.isEmpty()) {
            return;
        }
        if (targetClassLoader == null) {
            // Bootstrap classloader — can't inject via reflection.
            System.err.println(
                    "[braintrust] WARNING: cannot inject helpers into bootstrap classloader"
                            + " for module: " + moduleName);
            return;
        }

        Set<String> injectedModules =
                INJECTED.computeIfAbsent(targetClassLoader, k -> ConcurrentHashMap.newKeySet());
        if (!injectedModules.add(moduleName)) {
            // Already injected for this classloader + module
            return;
        }

        Map<String, byte[]> classBytes = readHelperBytes(agentClassLoader, helperClassNames);
        if (classBytes.isEmpty()) {
            return;
        }

        new ClassInjector.UsingReflection(targetClassLoader)
                .injectRaw(classBytes);

        System.out.println(
                "[braintrust] Injected "
                        + classBytes.size()
                        + " helper class(es) for module '"
                        + moduleName
                        + "' into "
                        + targetClassLoader.getClass().getName());
    }

    private static Map<String, byte[]> readHelperBytes(
            ClassLoader agentClassLoader, List<String> helperClassNames) {
        Map<String, byte[]> result = new LinkedHashMap<>();
        for (String className : helperClassNames) {
            String resourcePath = className.replace('.', '/') + ".class";
            try (InputStream is = agentClassLoader.getResourceAsStream(resourcePath)) {
                if (is == null) {
                    System.err.println(
                            "[braintrust] WARNING: helper class not found in agent classloader: "
                                    + className);
                    continue;
                }
                result.put(className, is.readAllBytes());
            } catch (IOException e) {
                System.err.println(
                        "[braintrust] WARNING: failed to read helper class "
                                + className + ": " + e);
            }
        }
        return result;
    }
}
