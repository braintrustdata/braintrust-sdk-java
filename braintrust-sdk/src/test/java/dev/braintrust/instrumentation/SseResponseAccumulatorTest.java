package dev.braintrust.instrumentation;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.SneakyThrows;
import org.junit.jupiter.api.Test;

/**
 * Hermetic tests for the generic SSE chunk merge. These assert that streamed fields are
 * reconstructed field-agnostically — in particular that reasoning/thinking is preserved alongside
 * content, and that tool-call deltas merge correctly — without needing a live provider.
 */
class SseResponseAccumulatorTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    @SneakyThrows
    private static JsonNode reconstruct(String... chunks) {
        var acc = new SseResponseAccumulator(JSON);
        for (String chunk : chunks) {
            acc.merge(chunk);
        }
        return JSON.readTree(acc.build());
    }

    @Test
    void concatenatesContentAndPreservesReasoning() {
        // A DeepSeek/OpenRouter-style reasoning stream: reasoning_content arrives before content.
        var response =
                reconstruct(
                        "{\"id\":\"x\",\"model\":\"m\",\"choices\":[{\"index\":0,\"delta\":{\"role\":\"assistant\",\"reasoning_content\":\"Let"
                            + " me\"}}]}",
                        "{\"choices\":[{\"index\":0,\"delta\":{\"reasoning_content\":\""
                                + " think.\"}}]}",
                        "{\"choices\":[{\"index\":0,\"delta\":{\"content\":\"Paris\"}}]}",
                        "{\"choices\":[{\"index\":0,\"delta\":{\"content\":\".\"},\"finish_reason\":\"stop\"}]}",
                        "{\"choices\":[],\"usage\":{\"prompt_tokens\":5,\"completion_tokens\":7,\"total_tokens\":12,\"completion_tokens_details\":{\"reasoning_tokens\":4}}}");

        assertEquals("x", response.get("id").asText());
        assertEquals("m", response.get("model").asText());

        JsonNode choice = response.get("choices").get(0);
        assertEquals(0, choice.get("index").asInt());
        assertEquals("stop", choice.get("finish_reason").asText());

        JsonNode message = choice.get("message");
        assertEquals("assistant", message.get("role").asText());
        assertEquals("Paris.", message.get("content").asText());
        assertEquals(
                "Let me think.",
                message.get("reasoning_content").asText(),
                "reasoning/thinking content must survive reconstruction");

        // usage passes through untouched so the semconv tool can map reasoning_tokens.
        assertEquals(
                4,
                response.get("usage")
                        .get("completion_tokens_details")
                        .get("reasoning_tokens")
                        .asInt());
    }

    @Test
    void mergesToolCallDeltasByIndex() {
        // Tool-call arguments are streamed in fragments and keyed by index; a naive
        // content-only accumulator would drop these entirely.
        var response =
                reconstruct(
                        "{\"choices\":[{\"index\":0,\"delta\":{\"tool_calls\":[{\"index\":0,\"id\":\"call_1\",\"type\":\"function\",\"function\":{\"name\":\"getWeather\",\"arguments\":\"{\\\"loc\\\":\"}}]}}]}",
                        "{\"choices\":[{\"index\":0,\"delta\":{\"tool_calls\":[{\"index\":0,\"function\":{\"arguments\":\"\\\"Paris\\\"}\"}}]}}]}",
                        "{\"choices\":[{\"index\":0,\"delta\":{},\"finish_reason\":\"tool_calls\"}]}");

        JsonNode toolCall = response.get("choices").get(0).get("message").get("tool_calls").get(0);
        assertEquals("call_1", toolCall.get("id").asText());
        assertEquals("getWeather", toolCall.get("function").get("name").asText());
        assertEquals("{\"loc\":\"Paris\"}", toolCall.get("function").get("arguments").asText());
    }

    @Test
    void concatenatesLogprobsAcrossChunks() {
        // logprobs stream one token entry per chunk at the choice level (siblings of "delta"), with
        // no per-element "index". Last-write-wins would keep only the final chunk's token, so the
        // entries must accumulate across chunks.
        var response =
                reconstruct(
                        "{\"choices\":[{\"index\":0,\"delta\":{\"content\":\"Hi\"},\"logprobs\":{\"content\":[{\"token\":\"Hi\",\"logprob\":-0.1}]}}]}",
                        "{\"choices\":[{\"index\":0,\"delta\":{\"content\":\"!\"},\"logprobs\":{\"content\":[{\"token\":\"!\",\"logprob\":-0.2}]},\"finish_reason\":\"stop\"}]}");

        JsonNode choice = response.get("choices").get(0);
        assertEquals("Hi!", choice.get("message").get("content").asText());
        assertEquals("stop", choice.get("finish_reason").asText());

        JsonNode logprobs = choice.get("logprobs").get("content");
        assertEquals(2, logprobs.size(), "logprobs entries from every chunk must be preserved");
        assertEquals("Hi", logprobs.get(0).get("token").asText());
        assertEquals("!", logprobs.get(1).get("token").asText());
    }

    @Test
    void keepsMultipleChoicesSeparate() {
        var response =
                reconstruct(
                        "{\"choices\":[{\"index\":0,\"delta\":{\"content\":\"A\"}},{\"index\":1,\"delta\":{\"content\":\"B\"}}]}",
                        "{\"choices\":[{\"index\":1,\"delta\":{\"content\":\"B2\"}},{\"index\":0,\"delta\":{\"content\":\"A2\"}}]}");

        assertEquals(2, response.get("choices").size());
        assertEquals("AA2", response.get("choices").get(0).get("message").get("content").asText());
        assertEquals("BB2", response.get("choices").get(1).get("message").get("content").asText());
    }

    @Test
    void serializesChoicesInIndexOrderRegardlessOfArrival() {
        // A provider could introduce a higher index before a lower one across chunks. Choices are
        // keyed by index, so accumulation is unaffected, but the serialized array must still come
        // out in index order (not first-seen order) so positional consumers see choices[i] == i.
        var response =
                reconstruct(
                        "{\"choices\":[{\"index\":2,\"delta\":{\"content\":\"C\"}}]}",
                        "{\"choices\":[{\"index\":0,\"delta\":{\"content\":\"A\"}}]}",
                        "{\"choices\":[{\"index\":1,\"delta\":{\"content\":\"B\"}}]}");

        JsonNode choices = response.get("choices");
        assertEquals(3, choices.size());
        for (int i = 0; i < 3; i++) {
            assertEquals(i, choices.get(i).get("index").asInt(), "choices must be in index order");
        }
        assertEquals("A", choices.get(0).get("message").get("content").asText());
        assertEquals("B", choices.get(1).get("message").get("content").asText());
        assertEquals("C", choices.get(2).get("message").get("content").asText());
    }
}
