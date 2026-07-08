package dev.braintrust.devserver;

import static org.junit.jupiter.api.Assertions.*;

import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

public class RemoteEvalTest {

    public record MyInput(String prompt, int temperature) {}

    public record MyOutput(String answer) {}

    @Test
    void builderWithClassesConvertsRawJsonValues() {
        RemoteEval<MyInput, MyOutput> eval =
                RemoteEval.builder(MyInput.class, MyOutput.class)
                        .name("typed-eval")
                        .taskFunction(input -> new MyOutput(input.prompt()))
                        .build();

        Map<String, Object> rawInput = new LinkedHashMap<>();
        rawInput.put("prompt", "hello");
        rawInput.put("temperature", 7);

        MyInput input = eval.getInputConverter().apply(rawInput);
        assertEquals(new MyInput("hello", 7), input);

        MyOutput expected = eval.getOutputConverter().apply(Map.of("answer", "world"));
        assertEquals(new MyOutput("world"), expected);

        // null values pass through as null
        assertNull(eval.getInputConverter().apply(null));
        assertNull(eval.getOutputConverter().apply(null));
    }

    @Test
    void builderWithConvertersUsesSuppliedFunctions() {
        RemoteEval<MyInput, String> eval =
                RemoteEval.<MyInput, String>builder(
                                raw -> {
                                    @SuppressWarnings("unchecked")
                                    var map = (Map<String, Object>) raw;
                                    return new MyInput(
                                            (String) map.get("prompt"),
                                            ((Number) map.get("temperature")).intValue());
                                },
                                raw -> String.valueOf(raw))
                        .name("converter-eval")
                        .taskFunction(input -> input.prompt())
                        .build();

        MyInput input = eval.getInputConverter().apply(Map.of("prompt", "hi", "temperature", 3));
        assertEquals(new MyInput("hi", 3), input);
        assertEquals("42", eval.getOutputConverter().apply(42));
    }

    @Test
    @SuppressWarnings("deprecation")
    void deprecatedBuilderPassesRawValuesThrough() {
        RemoteEval<Object, Object> eval =
                RemoteEval.builder().name("legacy-eval").taskFunction(input -> input).build();

        Map<String, Object> raw = Map.of("prompt", "hello");
        assertSame(raw, eval.getInputConverter().apply(raw));
        assertSame(raw, eval.getOutputConverter().apply(raw));
    }
}
