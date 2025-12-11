package dev.braintrust.examples;

import com.openai.client.okhttp.OpenAIOkHttpClient;
import com.openai.models.ChatModel;
import com.openai.models.chat.completions.ChatCompletionCreateParams;
import dev.braintrust.Braintrust;
import dev.braintrust.eval.DatasetCase;
import dev.braintrust.eval.Scorer;
import dev.braintrust.instrumentation.openai.BraintrustOpenAI;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

public class ExperimentExample {
    public static void main(String[] args) throws Exception {
        var braintrust = Braintrust.get();
        var openTelemetry = braintrust.openTelemetryCreate();
        var openAIClient = BraintrustOpenAI.wrapOpenAI(openTelemetry, OpenAIOkHttpClient.fromEnv());

        Function<String, String> getFoodType =
                (String food) -> {
                    var request =
                            ChatCompletionCreateParams.builder()
                                    .model(ChatModel.GPT_4O_MINI)
                                    .addSystemMessage("Return a one word answer")
                                    .addUserMessage("What kind of food is " + food + "?")
                                    .maxTokens(50L)
                                    .temperature(0.0)
                                    .build();
                    var response = openAIClient.chat().completions().create(request);
                    return response.choices().get(0).message().content().orElse("").toLowerCase();
                };

        var eval =
                braintrust
                        .<String, String>evalBuilder()
                        // NOTE: pre-existing experiment names will append results
                        .name("java-eval-x-" + System.currentTimeMillis())
                        .cases(
                                DatasetCase.of(
                                        "strawberry",
                                        "fruit",
                                        // custom tags which appear in Braintrust UI
                                        List.of("example"),
                                        // custom metadata passed to scorers
                                        Map.of("calories", 30)),
                                DatasetCase.of("asparagus", "vegetable"),
                                DatasetCase.of("apple", "fruit"),
                                DatasetCase.of("banana", "fruit"))
                        // Or, to fetch a remote dataset:
                        // .dataset(braintrust.fetchDataset("my-dataset-name"))
                        .taskFunction(getFoodType)
                        .scorers(
                                Scorer.of(
                                        "exact_match",
                                        (expected, result) -> expected.equals(result) ? 1.0 : 0.0))
                        .build();
        var result = eval.run();
        System.out.println("\n\n" + result.createReportString());
    }
}
