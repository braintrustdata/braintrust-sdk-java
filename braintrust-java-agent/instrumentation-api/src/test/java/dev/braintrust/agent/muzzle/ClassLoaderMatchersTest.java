package dev.braintrust.agent.muzzle;

import static org.junit.jupiter.api.Assertions.*;

import java.net.URL;
import java.net.URLClassLoader;
import net.bytebuddy.matcher.ElementMatcher;
import org.junit.jupiter.api.Test;

class ClassLoaderMatchersTest {

    @Test
    void hasClassNamedFindsExistingClass() {
        ElementMatcher<ClassLoader> matcher =
                ClassLoaderMatchers.hasClassNamed("java.util.ArrayList");
        assertTrue(matcher.matches(getClass().getClassLoader()));
    }

    @Test
    void hasClassNamedReturnsFalseForMissingClass() {
        ElementMatcher<ClassLoader> matcher =
                ClassLoaderMatchers.hasClassNamed("com.example.NoSuchClass");
        assertFalse(matcher.matches(getClass().getClassLoader()));
    }

    @Test
    void hasClassesNamedRequiresAll() {
        ElementMatcher<ClassLoader> matcher =
                ClassLoaderMatchers.hasClassesNamed(
                        "java.util.ArrayList",
                        "java.util.HashMap",
                        "java.lang.String");
        assertTrue(matcher.matches(getClass().getClassLoader()));
    }

    @Test
    void hasClassesNamedFailsIfAnyMissing() {
        ElementMatcher<ClassLoader> matcher =
                ClassLoaderMatchers.hasClassesNamed(
                        "java.util.ArrayList",
                        "com.example.DoesNotExist");
        assertFalse(matcher.matches(getClass().getClassLoader()));
    }

    @Test
    void worksWithEmptyClassLoader() {
        ClassLoader empty = new URLClassLoader(new URL[0], null);

        // JDK classes should still be found (bootstrap classloader)
        ElementMatcher<ClassLoader> jdkMatcher =
                ClassLoaderMatchers.hasClassNamed("java.util.ArrayList");
        assertTrue(jdkMatcher.matches(empty));

        // App classes should NOT be found
        ElementMatcher<ClassLoader> appMatcher =
                ClassLoaderMatchers.hasClassNamed(
                        "dev.braintrust.agent.muzzle.ClassLoaderMatchersTest");
        assertFalse(appMatcher.matches(empty));
    }

    @Test
    void worksWithNullClassLoader() {
        // null classloader = bootstrap classloader
        ElementMatcher<ClassLoader> matcher =
                ClassLoaderMatchers.hasClassNamed("java.util.ArrayList");
        assertTrue(matcher.matches(null));
    }

    @Test
    void nullClassLoaderFallsBackToSystemClassLoader() {
        // When classLoader == null, ClassLoaderMatchers falls back to the system classloader,
        // which DOES have app classes. This is by design — null CL means bootstrap, but we
        // delegate to getSystemClassLoader() to find resources.
        ElementMatcher<ClassLoader> matcher =
                ClassLoaderMatchers.hasClassNamed(
                        "dev.braintrust.agent.muzzle.ClassLoaderMatchersTest");
        // System classloader has our test classes on its classpath
        assertTrue(matcher.matches(null));
    }
}
