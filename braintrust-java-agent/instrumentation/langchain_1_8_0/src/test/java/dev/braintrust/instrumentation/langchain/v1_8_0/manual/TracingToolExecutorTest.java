package dev.braintrust.instrumentation.langchain.v1_8_0.manual;

import static org.junit.jupiter.api.Assertions.assertEquals;

import dev.braintrust.json.BraintrustJsonMapper;
import java.util.Map;
import lombok.SneakyThrows;
import org.junit.jupiter.api.Test;

public class TracingToolExecutorTest {
    @Test
    @SneakyThrows
    void typeToolJsonCorrect() {
        assertEquals(
                BraintrustJsonMapper.toJson(Map.of("type", "tool")),
                TracingToolExecutor.TYPE_TOOL_JSON);
    }
}
