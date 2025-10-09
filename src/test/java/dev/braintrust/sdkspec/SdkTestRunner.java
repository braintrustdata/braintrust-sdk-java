package dev.braintrust.sdkspec;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class SdkTestRunner {

    @Test
    public void testSDKSpans() {
        List<String> allTestFiles = new ArrayList<>();
        // to populate all test files: ./sdkspec/test/test-*.yaml
        Set<TestSpec> allTests = new HashSet<>();
        // to populate allTests, for each test file:
        // - each entry in the `tests` array becomes one TestsCase
        // - each TestCase has a unique name. Blow up otherwise

        // SET UP
        // bring up wiremock
    }

    public static class TestRunner {
    }

    public static class TestSpec {
        // basically one entry in the `tests` array in one yaml file
    }
}
