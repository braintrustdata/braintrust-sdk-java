package dev.braintrust.agent;

import dev.braintrust.bootstrap.BraintrustClassLoader;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

class BraintrustAgentTest {
    @Test
    void throwsOnOtherClassLoaders() {
        var classloader = BraintrustAgent.class.getClassLoader();
        assertFalse(classloader instanceof BraintrustClassLoader, "this test is not meaningful if run under a braintrust class loader") ;
        assertThrows(Exception.class, () -> BraintrustAgent.install("doesn't matter", null), "install only allowed on braintrust class loader");
    }
}
