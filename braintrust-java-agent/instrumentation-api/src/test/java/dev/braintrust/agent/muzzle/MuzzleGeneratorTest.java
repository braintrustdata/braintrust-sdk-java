package dev.braintrust.agent.muzzle;

import static org.junit.jupiter.api.Assertions.*;

import dev.braintrust.agent.instrumentation.InstrumentationModule;
import dev.braintrust.agent.instrumentation.TypeInstrumentation;
import dev.braintrust.agent.instrumentation.TypeTransformer;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.matcher.ElementMatcher;
import org.junit.jupiter.api.Test;

import static net.bytebuddy.matcher.ElementMatchers.named;

class MuzzleGeneratorTest {

    /**
     * Verify that the generated $Muzzle bytecode produces a valid ReferenceMatcher
     * whose references match what ReferenceCreator would extract at runtime.
     */
    @Test
    void generatedMuzzleMatchesRuntimeReferences() throws Exception {
        FakeMuzzleModule module = new FakeMuzzleModule();
        ClassLoader cl = getClass().getClassLoader();

        // 1. Collect references at runtime (the gold standard)
        Reference[] runtimeRefs = MuzzleGenerator.collectReferences(module, cl);
        assertTrue(runtimeRefs.length > 0, "Should have at least one reference");

        // 2. Generate $Muzzle class bytecode
        String moduleInternalName = module.getClass().getName().replace('.', '/');
        byte[] muzzleBytes = MuzzleGenerator.generateMuzzleClass(module, moduleInternalName, cl);
        assertNotNull(muzzleBytes);
        assertTrue(muzzleBytes.length > 0);

        // 3. Load the generated class and invoke create()
        String muzzleClassName = module.getClass().getName() + "$Muzzle";
        ByteArrayClassLoader loader = new ByteArrayClassLoader(muzzleClassName, muzzleBytes, cl);
        Class<?> muzzleClass = loader.loadClass(muzzleClassName);
        Method createMethod = muzzleClass.getMethod("create");
        ReferenceMatcher matcher = (ReferenceMatcher) createMethod.invoke(null);

        // 4. Verify the generated ReferenceMatcher has the same references
        Reference[] generatedRefs = matcher.getReferences();
        assertEquals(runtimeRefs.length, generatedRefs.length,
                "Generated and runtime references should have same count");

        // Check that all class names match
        Set<String> runtimeClassNames = Arrays.stream(runtimeRefs)
                .map(r -> r.className).collect(Collectors.toSet());
        Set<String> generatedClassNames = Arrays.stream(generatedRefs)
                .map(r -> r.className).collect(Collectors.toSet());
        assertEquals(runtimeClassNames, generatedClassNames,
                "Generated references should cover the same classes");

        // Check fields and methods for each reference
        Map<String, Reference> runtimeMap = Arrays.stream(runtimeRefs)
                .collect(Collectors.toMap(r -> r.className, r -> r));
        Map<String, Reference> generatedMap = Arrays.stream(generatedRefs)
                .collect(Collectors.toMap(r -> r.className, r -> r));

        for (String className : runtimeClassNames) {
            Reference runtime = runtimeMap.get(className);
            Reference generated = generatedMap.get(className);

            assertEquals(runtime.flags, generated.flags,
                    "Flags mismatch for " + className);
            assertEquals(runtime.superName, generated.superName,
                    "SuperName mismatch for " + className);
            assertArrayEquals(runtime.interfaces, generated.interfaces,
                    "Interfaces mismatch for " + className);
            assertEquals(runtime.fields.length, generated.fields.length,
                    "Field count mismatch for " + className);
            assertEquals(runtime.methods.length, generated.methods.length,
                    "Method count mismatch for " + className);

            // Check each field
            for (int i = 0; i < runtime.fields.length; i++) {
                assertEquals(runtime.fields[i].name, generated.fields[i].name,
                        "Field name mismatch at index " + i + " for " + className);
                assertEquals(runtime.fields[i].fieldType, generated.fields[i].fieldType,
                        "Field type mismatch for " + className + "#" + runtime.fields[i].name);
                assertEquals(runtime.fields[i].flags, generated.fields[i].flags,
                        "Field flags mismatch for " + className + "#" + runtime.fields[i].name);
            }

            // Check each method
            for (int i = 0; i < runtime.methods.length; i++) {
                assertEquals(runtime.methods[i].name, generated.methods[i].name,
                        "Method name mismatch at index " + i + " for " + className);
                assertEquals(runtime.methods[i].methodType, generated.methods[i].methodType,
                        "Method type mismatch for " + className + "#" + runtime.methods[i].name);
                assertEquals(runtime.methods[i].flags, generated.methods[i].flags,
                        "Method flags mismatch for " + className + "#" + runtime.methods[i].name);
            }
        }
    }

    /**
     * Verify that the generated ReferenceMatcher actually works for matching.
     */
    @Test
    void generatedMatcherPassesOnCurrentClassLoader() throws Exception {
        FakeMuzzleModule module = new FakeMuzzleModule();
        ClassLoader cl = getClass().getClassLoader();

        String moduleInternalName = module.getClass().getName().replace('.', '/');
        byte[] muzzleBytes = MuzzleGenerator.generateMuzzleClass(module, moduleInternalName, cl);

        String muzzleClassName = module.getClass().getName() + "$Muzzle";
        ByteArrayClassLoader loader = new ByteArrayClassLoader(muzzleClassName, muzzleBytes, cl);
        Class<?> muzzleClass = loader.loadClass(muzzleClassName);
        ReferenceMatcher matcher = (ReferenceMatcher) muzzleClass.getMethod("create").invoke(null);

        // The generated references should all match against our current classloader
        // because FakeLibraryClass, FakeResult, etc. are all on the test classpath
        assertTrue(matcher.matches(cl),
                "Generated references should match on current classloader");
    }

