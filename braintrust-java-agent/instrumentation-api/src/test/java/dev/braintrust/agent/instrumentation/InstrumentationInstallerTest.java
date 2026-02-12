package dev.braintrust.agent.instrumentation;

import static org.junit.jupiter.api.Assertions.*;

import dev.braintrust.agent.instrumentation.test.TestAdvice;
import dev.braintrust.agent.instrumentation.test.TestTarget;
import net.bytebuddy.agent.ByteBuddyAgent;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class InstrumentationInstallerTest {

    @BeforeAll
    static void beforeAll() {
        var inst = ByteBuddyAgent.install();
        InstrumentationInstaller.install(inst, InstrumentationInstallerTest.class.getClassLoader());
        assertEquals(0, TestAdvice.ENTER_COUNT.get());
    }

    @Test
    void adviceIsAppliedToTargetMethod() {
        int before = TestAdvice.ENTER_COUNT.get();

        var target = new TestTarget();
        String result = target.greet("world");

        assertEquals("hello world", result, "Original method behavior should be preserved");
        assertEquals(before + 1, TestAdvice.ENTER_COUNT.get(), "Advice should have been called once");
    }

    @Test
    void adviceCountsMultipleCalls() {
        int before = TestAdvice.ENTER_COUNT.get();

        var target = new TestTarget();
        assertEquals("hello a", target.greet("a"));
        assertEquals("hello b", target.greet("b"));
        assertEquals("hello c", target.greet("c"));

        assertEquals(before + 3, TestAdvice.ENTER_COUNT.get(), "Advice should have been called three times");
    }
}
