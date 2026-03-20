package dev.braintrust.instrumentation.springai.v1_0_0;

import static org.junit.jupiter.api.Assertions.*;

import dev.braintrust.TestHarness;
import dev.braintrust.instrumentation.Instrumenter;
import net.bytebuddy.agent.ByteBuddyAgent;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

public class BraintrustSpringAITest {

    @BeforeAll
    public static void beforeAll() {
        var instrumentation = ByteBuddyAgent.install();
        Instrumenter.install(instrumentation, BraintrustSpringAITest.class.getClassLoader());
    }

    private TestHarness testHarness;

    @BeforeEach
    void beforeEach() {
        testHarness = TestHarness.setup();
    }

    @Test
    @Disabled("TODO: implement once the Spring AI intercept strategy is finalised")
    void testSpringAIChatModelCall() {
        // TODO: construct a Spring AI ChatModel pointed at testHarness.openAiBaseUrl(),
        //       make a call, and assert that a span with the expected Braintrust attributes
        //       was exported.
        fail("not yet implemented");
    }
}
