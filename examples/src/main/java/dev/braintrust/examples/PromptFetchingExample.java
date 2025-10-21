package dev.braintrust.examples;

import static dev.braintrust.instrumentation.openai.BraintrustOpenAI.buildChatCompletionsPrompt;
import static dev.braintrust.instrumentation.openai.BraintrustOpenAI.wrapOpenAI;

import com.openai.client.OpenAIClient;
import com.openai.client.okhttp.OpenAIOkHttpClient;
import dev.braintrust.Braintrust;
import dev.braintrust.prompt.BraintrustPromptLoader.PromptLoadRequest;

public class PromptFetchingExample {
    public static void main(String... args) {
        if (null == System.getenv("OPENAI_API_KEY")) {
            System.err.println(
                    "\nWARNING envar OPEN_AI_API_KEY not found. This example will likely fail.\n");
        }
        var braintrust = Braintrust.get();
        var openTelemetry = braintrust.openTelemetryCreate();

        OpenAIClient openAIClient = wrapOpenAI(openTelemetry, OpenAIOkHttpClient.fromEnv());

        { // simple example
            var prompt = braintrust.promptLoader().load("my-prompt-slug");
            var response =
                    openAIClient.chat().completions().create(buildChatCompletionsPrompt(prompt));
            System.out.println("got response: %s".formatted(response));
        }
        { // more complex prompt options
            var prompt =
                    braintrust
                            .promptLoader()
                            .load(
                                    PromptLoadRequest.builder()
                                            .projectName("my-project")
                                            .promptSlug("my-prompt-slug")
                                            .version("5878bd218351fb8e")
                                            .defaults("model", "gpt-3.5-turbo")
                                            .build());
            var response =
                    openAIClient.chat().completions().create(buildChatCompletionsPrompt(prompt));
            System.out.println("got response: %s".formatted(response));
        }

        System.out.println(
                "\n\n  Example complete! View your data in Braintrust: %s\n"
                        .formatted(braintrust.projectUri() + "/logs"));
    }
}
