package dev.braintrust.smoketest.plainjava;

import dev.braintrust.InstrumentationReflection;

/**
 * Smoke test that runs with only the Braintrust agent attached — no OTel dependencies on the
 * application classpath.
 *
 * <p>Run via: {@code ./gradlew :braintrust-java-agent:smoke-test:plain-java:smokeTest}
 *
 * <p>This verifies that the Braintrust agent bootstraps correctly and instruments application
 * classes. The agent's bytecode transformation should change {@link
 * InstrumentationReflection#isInstrumented()} from returning {@code false} to returning {@code
 * true}.
 */
public class PlainJavaSmokeTest {
    public static void main(String[] args) throws Exception {
        System.out.println("[smoke-test] Starting plain-java smoke test");

        boolean instrumented = InstrumentationReflection.isInstrumented();
        System.out.println(
                "[smoke-test] InstrumentationReflection.isInstrumented() = " + instrumented);

        if (!instrumented) {
            throw new RuntimeException(
                    "Expected InstrumentationReflection.isInstrumented() to return true, "
                            + "but got false — the agent did not instrument the class");
        }
        System.out.println("=== Smoke test passed ===");
    }
}
