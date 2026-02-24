package dev.braintrust.agent.instrumentation;

import static org.junit.jupiter.api.Assertions.*;

import dev.braintrust.agent.instrumentation.test.TestHelper;
import dev.braintrust.agent.instrumentation.test.TestTarget;
import net.bytebuddy.agent.ByteBuddyAgent;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class InstrumentationInstallerTest {

    @BeforeAll
    static void beforeAll() {
        var inst = ByteBuddyAgent.install();
        InstrumentationInstaller.install(inst, InstrumentationInstallerTest.class.getClassLoader());
        assertEquals(0, TestHelper.CALL_COUNT.get());
    }

    @Test
    void adviceIsAppliedAndHelperIsCalled() {
        int before = TestHelper.CALL_COUNT.get();

        var target = new TestTarget();
        String result = target.greet("world");

        assertEquals("hello world", result, "Original method behavior should be preserved");
        assertEquals(before + 1, TestHelper.CALL_COUNT.get(), "Helper should have been called once");
        assertEquals("world", TestHelper.LAST_ARG.get(), "Helper should have received the argument");
    }

    @Test
    void adviceCountsMultipleCalls() {
        int before = TestHelper.CALL_COUNT.get();

        var target = new TestTarget();
        target.greet("a");
        target.greet("b");
        target.greet("c");

        assertEquals(before + 3, TestHelper.CALL_COUNT.get(), "Helper should have been called three times");
        assertEquals("c", TestHelper.LAST_ARG.get(), "Helper should have the last argument");
    }
}
