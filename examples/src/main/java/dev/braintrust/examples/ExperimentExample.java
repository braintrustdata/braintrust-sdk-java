package dev.braintrust.examples;

import com.openai.client.okhttp.OpenAIOkHttpClient;
import com.openai.models.ChatModel;
import com.openai.models.chat.completions.ChatCompletionCreateParams;
import dev.braintrust.Braintrust;
import dev.braintrust.eval.EvalCase;
import dev.braintrust.eval.Scorer;
import dev.braintrust.instrumentation.openai.BraintrustOpenAI;
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
                        .name("java-eval-x-" + System.currentTimeMillis()) // NOTE: if you use a
                        // constant, additional runs
                        // will append new cases to
                        // the same experiment
                        .cases(
                                EvalCase.of("strawberry", "fruit"),
                                EvalCase.of("asparagus", "vegetable"),
                                EvalCase.of("apple", "fruit"),
                                EvalCase.of("banana", "fruit"))
                        .task(getFoodType)
                        .scorers(
                                Scorer.of(
                                        "fruit_scorer",
                                        result -> "fruit".equals(result) ? 1.0 : 0.0),
                                Scorer.of(
                                        "vegetable_scorer",
                                        result -> "vegetable".equals(result) ? 1.0 : 0.0))
                        .build();
        var result = eval.run();
        System.out.println("\n\n" + result.createReportString());
    }
}
