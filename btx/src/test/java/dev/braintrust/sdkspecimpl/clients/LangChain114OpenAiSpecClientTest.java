package dev.braintrust.sdkspecimpl.clients;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Unit tests for the spec-JSON translation this client does before calling langchain4j. */
class LangChain114OpenAiSpecClientTest {

    @Test
    void readsPlainStringContent() {
        assertEquals("hello", LangChain114OpenAiSpecClient.inputText("hello"));
        assertEquals("", LangChain114OpenAiSpecClient.inputText(null));
    }

    @Test
    void readsResponsesContentPartArrays() {
        // A /v1/responses input item may express its text as content parts; sending the Java map
        // literal ("[{type=input_text, text=hello}]") instead would silently corrupt the request.
        assertEquals(
                "hello",
                LangChain114OpenAiSpecClient.inputText(
                        List.of(Map.of("type", "input_text", "text", "hello"))));
        assertEquals(
                "hello there",
                LangChain114OpenAiSpecClient.inputText(
                        List.of(
                                Map.of("type", "input_text", "text", "hello"),
                                Map.of("type", "input_text", "text", " there"))));
        // Non-text parts (e.g. images) contribute nothing — langchain4j's ChatMessage types here
        // only carry text.
        assertEquals(
                "describe this",
                LangChain114OpenAiSpecClient.inputText(
                        List.of(
                                Map.of("type", "input_text", "text", "describe this"),
                                Map.of("type", "input_image", "image_url", "data:..."))));
    }

    @Test
    void readsBareStringParts() {
        assertEquals("ab", LangChain114OpenAiSpecClient.inputText(List.of("a", "b")));
    }
}
