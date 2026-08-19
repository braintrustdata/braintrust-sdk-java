package dev.braintrust.openapi.model;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.braintrust.openapi.JSON;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * Verifies that every chat message role survives a read.
 *
 * <p>Prompts fetched through {@code PromptsApi} carry these messages, so a role that fails to round
 * trip silently changes the prompt a caller renders.
 */
public class ChatCompletionMessageParamTest {

    private static ObjectMapper mapper;

    @BeforeAll
    static void setUp() {
        mapper = new JSON().getMapper();
    }

    private static void assertRoundTrips(String json) throws Exception {
        var parsed = mapper.readValue(json, ChatCompletionMessageParam.class);
        assertEquals(mapper.readTree(json), mapper.readTree(mapper.writeValueAsString(parsed)));
    }

    @Test
    void functionRole_preservesNameAndContent() throws Exception {
        assertRoundTrips("{\"role\":\"function\",\"name\":\"get_weather\",\"content\":\"sunny\"}");
    }

    @Test
    void systemRole_preservesContent() throws Exception {
        assertRoundTrips("{\"role\":\"system\",\"content\":\"be nice\"}");
    }

    @Test
    void userRole_preservesStringContent() throws Exception {
        assertRoundTrips("{\"role\":\"user\",\"content\":\"hello\"}");
    }

    @Test
    void userRole_preservesContentParts() throws Exception {
        assertRoundTrips(
                "{\"role\":\"user\",\"content\":[{\"type\":\"text\",\"text\":\"hello\"}]}");
    }

    @Test
    void assistantRole_preservesContent() throws Exception {
        assertRoundTrips("{\"role\":\"assistant\",\"content\":\"hi there\"}");
    }

    @Test
    void toolRole_preservesToolCallId() throws Exception {
        assertRoundTrips("{\"role\":\"tool\",\"tool_call_id\":\"call_1\",\"content\":\"result\"}");
    }
}
