package dev.braintrust.agent.muzzle;

import static org.junit.jupiter.api.Assertions.*;

import java.util.Map;
import org.junit.jupiter.api.Test;

class ReferenceCreatorTest {

    /**
     * Scan our own TestAdvice class (which calls ArrayList.add and references HashMap)
     * and verify that external references are extracted.
     */
    @Test
    void extractsMethodReferenceFromAdvice() {
        Map<String, Reference> refs =
                ReferenceCreator.createReferencesFrom(
                        "dev.braintrust.agent.instrumentation.openai.FakeAdvice",
                        getClass().getClassLoader());

        // FakeAdvice calls FakeLibraryClass.doWork(String) and reads FakeLibraryClass.VERSION
        // Both FakeLibraryClass references should be found.
        assertTrue(refs.containsKey("dev.braintrust.agent.instrumentation.openai.FakeLibraryClass"),
                "Should find reference to FakeLibraryClass; got keys: " + refs.keySet());

        Reference ref = refs.get("dev.braintrust.agent.instrumentation.openai.FakeLibraryClass");
        assertNotNull(ref);

        // Should find the doWork method reference
        boolean foundMethod = false;
        for (Reference.Method m : ref.methods) {
            if (m.name.equals("doWork")) {
                foundMethod = true;
                // Should be non-static (invokevirtual)
                assertTrue((m.flags & Reference.EXPECTS_NON_STATIC) != 0,
                        "doWork should be EXPECTS_NON_STATIC");
            }
        }
        assertTrue(foundMethod, "Should find doWork method reference");

        // Should find the VERSION field reference
        boolean foundField = false;
        for (Reference.Field f : ref.fields) {
            if (f.name.equals("VERSION")) {
                foundField = true;
                // Static field access => EXPECTS_STATIC
                assertTrue((f.flags & Reference.EXPECTS_STATIC) != 0,
                        "VERSION should be EXPECTS_STATIC");
            }
        }
        assertTrue(foundField, "Should find VERSION field reference");
    }

    @Test
    void ignoresJdkTypes() {
        Map<String, Reference> refs =
                ReferenceCreator.createReferencesFrom(
                        "dev.braintrust.agent.instrumentation.openai.FakeAdvice",
                        getClass().getClassLoader());

        // java.lang.String, java.util.*, etc. should all be filtered out
        for (String key : refs.keySet()) {
            assertFalse(key.startsWith("java."), "Should not contain JDK type: " + key);
        }
    }

    @Test
    void ignoresByteBuddyAnnotations() {
        Map<String, Reference> refs =
                ReferenceCreator.createReferencesFrom(
                        "dev.braintrust.agent.instrumentation.openai.FakeAdvice",
                        getClass().getClassLoader());

        for (String key : refs.keySet()) {
            assertFalse(key.startsWith("net.bytebuddy."),
                    "Should not contain ByteBuddy type: " + key);
        }
    }

    @Test
    void ignoresFrameworkClasses() {
        Map<String, Reference> refs =
                ReferenceCreator.createReferencesFrom(
                        "dev.braintrust.agent.instrumentation.openai.FakeAdvice",
                        getClass().getClassLoader());

        // Framework types like InstrumentationModule should be ignored
        for (String key : refs.keySet()) {
            if (key.startsWith("dev.braintrust.agent.instrumentation.")) {
                // Should only contain types within an instrumentation subpackage (openai, etc.)
                assertTrue(
                        key.contains(".openai.") || key.contains(".anthropic.")
                                || key.contains(".genai.") || key.contains(".langchain."),
                        "Non-instrumentation framework class should be ignored: " + key);
            }
        }
    }

    @Test
    void followsBfsIntoHelperClasses() {
        // FakeAdvice -> calls FakeHelper.record() which is in the instrumentation.openai package
        // FakeHelper references FakeLibraryClass.parse() — BFS should discover that too
        Map<String, Reference> refs =
                ReferenceCreator.createReferencesFrom(
                        "dev.braintrust.agent.instrumentation.openai.FakeAdvice",
                        getClass().getClassLoader());

        Reference libRef = refs.get("dev.braintrust.agent.instrumentation.openai.FakeLibraryClass");
        assertNotNull(libRef, "BFS should find FakeLibraryClass referenced transitively via FakeHelper");

        // Check that FakeHelper's reference to parse() is also discovered
        boolean foundParse = false;
        for (Reference.Method m : libRef.methods) {
            if (m.name.equals("parse")) {
                foundParse = true;
            }
        }
        assertTrue(foundParse,
                "BFS through FakeHelper should discover FakeLibraryClass.parse() reference");
    }

    @Test
    void extractsClassFlagsFromMethodInsn() {
        Map<String, Reference> refs =
                ReferenceCreator.createReferencesFrom(
                        "dev.braintrust.agent.instrumentation.openai.FakeAdvice",
                        getClass().getClassLoader());

        Reference ref = refs.get("dev.braintrust.agent.instrumentation.openai.FakeLibraryClass");
        assertNotNull(ref);

        // FakeAdvice and FakeLibraryClass are in the SAME package, so minimum class access
        // is EXPECTS_NON_PRIVATE (not EXPECTS_PUBLIC).
        assertTrue((ref.flags & Reference.EXPECTS_NON_PRIVATE) != 0,
                "Same-package class reference should expect NON_PRIVATE");
    }

    @Test
    void extractsReturnAndParameterTypeReferences() {
        Map<String, Reference> refs =
                ReferenceCreator.createReferencesFrom(
                        "dev.braintrust.agent.instrumentation.openai.FakeAdvice",
                        getClass().getClassLoader());

        // FakeAdvice calls FakeLibraryClass.createResult() which returns FakeResult
        assertTrue(refs.containsKey("dev.braintrust.agent.instrumentation.openai.FakeResult"),
                "Should find reference to FakeResult (return type); got keys: " + refs.keySet());
    }

    @Test
    void emptyAdviceProducesNoExternalReferences() {
        Map<String, Reference> refs =
                ReferenceCreator.createReferencesFrom(
                        "dev.braintrust.agent.instrumentation.openai.EmptyAdvice",
                        getClass().getClassLoader());

        // EmptyAdvice doesn't reference any external classes
        assertTrue(refs.isEmpty(), "Empty advice should produce no external references, got: " + refs.keySet());
    }
}
