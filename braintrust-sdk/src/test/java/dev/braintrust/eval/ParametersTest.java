package dev.braintrust.eval;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.annotation.JsonProperty;
import dev.braintrust.devserver.RemoteEval;
import java.util.*;
import org.junit.jupiter.api.Test;

public class ParametersTest {

    @Test
    void emptyParametersHasNoValues() {
        Parameters params = Parameters.empty();
        assertFalse(params.has("anything"));
        assertTrue(params.isEmpty());
    }

    @Test
    void getReturnsDefaultWhenNoRequestOverride() {
        var params = new Parameters(List.of(ParameterDef.data("model", "gpt-4")), Map.of());
        assertTrue(params.has("model"));
        assertEquals("gpt-4", params.get("model", String.class));
    }

    @Test
    void requestOverridesDefault() {
        var params =
                new Parameters(
                        List.of(ParameterDef.data("model", "gpt-4")), Map.of("model", "gpt-5"));
        assertEquals("gpt-5", params.get("model", String.class));
    }

    @Test
    void throwsWhenParamNotDefined() {
        var params = new Parameters(List.of(ParameterDef.data("model", "gpt-4")), Map.of());
        assertThrows(Exception.class, () -> params.get("temperature", String.class));
    }

    @Test
    void numericValues() {
        var params =
                new Parameters(
                        List.of(
                                ParameterDef.data("temperature", 0.7),
                                ParameterDef.data("max_tokens", 100)),
                        Map.of());

        assertEquals(0.7, params.get("temperature", Double.class));
        assertEquals(100, params.get("max_tokens", Integer.class));
    }

    @Test
    void booleanValues() {
        var params = new Parameters(List.of(ParameterDef.data("verbose", true)), Map.of());
        assertTrue(params.get("verbose", Boolean.class));
    }

    @Test
    void intCoercesToDouble() {
        // JSON deserializes 1 as Integer, but caller wants Double
        var params = new Parameters(List.of(ParameterDef.data("temp", 1)), Map.of());
        assertEquals(1.0, params.get("temp", Double.class));
    }

    @Test
    void intCoercesToFloat() {
        var params = new Parameters(List.of(ParameterDef.data("temp", 1)), Map.of());
        assertEquals(1.0f, params.get("temp", Float.class));
    }

    @Test
    void longCoercesToDouble() {
        var params = new Parameters(List.of(ParameterDef.data("count", 100L)), Map.of());
        assertEquals(100.0, params.get("count", Double.class));
    }

    @Test
    void classCastThrows() {
        var params = new Parameters(List.of(ParameterDef.data("model", "gpt-4")), Map.of());
        assertThrows(ClassCastException.class, () -> params.get("model", Integer.class));
    }

    @Test
    void unknownRequestParamsAreIgnored() {
        var requestParams = new LinkedHashMap<String, Object>();
        requestParams.put("model", "gpt-4");
        requestParams.put("unknown_key", "should be dropped");
        var params = new Parameters(List.of(ParameterDef.data("model", "gpt-4")), requestParams);
        assertTrue(params.has("model"));
        assertFalse(params.has("unknown_key"));
    }

    @Test
    void paramWithNullDefaultAndNoRequestValueIsAbsent() {
        var params =
                new Parameters(
                        List.of(ParameterDef.data("foo", String.class, null, null)), Map.of());
        assertFalse(params.has("foo"));
    }

    @Test
    void listParameterDefInfersArraySchema() {
        var def = ParameterDef.data("tags", List.of("foo", "bar"), "Tag list");
        assertEquals("tags", def.name());
        assertEquals(Map.of("type", "array"), def.schema());
        assertEquals(List.of("foo", "bar"), def.defaultValue());
    }

    @Test
    void emptyIsSingleton() {
        assertSame(Parameters.empty(), Parameters.empty());
    }

    @Test
    void remoteEvalBuilderRejectsDuplicateParameterNames() {
        assertThrows(
                Exception.class,
                () ->
                        RemoteEval.<String, String>builder()
                                .name("test")
                                .taskFunction(input -> "out")
                                .parameter(ParameterDef.model("model", "gpt-4"))
                                .parameter(ParameterDef.data("model", "gpt-5"))
                                .build());
    }

    /** A complex Jackson-serializable POJO for testing OBJECT data type. */
    record ModelConfig(
            @JsonProperty("model_name") String modelName,
            @JsonProperty("max_tokens") int maxTokens,
            @JsonProperty("stop_sequences") List<String> stopSequences) {}

    @Test
    void complexObjectParameterDef() {
        var defaultConfig = new ModelConfig("gpt-4", 1024, List.of("\n", "###"));
        var def = ParameterDef.data("config", defaultConfig, "Model configuration");

        assertEquals("config", def.name());
        assertEquals(ParameterDef.Type.DATA, def.type());
        assertEquals(Map.of("type", "object"), def.schema());
        assertEquals("Model configuration", def.description());
        assertSame(defaultConfig, def.defaultValue());
    }

    @Test
    void complexObjectParameterMergesFromRequest() {
        // Playground would send a JSON object which Jackson deserializes as a Map
        var expectedConfig = new ModelConfig("gpt-5", 2048, List.of("END"));
        var requestJson =
                Map.of("model_name", "gpt-5", "max_tokens", 2048, "stop_sequences", List.of("END"));

        var params =
                new Parameters(
                        List.of(ParameterDef.data("config", ModelConfig.class, null, null)),
                        Map.of("config", requestJson));

        // Request override replaces the default entirely
        assertEquals(expectedConfig, params.get("config", ModelConfig.class));
    }

    @Test
    void dataTypeInferenceCoversAllTypes() {
        assertEquals(Map.of("type", "string"), ParameterDef.data("a", "hello").schema());
        assertEquals(Map.of("type", "number"), ParameterDef.data("b", 3.14).schema());
        assertEquals(Map.of("type", "number"), ParameterDef.data("c", 42).schema());
        assertEquals(Map.of("type", "boolean"), ParameterDef.data("d", true).schema());
        assertEquals(Map.of("type", "array"), ParameterDef.data("e", List.of(1, 2)).schema());
        assertEquals(
                Map.of("type", "object"),
                ParameterDef.data("f", new ModelConfig("x", 1, List.of())).schema());
        assertEquals(Map.of("type", "object"), ParameterDef.data("g", Map.of("k", "v")).schema());
    }

    @Test
    void autoConvertFailsForNonDeserializableType() {
        // A class with no Jackson-friendly constructor or annotations
        class Opaque {
            private final int x;

            Opaque(int x) {
                this.x = x;
            }
        }
        assertThrows(
                Exception.class,
                () ->
                        ParameterDef.data(
                                "val",
                                Opaque.class,
                                new Opaque(33),
                                "a custom object that doesn't serialize"),
                "parameter defs must throw for objects that don't serialize");
        // Also fails fast with null default
        assertThrows(
                Exception.class,
                () ->
                        ParameterDef.data(
                                "val",
                                Opaque.class,
                                null,
                                "a custom object that doesn't serialize"),
                "parameter defs must throw for objects that don't serialize");
    }
}