    /**
     * Verify that the generated ReferenceMatcher correctly fails against an empty classloader.
     */
    @Test
    void generatedMatcherFailsOnEmptyClassLoader() throws Exception {
        FakeMuzzleModule module = new FakeMuzzleModule();
        ClassLoader cl = getClass().getClassLoader();

        String moduleInternalName = module.getClass().getName().replace('.', '/');
        byte[] muzzleBytes = MuzzleGenerator.generateMuzzleClass(module, moduleInternalName, cl);

        String muzzleClassName = module.getClass().getName() + "$Muzzle";
        ByteArrayClassLoader loader = new ByteArrayClassLoader(muzzleClassName, muzzleBytes, cl);
        Class<?> muzzleClass = loader.loadClass(muzzleClassName);
        ReferenceMatcher matcher = (ReferenceMatcher) muzzleClass.getMethod("create").invoke(null);

        // An empty classloader shouldn't have our FakeLibraryClass
        java.net.URLClassLoader empty = new java.net.URLClassLoader(new java.net.URL[0], null);
        assertFalse(matcher.matches(empty),
                "Generated references should NOT match on empty classloader");
    }

    /**
     * Module with no instrumentations should produce an empty ReferenceMatcher.
     */
    @Test
    void emptyModuleProducesEmptyMatcher() throws Exception {
        EmptyMuzzleModule module = new EmptyMuzzleModule();
        ClassLoader cl = getClass().getClassLoader();

        String moduleInternalName = module.getClass().getName().replace('.', '/');
        byte[] muzzleBytes = MuzzleGenerator.generateMuzzleClass(module, moduleInternalName, cl);

        String muzzleClassName = module.getClass().getName() + "$Muzzle";
        ByteArrayClassLoader loader = new ByteArrayClassLoader(muzzleClassName, muzzleBytes, cl);
        Class<?> muzzleClass = loader.loadClass(muzzleClassName);
        ReferenceMatcher matcher = (ReferenceMatcher) muzzleClass.getMethod("create").invoke(null);

        assertEquals(0, matcher.getReferences().length);
        assertTrue(matcher.matches(cl));
    }

    /**
     * Helper class references should be filtered out from generated references.
     */
    @Test
    void helperClassesAreFilteredOut() throws Exception {
        FakeMuzzleModuleWithHelper module = new FakeMuzzleModuleWithHelper();
        ClassLoader cl = getClass().getClassLoader();

        Reference[] refs = MuzzleGenerator.collectReferences(module, cl);

        // FakeHelper is declared as a helper class, so it should NOT appear in references
        Set<String> refClassNames = Arrays.stream(refs)
                .map(r -> r.className).collect(Collectors.toSet());
        assertFalse(refClassNames.contains(
                        "dev.braintrust.agent.instrumentation.openai.FakeHelper"),
                "Helper classes should be filtered out of references");

        // But FakeLibraryClass (not a helper) should still be present
        assertTrue(refClassNames.contains(
                        "dev.braintrust.agent.instrumentation.openai.FakeLibraryClass"),
                "Non-helper classes should remain in references");
    }

    // --- Test fixtures ---

    /**
     * A module with real advice that references external classes.
     */
    public static class FakeMuzzleModule extends InstrumentationModule {
        public FakeMuzzleModule() {
            super("fake-muzzle");
        }

        @Override
        public List<TypeInstrumentation> typeInstrumentations() {
            return List.of(new TypeInstrumentation() {
                @Override
                public ElementMatcher<TypeDescription> typeMatcher() {
                    return named("anything");
                }

                @Override
                public void transform(TypeTransformer transformer) {
                    transformer.applyAdviceToMethod(
                            named("doSomething"),
                            "dev.braintrust.agent.instrumentation.openai.FakeAdvice");
                }
            });
        }
    }

    /**
     * A module with a helper class declared — FakeHelper should be filtered out of references.
     */
    public static class FakeMuzzleModuleWithHelper extends InstrumentationModule {
        public FakeMuzzleModuleWithHelper() {
            super("fake-muzzle-with-helper");
        }

        @Override
        public List<String> getHelperClassNames() {
            return List.of("dev.braintrust.agent.instrumentation.openai.FakeHelper");
        }

        @Override
        public List<TypeInstrumentation> typeInstrumentations() {
            return List.of(new TypeInstrumentation() {
                @Override
                public ElementMatcher<TypeDescription> typeMatcher() {
                    return named("anything");
                }

                @Override
                public void transform(TypeTransformer transformer) {
                    transformer.applyAdviceToMethod(
                            named("doSomething"),
                            "dev.braintrust.agent.instrumentation.openai.FakeAdvice");
                }
            });
        }
    }

    /** Module with no type instrumentations at all. */
    public static class EmptyMuzzleModule extends InstrumentationModule {
        public EmptyMuzzleModule() {
            super("empty-muzzle");
        }

        @Override
        public List<TypeInstrumentation> typeInstrumentations() {
            return Collections.emptyList();
        }
    }

    /**
     * A classloader that can define a single class from a byte array.
     */
    private static class ByteArrayClassLoader extends ClassLoader {
        private final String className;
        private final byte[] classBytes;

        ByteArrayClassLoader(String className, byte[] classBytes, ClassLoader parent) {
            super(parent);
            this.className = className;
            this.classBytes = classBytes;
        }

        @Override
        protected Class<?> findClass(String name) throws ClassNotFoundException {
            if (name.equals(className)) {
                return defineClass(name, classBytes, 0, classBytes.length);
            }
            throw new ClassNotFoundException(name);
        }
    }
}
