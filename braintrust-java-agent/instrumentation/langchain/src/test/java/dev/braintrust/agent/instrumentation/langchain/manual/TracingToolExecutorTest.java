package dev.braintrust.agent.instrumentation.langchain.manual;

import dev.braintrust.json.BraintrustJsonMapper;
import lombok.SneakyThrows;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

public class TracingToolExecutorTest {
    @Test
    @SneakyThrows
    void typeToolJsonCorrect() {
        assertEquals(
                BraintrustJsonMapper.toJson(Map.of("type", "tool")),
                TracingToolExecutor.TYPE_TOOL_JSON);
    }
}
