package dev.braintrust.examples;

import com.openai.client.okhttp.OpenAIOkHttpClient;
import com.openai.models.ChatModel;
import com.openai.models.chat.completions.ChatCompletionCreateParams;
import dev.braintrust.Braintrust;
import dev.braintrust.eval.*;
import dev.braintrust.instrumentation.openai.BraintrustOpenAI;
import dev.braintrust.trace.BrainstoreTrace;
import java.util.List;
import java.util.function.Function;

/**
 * Demonstrates trace scoring: a {@link TracedScorer} that inspects the intermediate LLM spans
 * produced during task execution, rather than only examining the final output.
 *
 * <p>This is useful for evaluating multi-step tasks where you want to score the reasoning process
 * (e.g. checking that the LLM cited sources, stayed on topic, or used the right tool calls) in
 * addition to — or instead of — the final answer.
 */
public class TraceScoringExample {

    public static void main(String[] args) throws Exception {
        var braintrust = Braintrust.get();
        var openTelemetry = braintrust.openTelemetryCreate();
        var openAIClient = BraintrustOpenAI.wrapOpenAI(openTelemetry, OpenAIOkHttpClient.fromEnv());

        // Task: ask the LLM to classify a food item
        var task =
                (Function<String, String>)
                        food -> {
                            var request =
                                    ChatCompletionCreateParams.builder()
                                            .model(ChatModel.GPT_4O_MINI)
                                            .addSystemMessage(
                                                    "Classify the given food as either 'fruit' or"
                                                            + " 'vegetable'. Return only one word.")
                                            .addUserMessage(food)
                                            .maxTokens(10L)
                                            .temperature(0.0)
                                            .build();
                            return openAIClient
                                    .chat()
                                    .completions()
                                    .create(request)
                                    .choices()
                                    .get(0)
                                    .message()
                                    .content()
                                    .orElse("")
                                    .strip()
                                    .toLowerCase();
                        };

        // A TracedScorer that inspects the LLM span to verify a system message was included
        var systemMessageChecker =
                new TracedScorer<String, String>() {
                    @Override
                    public String getName() {
                        return "system_message_present";
                    }

                    @Override
                    public List<Score> score(
                            TaskResult<String, String> taskResult, BrainstoreTrace trace) {
                        // Reconstruct the full conversation thread from LLM spans
                        var thread = trace.getLLMConversationThread();

                        // Check that a system message was included in the conversation
                        boolean hasSystemMessage =
                                thread.stream().anyMatch(msg -> "system".equals(msg.get("role")));

                        System.out.println(
                                "  [trace scorer] conversation thread has "
                                        + thread.size()
                                        + " messages, system message present: "
                                        + hasSystemMessage);

                        return List.of(new Score(getName(), hasSystemMessage ? 1.0 : 0.0));
                    }
                };

        // A TracedScorer that checks the number of LLM calls made during task execution
        var llmCallCounter =
                new TracedScorer<String, String>() {
                    @Override
                    public String getName() {
                        return "single_llm_call";
                    }

                    @Override
                    public List<Score> score(
                            TaskResult<String, String> taskResult, BrainstoreTrace trace) {
                        var llmSpans = trace.getSpans("llm");
                        int callCount = llmSpans.size();

                        System.out.println(
                                "  [trace scorer] LLM calls made: "
                                        + callCount
                                        + " for input: "
                                        + taskResult.datasetCase().input());

                        // Score 1.0 if exactly one LLM call was made, 0.0 otherwise
                        return List.of(new Score(getName(), callCount == 1 ? 1.0 : 0.0));
                    }
                };

        var eval =
                braintrust
                        .<String, String>evalBuilder()
                        .name("trace-scoring-example-" + System.currentTimeMillis())
                        .cases(DatasetCase.of("strawberry", "fruit"))
                        .taskFunction(task)
                        .scorers(
                                // A regular scorer (final output only)
                                Scorer.of(
                                        "exact_match",
                                        (expected, result) -> expected.equals(result) ? 1.0 : 0.0),
                                // Trace-aware scorers (inspect intermediate LLM spans)
                                systemMessageChecker,
                                llmCallCounter)
                        .build();

        var result = eval.run();
        System.out.println("\n\n" + result.createReportString());
    }
}
