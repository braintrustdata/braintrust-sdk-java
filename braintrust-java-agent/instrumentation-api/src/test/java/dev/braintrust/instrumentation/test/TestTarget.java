package dev.braintrust.instrumentation.test;

/** A simple class to be instrumented in tests. */
public class TestTarget {

    public String greet(String name) {
        return "hello " + name;
    }
}
