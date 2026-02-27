package dev.braintrust.agent.instrumentation.openai;

import static org.junit.jupiter.api.Assertions.*;

import com.openai.client.OpenAIClient;
import com.openai.client.okhttp.OpenAIOkHttpClient;
import com.openai.models.ChatModel;
import com.openai.models.chat.completions.*;
import dev.braintrust.agent.instrumentation.InstrumentationInstaller;
import dev.braintrust.agent.instrumentation.openai.manual.BraintrustOpenAI;
import net.bytebuddy.agent.ByteBuddyAgent;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class OpenAIInstrumentationModuleTest {
    @BeforeAll
    public static void beforeAll() {
        var instrumentation = ByteBuddyAgent.install();
        InstrumentationInstaller.install(instrumentation, OpenAIInstrumentationModuleTest.class.getClassLoader());
    }

    @Test
    void testChatCompletions() {
        assertFalse(BraintrustOpenAI.autoInstrumentationApplied.get());
        OpenAIClient client = OpenAIOkHttpClient.fromEnv();
        assertTrue(BraintrustOpenAI.autoInstrumentationApplied.get());
        if (false) {
            var response = client.chat().completions().create(
                    ChatCompletionCreateParams.builder()
                            .model(ChatModel.GPT_4O_MINI)
                            .addUserMessage("Say hello in exactly one word")
                            .temperature(0.0)
                            .build());

            assertNotNull(response);
            assertTrue(response.choices().get(0).message().content().isPresent());
        }
    }
}
