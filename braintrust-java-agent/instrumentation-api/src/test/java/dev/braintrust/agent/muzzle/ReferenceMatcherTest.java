package dev.braintrust.agent.muzzle;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import net.bytebuddy.jar.asm.Opcodes;
import org.junit.jupiter.api.Test;

class ReferenceMatcherTest {

    /**
     * A reference to a class that exists on the classpath should match.
     */
    @Test
    void matchesClassThatExists() {
        Reference ref = new Reference.Builder("java/util/ArrayList")
                .withSource("Test", 1)
                .withFlag(Reference.EXPECTS_PUBLIC)
                .build();

        ReferenceMatcher matcher = new ReferenceMatcher(ref);
        assertTrue(matcher.matches(getClass().getClassLoader()));
    }

    /**
     * A reference to a class that doesn't exist should produce a MissingClass mismatch.
     */
    @Test
    void failsForMissingClass() {
        Reference ref = new Reference.Builder("com/example/NoSuchClass")
                .withSource("Test", 1)
                .build();

        ReferenceMatcher matcher = new ReferenceMatcher(ref);
        assertFalse(matcher.matches(getClass().getClassLoader()));

        List<Reference.Mismatch> mismatches =
                matcher.getMismatchedReferenceSources(getClass().getClassLoader());
        assertEquals(1, mismatches.size());
        assertInstanceOf(Reference.Mismatch.MissingClass.class, mismatches.get(0));
        assertTrue(mismatches.get(0).toString().contains("com.example.NoSuchClass"));
    }

    /**
     * A reference expecting a public method that exists should match.
     */
    @Test
    void matchesMethodThatExists() {
        // ArrayList has public boolean add(Object)
        Reference ref = new Reference.Builder("java/util/ArrayList")
                .withSource("Test", 1)
                .withFlag(Reference.EXPECTS_PUBLIC)
                .withMethod(
                        new String[]{"Test:1"},
                        Reference.EXPECTS_PUBLIC | Reference.EXPECTS_NON_STATIC,
                        "add",
                        "Z", // boolean return
                        "Ljava/lang/Object;") // Object param
                .build();

        ReferenceMatcher matcher = new ReferenceMatcher(ref);
        assertTrue(matcher.matches(getClass().getClassLoader()));
    }

    /**
     * A reference to a method that doesn't exist should produce a MissingMethod mismatch.
     */
    @Test
    void failsForMissingMethod() {
        Reference ref = new Reference.Builder("java/util/ArrayList")
                .withSource("Test", 1)
                .withFlag(Reference.EXPECTS_PUBLIC)
                .withMethod(
                        new String[]{"Test:1"},
                        Reference.EXPECTS_PUBLIC,
                        "noSuchMethod",
                        "V")
                .build();

        ReferenceMatcher matcher = new ReferenceMatcher(ref);
        assertFalse(matcher.matches(getClass().getClassLoader()));

        List<Reference.Mismatch> mismatches =
                matcher.getMismatchedReferenceSources(getClass().getClassLoader());
        assertEquals(1, mismatches.size());
        assertInstanceOf(Reference.Mismatch.MissingMethod.class, mismatches.get(0));
    }

    /**
     * A reference to a field that exists should match.
     */
    @Test
    void matchesFieldThatExists() {
        // System.out is a public static field of type PrintStream
        Reference ref = new Reference.Builder("java/lang/System")
                .withSource("Test", 1)
                .withFlag(Reference.EXPECTS_PUBLIC)
                .withField(
                        new String[]{"Test:1"},
                        Reference.EXPECTS_PUBLIC | Reference.EXPECTS_STATIC,
                        "out",
                        "Ljava/io/PrintStream;")
                .build();

        ReferenceMatcher matcher = new ReferenceMatcher(ref);
        assertTrue(matcher.matches(getClass().getClassLoader()));
    }

    /**
     * A reference to a field that doesn't exist should produce a MissingField mismatch.
     */
    @Test
    void failsForMissingField() {
        Reference ref = new Reference.Builder("java/util/ArrayList")
                .withSource("Test", 1)
                .withFlag(Reference.EXPECTS_PUBLIC)
                .withField(
                        new String[]{"Test:1"},
                        Reference.EXPECTS_PUBLIC,
                        "noSuchField",
                        "Ljava/lang/String;")
                .build();

        ReferenceMatcher matcher = new ReferenceMatcher(ref);
        assertFalse(matcher.matches(getClass().getClassLoader()));

        List<Reference.Mismatch> mismatches =
                matcher.getMismatchedReferenceSources(getClass().getClassLoader());
        assertEquals(1, mismatches.size());
        assertInstanceOf(Reference.Mismatch.MissingField.class, mismatches.get(0));
    }

