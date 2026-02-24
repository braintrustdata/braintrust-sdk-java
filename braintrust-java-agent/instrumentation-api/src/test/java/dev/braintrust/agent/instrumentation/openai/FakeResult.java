package dev.braintrust.agent.instrumentation.openai;

/**
 * A return type used by FakeLibraryClass.createResult().
 * Muzzle should extract a reference to this class from the return type of that method call.
 */
public class FakeResult {

    public final String value;

    public FakeResult(String value) {
        this.value = value;
    }
}
