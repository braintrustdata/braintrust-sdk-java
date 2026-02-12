package dev.braintrust.agent.muzzle;

import static org.junit.jupiter.api.Assertions.*;

import dev.braintrust.agent.instrumentation.InstrumentationModule;
import dev.braintrust.agent.instrumentation.TypeInstrumentation;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.Test;

class MuzzleCheckTest {

    /**
     * MuzzleCheck should return true for a classloader where all references are satisfied.
     */
    @Test
    void matchesWhenAllReferencesPresent() {
        Reference ref = new Reference.Builder("java/util/ArrayList")
                .withSource("Test", 1)
                .withFlag(Reference.EXPECTS_PUBLIC)
                .build();

        MuzzleCheck check = new MuzzleCheck(fakeModule("test"), new ReferenceMatcher(ref));
        assertTrue(check.matches(getClass().getClassLoader()));
    }

    /**
     * MuzzleCheck should return false when a reference is missing.
     */
    @Test
    void failsWhenReferenceMissing() {
        Reference ref = new Reference.Builder("com/example/DoesNotExist")
                .withSource("Test", 1)
                .build();

        MuzzleCheck check = new MuzzleCheck(fakeModule("test"), new ReferenceMatcher(ref));
        assertFalse(check.matches(getClass().getClassLoader()));
    }

    /**
     * MuzzleCheck should cache results per classloader — calling matches() twice on the same
     * classloader should return the same result without recomputing.
     */
    @Test
    void cachesResultPerClassLoader() {
        // We can verify caching by using a reference that always matches (JDK type)
        Reference ref = new Reference.Builder("java/util/HashMap")
                .withSource("Test", 1)
                .withFlag(Reference.EXPECTS_PUBLIC)
                .build();

        MuzzleCheck check = new MuzzleCheck(fakeModule("test"), new ReferenceMatcher(ref));
        ClassLoader cl = getClass().getClassLoader();

        assertTrue(check.matches(cl));
        assertTrue(check.matches(cl)); // second call should use cache
    }

    /**
     * Different classloaders should get independent results.
     */
    @Test
    void differentClassLoadersGetIndependentResults() {
        // java.util.ArrayList is on every classloader
        Reference ref = new Reference.Builder("java/util/ArrayList")
                .withSource("Test", 1)
                .withFlag(Reference.EXPECTS_PUBLIC)
                .build();

        MuzzleCheck check = new MuzzleCheck(fakeModule("test"), new ReferenceMatcher(ref));

        // Both should match since ArrayList is always available
        assertTrue(check.matches(getClass().getClassLoader()));
        assertTrue(check.matches(new URLClassLoader(new URL[0], null)));
    }

    /**
     * An empty ReferenceMatcher (no references) should always pass.
     */
    @Test
    void emptyReferencesAlwaysPass() {
        MuzzleCheck check = new MuzzleCheck(fakeModule("test"), new ReferenceMatcher());
        assertTrue(check.matches(getClass().getClassLoader()));
    }

    /**
     * When a classloader doesn't have a required class, muzzle should reject it.
     * A minimal URLClassLoader with no URLs won't have app classes.
     */
    @Test
    void rejectsClassLoaderMissingRequiredClass() {
        // Reference a class from our test code that won't be on an empty classloader
        Reference ref = new Reference.Builder("dev/braintrust/agent/muzzle/MuzzleCheckTest")
                .withSource("Test", 1)
                .withFlag(Reference.EXPECTS_PUBLIC)
                .build();

        MuzzleCheck check = new MuzzleCheck(fakeModule("test"), new ReferenceMatcher(ref));

        // Empty classloader (no parent = no delegation to app classloader)
        ClassLoader empty = new URLClassLoader(new URL[0], null);
        assertFalse(check.matches(empty));
    }

    // --- helpers ---

    private static InstrumentationModule fakeModule(String name) {
        return new InstrumentationModule(name) {
            @Override
            public List<TypeInstrumentation> typeInstrumentations() {
                return Collections.emptyList();
            }
        };
    }
}
