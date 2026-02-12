package dev.braintrust.agent.muzzle;

import net.bytebuddy.matcher.ElementMatcher;

/**
 * Utility methods for creating classloader matchers that check for the presence of classes
 * without actually loading them (using resource lookups instead).
 */
public final class ClassLoaderMatchers {

    private ClassLoaderMatchers() {}

    /**
     * Returns a matcher that checks if the given class exists on the classloader's classpath
     * by looking up the .class file as a resource. This does NOT trigger class loading,
     * linking, or static initialization.
     *
     * @param className fully qualified class name (e.g. "com.openai.client.OpenAIClient")
     */
    public static ElementMatcher<ClassLoader> hasClassNamed(String className) {
        String resourceName = className.replace('.', '/') + ".class";
        return classLoader -> {
            if (classLoader == null) {
                return ClassLoader.getSystemClassLoader().getResource(resourceName) != null;
            }
            return classLoader.getResource(resourceName) != null;
        };
    }

    /**
     * Returns a matcher that checks if ALL of the given classes exist on the classloader.
     */
    public static ElementMatcher<ClassLoader> hasClassesNamed(String... classNames) {
        return classLoader -> {
            for (String className : classNames) {
                String resourceName = className.replace('.', '/') + ".class";
                boolean found =
                        classLoader == null
                                ? ClassLoader.getSystemClassLoader().getResource(resourceName) != null
                                : classLoader.getResource(resourceName) != null;
                if (!found) return false;
            }
            return true;
        };
    }
}
