package dev.braintrust.agent.muzzle;

import static org.junit.jupiter.api.Assertions.*;

import net.bytebuddy.jar.asm.Opcodes;
import org.junit.jupiter.api.Test;

class ReferenceTest {

    // --- Flag matching ---

    @Test
    void matchesPublicFlag() {
        assertTrue(Reference.matches(Reference.EXPECTS_PUBLIC, Opcodes.ACC_PUBLIC));
        assertFalse(Reference.matches(Reference.EXPECTS_PUBLIC, 0));
        assertFalse(Reference.matches(Reference.EXPECTS_PUBLIC, Opcodes.ACC_PRIVATE));
    }

    @Test
    void matchesPublicOrProtectedFlag() {
        assertTrue(Reference.matches(Reference.EXPECTS_PUBLIC_OR_PROTECTED, Opcodes.ACC_PUBLIC));
        assertTrue(Reference.matches(Reference.EXPECTS_PUBLIC_OR_PROTECTED, Opcodes.ACC_PROTECTED));
        assertFalse(Reference.matches(Reference.EXPECTS_PUBLIC_OR_PROTECTED, 0)); // package-private
        assertFalse(Reference.matches(Reference.EXPECTS_PUBLIC_OR_PROTECTED, Opcodes.ACC_PRIVATE));
    }

    @Test
    void matchesNonPrivateFlag() {
        assertTrue(Reference.matches(Reference.EXPECTS_NON_PRIVATE, Opcodes.ACC_PUBLIC));
        assertTrue(Reference.matches(Reference.EXPECTS_NON_PRIVATE, Opcodes.ACC_PROTECTED));
        assertTrue(Reference.matches(Reference.EXPECTS_NON_PRIVATE, 0)); // package-private is OK
        assertFalse(Reference.matches(Reference.EXPECTS_NON_PRIVATE, Opcodes.ACC_PRIVATE));
    }

    @Test
    void matchesStaticFlag() {
        assertTrue(Reference.matches(Reference.EXPECTS_STATIC, Opcodes.ACC_STATIC));
        assertFalse(Reference.matches(Reference.EXPECTS_STATIC, 0));
    }

    @Test
    void matchesNonStaticFlag() {
        assertTrue(Reference.matches(Reference.EXPECTS_NON_STATIC, 0));
        assertFalse(Reference.matches(Reference.EXPECTS_NON_STATIC, Opcodes.ACC_STATIC));
    }

    @Test
    void matchesInterfaceFlag() {
        assertTrue(Reference.matches(Reference.EXPECTS_INTERFACE, Opcodes.ACC_INTERFACE));
        assertFalse(Reference.matches(Reference.EXPECTS_INTERFACE, 0));
    }

    @Test
    void matchesNonInterfaceFlag() {
        assertTrue(Reference.matches(Reference.EXPECTS_NON_INTERFACE, 0));
        assertFalse(Reference.matches(Reference.EXPECTS_NON_INTERFACE, Opcodes.ACC_INTERFACE));
    }

    @Test
    void matchesNonFinalFlag() {
        assertTrue(Reference.matches(Reference.EXPECTS_NON_FINAL, 0));
        assertFalse(Reference.matches(Reference.EXPECTS_NON_FINAL, Opcodes.ACC_FINAL));
    }

    @Test
    void combinedFlagsAllMustMatch() {
        int flags = Reference.EXPECTS_PUBLIC | Reference.EXPECTS_STATIC;
        assertTrue(Reference.matches(flags, Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC));
        assertFalse(Reference.matches(flags, Opcodes.ACC_PUBLIC)); // not static
        assertFalse(Reference.matches(flags, Opcodes.ACC_STATIC)); // not public
    }

    @Test
    void zeroFlagsMatchAnything() {
        assertTrue(Reference.matches(0, Opcodes.ACC_PUBLIC));
        assertTrue(Reference.matches(0, Opcodes.ACC_PRIVATE));
        assertTrue(Reference.matches(0, 0));
    }

    // --- Builder ---

    @Test
    void builderProducesCorrectReference() {
        Reference ref = new Reference.Builder("com/example/Foo")
                .withSource("Test.java", 42)
                .withFlag(Reference.EXPECTS_PUBLIC)
                .withSuperName("com/example/Bar")
                .withInterface("com/example/Baz")
                .withField(new String[]{"Test.java:42"}, Reference.EXPECTS_PUBLIC, "name", "Ljava/lang/String;")
                .withMethod(new String[]{"Test.java:42"}, Reference.EXPECTS_PUBLIC, "run", "V")
                .build();

        assertEquals("com.example.Foo", ref.className);
        assertEquals("com.example.Bar", ref.superName);
        assertEquals(Reference.EXPECTS_PUBLIC, ref.flags);
        assertEquals(1, ref.interfaces.length);
        assertEquals("com/example/Baz", ref.interfaces[0]);
        assertEquals(1, ref.fields.length);
        assertEquals("name", ref.fields[0].name);
        assertEquals(1, ref.methods.length);
        assertEquals("run", ref.methods[0].name);
    }

    @Test
    void builderConvertsDottedClassName() {
        // Internal names use '/', builder should convert to '.'
        Reference ref = new Reference.Builder("com/example/Foo").build();
        assertEquals("com.example.Foo", ref.className);
    }

    // --- Merge ---

    @Test
    void mergesCombinesFlagsAndMembers() {
        Reference r1 = new Reference.Builder("com/example/Foo")
                .withSource("A.java", 1)
                .withFlag(Reference.EXPECTS_PUBLIC)
                .withField(new String[]{"A.java:1"}, 0, "x", "I")
                .build();

        Reference r2 = new Reference.Builder("com/example/Foo")
                .withSource("B.java", 2)
                .withFlag(Reference.EXPECTS_NON_FINAL)
                .withMethod(new String[]{"B.java:2"}, 0, "doIt", "V")
                .build();

        Reference merged = r1.merge(r2);
        assertEquals("com.example.Foo", merged.className);
        assertEquals(Reference.EXPECTS_PUBLIC | Reference.EXPECTS_NON_FINAL, merged.flags);
        assertEquals(2, merged.sources.length);
        assertEquals(1, merged.fields.length);
        assertEquals(1, merged.methods.length);
    }

    @Test
    void mergeSameMethodCombinesFlags() {
        Reference r1 = new Reference.Builder("com/example/Foo")
                .withMethod(new String[]{"A:1"}, Reference.EXPECTS_PUBLIC, "doIt", "V")
                .build();

        Reference r2 = new Reference.Builder("com/example/Foo")
                .withMethod(new String[]{"B:2"}, Reference.EXPECTS_NON_STATIC, "doIt", "V")
                .build();

        Reference merged = r1.merge(r2);
        assertEquals(1, merged.methods.length);
        assertEquals(Reference.EXPECTS_PUBLIC | Reference.EXPECTS_NON_STATIC, merged.methods[0].flags);
    }

    @Test
    void mergeThrowsForDifferentClassNames() {
        Reference r1 = new Reference.Builder("com/example/Foo").build();
        Reference r2 = new Reference.Builder("com/example/Bar").build();

        assertThrows(IllegalStateException.class, () -> r1.merge(r2));
    }

    // --- Utility ---

    @Test
    void toClassName() {
        assertEquals("com.example.Foo", Reference.toClassName("com/example/Foo"));
        assertEquals("Foo", Reference.toClassName("Foo"));
    }

    @Test
    void toResourceName() {
        assertEquals("com/example/Foo.class", Reference.toResourceName("com.example.Foo"));
    }
}
