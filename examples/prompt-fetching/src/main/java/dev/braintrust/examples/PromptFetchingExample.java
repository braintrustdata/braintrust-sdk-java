package dev.braintrust.examples;

import static dev.braintrust.instrumentation.openai.BraintrustOpenAI.buildChatCompletionsPrompt;
import static dev.braintrust.instrumentation.openai.BraintrustOpenAI.wrapOpenAI;

import com.openai.client.OpenAIClient;
import com.openai.client.okhttp.OpenAIOkHttpClient;
import dev.braintrust.Braintrust;
import dev.braintrust.prompt.BraintrustPromptLoader.PromptLoadRequest;
import java.util.Map;

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
            var prompt = braintrust.promptLoader().load("kind-greeter-69d2");
            var response =
                    openAIClient
                            .chat()
                            .completions()
                            .create(
                                    buildChatCompletionsPrompt(
                                            prompt, Map.of("name", "Sam Malone")));
            System.out.println("got response: %s".formatted(response.choices().get(0).message()));
        }
        { // more complex prompt options
            var prompt =
                    braintrust
                            .promptLoader()
                            .load(
                                    PromptLoadRequest.builder()
                                            .projectName("andrew-misc")
                                            .promptSlug("unkind-greeter-fd4c")
                                            .version("cbbc711da9f7d445")
                                            .defaults("model", "gpt-3.5-turbo")
                                            .build());
            var response =
                    openAIClient
                            .chat()
                            .completions()
                            .create(
                                    buildChatCompletionsPrompt(
                                            prompt, Map.of("name", "Frasier Crane")));
            System.out.println("got response: %s".formatted(response));
        }

        System.out.println(
                "\n\n  Example complete! View your data in Braintrust: %s\n"
                        .formatted(braintrust.projectUri() + "/logs"));
    }
}
