package dev.braintrust.agent.instrumentation.openai;

/**
 * Simulates an external library class that the advice code references.
 * Used as a muzzle test fixture — ReferenceCreator should extract references to this class.
 */
public class FakeLibraryClass {

    /**
     * Non-compile-time-constant field. Using a method call prevents javac from inlining
     * the value, ensuring a GETSTATIC instruction appears in bytecode that references this field.
     */
    public static final String VERSION = String.valueOf("1.0");

    public String doWork(String input) {
        return "done: " + input;
    }

    public static int parse(String data) {
        return data.length();
    }

    public FakeResult createResult() {
        return new FakeResult("ok");
    }
}
