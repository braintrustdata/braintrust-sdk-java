package dev.braintrust.openapi.model;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.braintrust.openapi.JSON;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * Verifies that saved function references survive a read.
 *
 * <p>A reference identifies a function either by id ({@code {type: function, id, version}}) or by
 * global name ({@code {type: global, name, function_type}}). Assertions are written against a JSON
 * round trip rather than typed getters, so they describe the payload contract rather than the shape
 * of whichever generated class happens to back a variant.
 *
 * <p>These references appear in topic automations ({@code topic_map_functions}, {@code
 * facet_functions}), online scoring rules ({@code scorers}), and prompt {@code tool_functions}.
 */
public class SavedFunctionIdTest {

    private static ObjectMapper mapper;

    @BeforeAll
    static void setUp() {
        mapper = new JSON().getMapper();
    }

    private static void assertRoundTrips(String json, Class<?> type) throws Exception {
        var parsed = mapper.readValue(json, type);
        assertEquals(mapper.readTree(json), mapper.readTree(mapper.writeValueAsString(parsed)));
    }

    @Test
    void functionReference_preservesIdAndVersion() throws Exception {
        assertRoundTrips(
                "{\"type\":\"function\",\"id\":\"36363b1a-b126-41da-9d28-a91a648b0b4c\",\"version\":\"v1\"}",
                SavedFunctionId.class);
    }

    @Test
    void functionReference_preservesIdWhenVersionOmitted() throws Exception {
        assertRoundTrips(
                "{\"type\":\"function\",\"id\":\"36363b1a-b126-41da-9d28-a91a648b0b4c\"}",
                SavedFunctionId.class);
    }

    @Test
    void nullableFunctionReference_preservesId() throws Exception {
        assertRoundTrips(
                "{\"type\":\"function\",\"id\":\"36363b1a-b126-41da-9d28-a91a648b0b4c\"}",
                NullableSavedFunctionId.class);
    }

    @Test
    void globalReference_preservesNameAndFunctionType() throws Exception {
        assertRoundTrips(
                "{\"type\":\"global\",\"name\":\"Task\",\"function_type\":\"scorer\"}",
                SavedFunctionId.class);
    }

    /** A code bundle location references a function positionally rather than by id. */
    @Test
    void codeBundleLocation_preservesFunctionIndex() throws Exception {
        assertRoundTrips("{\"type\":\"function\",\"index\":2}", CodeBundleLocation.class);
    }

    /** An OpenAI tool choice names the function it selects. */
    @Test
    void toolChoice_preservesSelectedFunctionName() throws Exception {
        assertRoundTrips(
                "{\"type\":\"function\",\"function\":{\"name\":\"get_weather\"}}",
                OpenAIModelParamsToolChoice.class);
    }

    @Test
    void toolChoice_preservesStringShorthand() throws Exception {
        assertRoundTrips("\"auto\"", OpenAIModelParamsToolChoice.class);
    }
}
