package dev.braintrust.instrumentation.langchain;

import dev.langchain4j.agent.tool.Tool;

public class TestTools {

    @Tool("Get weather for a location")
    public String getWeather(String location) {
        return String.format(
                "{\"location\":\"%s\",\"temperature\":72,\"condition\":\"sunny\"}", location);
    }

    @Tool("Calculate sum")
    public int calculateSum(int a, int b) {
        return a + b;
    }

    @Tool("Tool that throws exception")
    public String throwError() {
        throw new RuntimeException("Intentional error for testing");
    }
}