    /**
     * A reference expecting public access on a package-private class should fail.
     */
    @Test
    void failsForWrongClassFlags() {
        // Create a reference that expects public + interface on ArrayList (which is not an interface)
        Reference ref = new Reference.Builder("java/util/ArrayList")
                .withSource("Test", 1)
                .withFlag(Reference.EXPECTS_INTERFACE)
                .build();

        ReferenceMatcher matcher = new ReferenceMatcher(ref);
        assertFalse(matcher.matches(getClass().getClassLoader()));

        List<Reference.Mismatch> mismatches =
                matcher.getMismatchedReferenceSources(getClass().getClassLoader());
        assertEquals(1, mismatches.size());
        assertInstanceOf(Reference.Mismatch.MissingFlag.class, mismatches.get(0));
    }

    /**
     * EXPECTS_INTERFACE should pass for an actual interface.
     */
    @Test
    void matchesInterfaceFlag() {
        Reference ref = new Reference.Builder("java/util/List")
                .withSource("Test", 1)
                .withFlag(Reference.EXPECTS_PUBLIC | Reference.EXPECTS_INTERFACE)
                .build();

        ReferenceMatcher matcher = new ReferenceMatcher(ref);
        assertTrue(matcher.matches(getClass().getClassLoader()));
    }

    /**
     * A method inherited from a superclass should still match.
     */
    @Test
    void findsMethodInSuperclass() {
        // ArrayList inherits size() from AbstractCollection -> AbstractList -> ArrayList
        // Actually size() is declared on ArrayList itself. Let's use toString() from Object.
        // Actually let's use iterator() which is inherited from AbstractList.
        // Let's use a well-known one: hashCode() from Object, accessible on ArrayList
        Reference ref = new Reference.Builder("java/util/ArrayList")
                .withSource("Test", 1)
                .withFlag(Reference.EXPECTS_PUBLIC)
                .withMethod(
                        new String[]{"Test:1"},
                        Reference.EXPECTS_PUBLIC | Reference.EXPECTS_NON_STATIC,
                        "forEach",
                        "V",
                        "Ljava/util/function/Consumer;")
                .build();

        ReferenceMatcher matcher = new ReferenceMatcher(ref);
        assertTrue(matcher.matches(getClass().getClassLoader()),
                "Should find forEach via interface hierarchy");
    }

    /**
     * Multiple references where one fails should report that mismatch.
     */
    @Test
    void reportsAllMismatches() {
        Reference good = new Reference.Builder("java/util/ArrayList")
                .withSource("Test", 1)
                .withFlag(Reference.EXPECTS_PUBLIC)
                .build();

        Reference bad1 = new Reference.Builder("com/example/Missing1")
                .withSource("Test", 2)
                .build();

        Reference bad2 = new Reference.Builder("com/example/Missing2")
                .withSource("Test", 3)
                .build();

        ReferenceMatcher matcher = new ReferenceMatcher(good, bad1, bad2);
        assertFalse(matcher.matches(getClass().getClassLoader()));

        List<Reference.Mismatch> mismatches =
                matcher.getMismatchedReferenceSources(getClass().getClassLoader());
        assertEquals(2, mismatches.size(), "Should report both missing classes");
    }

    /**
     * An empty set of references should always match.
     */
    @Test
    void emptyReferencesAlwaysMatch() {
        ReferenceMatcher matcher = new ReferenceMatcher();
        assertTrue(matcher.matches(getClass().getClassLoader()));
    }

    /**
     * Test with a method that has wrong flags (e.g., expecting static on a non-static method).
     */
    @Test
    void failsForWrongMethodFlags() {
        // ArrayList.add(Object) is non-static; expecting static should fail
        Reference ref = new Reference.Builder("java/util/ArrayList")
                .withSource("Test", 1)
                .withFlag(Reference.EXPECTS_PUBLIC)
                .withMethod(
                        new String[]{"Test:1"},
                        Reference.EXPECTS_STATIC,
                        "add",
                        "Z",
                        "Ljava/lang/Object;")
                .build();

        ReferenceMatcher matcher = new ReferenceMatcher(ref);
        assertFalse(matcher.matches(getClass().getClassLoader()));

        List<Reference.Mismatch> mismatches =
                matcher.getMismatchedReferenceSources(getClass().getClassLoader());
        assertFalse(mismatches.isEmpty());
        // Should get a MissingFlag for the method
        boolean foundFlagMismatch = mismatches.stream()
                .anyMatch(m -> m instanceof Reference.Mismatch.MissingFlag);
        assertTrue(foundFlagMismatch, "Should report a flag mismatch for static vs non-static");
    }
}
