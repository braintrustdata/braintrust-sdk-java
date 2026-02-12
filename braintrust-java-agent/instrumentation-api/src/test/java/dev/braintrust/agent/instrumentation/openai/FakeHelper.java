package dev.braintrust.agent.instrumentation.openai;

/**
 * A helper class in the instrumentation package. ReferenceCreator's BFS should follow into this
 * class from FakeAdvice and discover its references to FakeLibraryClass.parse().
 */
public class FakeHelper {

    public static void record(String data) {
        // This calls FakeLibraryClass.parse() — the BFS should discover this transitive reference
        int len = FakeLibraryClass.parse(data);
    }
}
