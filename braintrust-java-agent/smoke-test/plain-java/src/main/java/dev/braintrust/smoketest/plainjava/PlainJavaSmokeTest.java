package dev.braintrust.smoketest.plainjava;

import com.openai.client.OpenAIClient;
import com.openai.client.okhttp.OpenAIOkHttpClient;
import com.openai.models.ChatModel;
import com.openai.models.chat.completions.ChatCompletionCreateParams;

/**
 * Smoke test that runs with only the Braintrust agent attached — no OTel
 * dependencies on the application classpath.
 */
public class PlainJavaSmokeTest {
    public static void main(String[] args) throws Exception {
        System.out.println("[smoke-test] Starting plain-java smoke test");

        OpenAIClient client = OpenAIOkHttpClient.fromEnv();
        assertNotNull(client, "OpenAI client should not be null");
        System.out.println("[smoke-test] OpenAI client created successfully: " + client.getClass().getName());

        // Verify that we can build a request without errors — we won't actually
        // send it since there's no real API key / server.
        var request = ChatCompletionCreateParams.builder()
                .model(ChatModel.GPT_4O_MINI)
                .addUserMessage("Hello")
                .build();
        var response = client.chat().completions().create(request);

        assertNotNull(request, "ChatCompletionCreateParams should not be null");
        System.out.println("[smoke-test] Chat completion request built successfully: " + response);

        System.out.println("=== Smoke test passed ===");
    }

    private static void assertNotNull(Object object, String msg) {
        if (object == null) {
            throw new RuntimeException(msg);
        }
    }
}
