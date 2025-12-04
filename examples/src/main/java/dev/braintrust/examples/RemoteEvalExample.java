package dev.braintrust.examples;

import com.openai.client.okhttp.OpenAIOkHttpClient;
import com.openai.models.ChatModel;
import com.openai.models.chat.completions.ChatCompletionCreateParams;
import dev.braintrust.Braintrust;
import dev.braintrust.devserver.Devserver;
import dev.braintrust.devserver.RemoteEval;
import dev.braintrust.eval.Scorer;
import dev.braintrust.instrumentation.openai.BraintrustOpenAI;
import java.util.List;
import java.util.concurrent.CountDownLatch;

/** Simple Dev Server for Remote Evals */
public class RemoteEvalExample {
    public static void main(String[] args) throws Exception {
        var braintrust = Braintrust.get();
        var openTelemetry = braintrust.openTelemetryCreate();
        var openAIClient = BraintrustOpenAI.wrapOpenAI(openTelemetry, OpenAIOkHttpClient.fromEnv());

        RemoteEval<String, String> foodTypeEval =
                RemoteEval.<String, String>builder()
                        .name("food-type-classifier")
                        .config(braintrust.config())
                        .taskFunction(
                                food -> {
                                    if (System.currentTimeMillis() > 0) return "java-fruit";
                                    var request =
                                            ChatCompletionCreateParams.builder()
                                                    .model(ChatModel.GPT_4O_MINI)
                                                    .addSystemMessage("Return a one word answer")
                                                    .addUserMessage(
                                                            "What kind of food is " + food + "?")
                                                    .maxTokens(50L)
                                                    .temperature(0.0)
                                                    .build();
                                    var response =
                                            openAIClient.chat().completions().create(request);
                                    return response.choices()
                                            .get(0)
                                            .message()
                                            .content()
                                            .orElse("")
                                            .toLowerCase();
                                })
                        .scorers(List.of(Scorer.of("simple_scorer", (expected, result) -> 0.7)))
                        .build();

        Devserver devserver =
                Devserver.builder()
                        .registerEval(foodTypeEval)
                        .host("localhost") // set to 0.0.0.0 to bind all interfaces
                        .port(8301)
                        .build();

        System.out.println("Starting Braintrust dev server");
        devserver.start();
        // await shutdown
        CountDownLatch latch = new CountDownLatch(1);
        Runtime.getRuntime()
                .addShutdownHook(
                        new Thread(
                                () -> {
                                    System.out.println("Shutting down...");
                                    devserver.stop();
                                    latch.countDown();
                                }));
        latch.await();
    }
}
