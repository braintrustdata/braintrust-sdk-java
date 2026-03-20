package dev.braintrust.instrumentation;

import static org.junit.jupiter.api.Assertions.*;

import dev.braintrust.instrumentation.test.TestHelper;
import dev.braintrust.instrumentation.test.TestTarget;
import java.io.File;
import java.lang.ref.WeakReference;
import java.net.URL;
import java.net.URLClassLoader;
import net.bytebuddy.agent.ByteBuddyAgent;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class InstrumenterTest {

    @BeforeAll
    static void beforeAll() {
        var inst = ByteBuddyAgent.install();
        Instrumenter.install(inst, InstrumenterTest.class.getClassLoader());
        assertEquals(0, TestHelper.CALL_COUNT.get());
    }

    @Test
    void adviceIsAppliedAndHelperIsCalled() {
        int before = TestHelper.CALL_COUNT.get();

        var target = new TestTarget();
        String result = target.greet("world");

        assertEquals("hello world", result, "Original method behavior should be preserved");
        assertEquals(
                before + 1, TestHelper.CALL_COUNT.get(), "Helper should have been called once");
        assertEquals(
                "world", TestHelper.LAST_ARG.get(), "Helper should have received the argument");
    }

    @Test
    void adviceCountsMultipleCalls() {
        int before = TestHelper.CALL_COUNT.get();

        var target = new TestTarget();
        target.greet("a");
        target.greet("b");
        target.greet("c");

        assertEquals(
                before + 3,
                TestHelper.CALL_COUNT.get(),
                "Helper should have been called three times");
        assertEquals("c", TestHelper.LAST_ARG.get(), "Helper should have the last argument");
    }

    /**
     * Verifies that classloaders which have been instrumented can be garbage collected after all
     * strong references are dropped. This ensures the muzzle check cache and helper injector
     * tracking use weak keys and don't cause classloader leaks in app-server environments.
     */
    @Test
    void instrumentedClassLoaderCanBeGarbageCollected() throws Exception {
        // Create a child-first classloader that will load its own copy of TestTarget.
        // Build URLs from java.class.path so it can find the class bytes.
        String[] cpEntries = System.getProperty("java.class.path").split(File.pathSeparator);
        URL[] urls = new URL[cpEntries.length];
        for (int i = 0; i < cpEntries.length; i++) {
            urls[i] = new File(cpEntries[i]).toURI().toURL();
        }
        ClassLoader childFirst =
                new URLClassLoader(urls, getClass().getClassLoader()) {
                    @Override
                    public Class<?> loadClass(String name, boolean resolve)
                            throws ClassNotFoundException {
                        // Load TestTarget in this classloader (child-first) so ByteBuddy
                        // sees a new classloader and runs muzzle + helper injection for it.
                        if (name.startsWith("dev.braintrust.instrumentation.test.")) {
                            Class<?> c = findLoadedClass(name);
                            if (c == null) {
                                c = findClass(name);
                            }
                            if (resolve) {
                                resolveClass(c);
                            }
                            return c;
                        }
                        return super.loadClass(name, resolve);
                    }
                };

        // Load TestTarget through the child classloader and invoke greet() via reflection
        Class<?> targetClass =
                childFirst.loadClass("dev.braintrust.instrumentation.test.TestTarget");
        assertNotSame(
                TestTarget.class,
                targetClass,
                "Should be a different Class instance loaded by the child classloader");
        assertSame(
                childFirst,
                targetClass.getClassLoader(),
                "TestTarget should be defined by the child classloader");

        Object target = targetClass.getDeclaredConstructor().newInstance();
        Object result = targetClass.getMethod("greet", String.class).invoke(target, "gc-test");

        assertEquals("hello gc-test", result, "Original method behavior should be preserved");

        // The helper class gets injected into the child classloader by HelperInjector,
        // so it has its own static CALL_COUNT. Read it via reflection.
        Class<?> helperClass =
                childFirst.loadClass("dev.braintrust.instrumentation.test.TestHelper");
        assertNotSame(TestHelper.class, helperClass);
        Object callCount = helperClass.getField("CALL_COUNT").get(null);
        int count = (int) callCount.getClass().getMethod("get").invoke(callCount);
        assertTrue(
                count >= 1,
                "Advice should have been applied to the class loaded by the child classloader");

        // Now drop all strong references to the classloader and its classes
        WeakReference<ClassLoader> weakRef = new WeakReference<>(childFirst);
        //noinspection UnusedAssignment
        childFirst = null;
        //noinspection UnusedAssignment
        targetClass = null;
        //noinspection UnusedAssignment
        target = null;
        //noinspection UnusedAssignment
        result = null;
        //noinspection UnusedAssignment
        helperClass = null;
        //noinspection UnusedAssignment
        callCount = null;

        // Poll for GC to reclaim the classloader
        for (int i = 0; i < 50 && weakRef.get() != null; i++) {
            System.gc();
            Thread.sleep(100);
        }

        assertNull(
                weakRef.get(),
                "Instrumented classloader should be garbage collected — "
                        + "muzzle cache and helper injector must not hold strong references");
    }
}
