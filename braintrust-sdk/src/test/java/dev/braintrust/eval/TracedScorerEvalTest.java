package dev.braintrust.eval;

import static org.junit.jupiter.api.Assertions.*;

import dev.braintrust.TestHarness;
import dev.braintrust.trace.BrainstoreTrace;
import io.opentelemetry.api.trace.SpanId;
import io.opentelemetry.sdk.trace.data.SpanData;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import lombok.SneakyThrows;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

public class TracedScorerEvalTest {
    private TestHarness testHarness;

    @BeforeEach
    void beforeEach() {
        testHarness = TestHarness.setup();
    }

    /**
     * Verifies that when a {@link TracedScorer} is in the scorer list, {@code Eval} dispatches to
     * {@link TracedScorer#score(TaskResult, BrainstoreTrace)} instead of {@link
     * Scorer#score(TaskResult)}.
     *
     * <p>The test scorer captures the {@link BrainstoreTrace} it receives but does NOT call {@code
     * getSpans()} — this avoids making a live BTQL HTTP call and keeps the test fast and hermetic.
     */
    @Test
    @SneakyThrows
    void evalDispatchesToTracedScorerWithTrace() {
        var capturedTrace = new AtomicReference<BrainstoreTrace>();
        var capturedTaskResult = new AtomicReference<TaskResult<String, String>>();
        var scoreCallCount = new AtomicReference<>(0);

        var tracedScorer =
                new TracedScorer<String, String>() {
                    @Override
                    public String getName() {
                        return "trace_aware_scorer";
                    }

                    @Override
                    public List<Score> score(
                            TaskResult<String, String> taskResult, BrainstoreTrace trace) {
                        capturedTrace.set(trace);
                        capturedTaskResult.set(taskResult);
                        scoreCallCount.set(scoreCallCount.get() + 1);
                        return List.of(new Score(getName(), 1.0));
                    }
                };

        var eval =
                testHarness
                        .braintrust()
                        .<String, String>evalBuilder()
                        .name("unit-test-eval")
                        .cases(DatasetCase.of("hello", "world"))
                        .taskFunction(input -> "world")
                        .scorers(tracedScorer)
                        .build();

        eval.run();

        // Verify the TracedScorer overload was called (not the plain Scorer overload)
        assertEquals(1, scoreCallCount.get(), "traced scorer should have been called once");
        assertNotNull(
                capturedTrace.get(), "TracedScorer should receive a non-null BrainstoreTrace");
        assertNotNull(capturedTaskResult.get(), "TracedScorer should receive the TaskResult");
        assertEquals("hello", capturedTaskResult.get().datasetCase().input());
        assertEquals("world", capturedTaskResult.get().result());
    }

    /**
     * Verifies that when a {@link TracedScorer} is mixed with a regular {@link Scorer}, both are
     * called correctly: the traced scorer receives a {@link BrainstoreTrace}, the regular scorer
     * does not.
     */
    @Test
    @SneakyThrows
    void evalMixedScorersMaintainCorrectDispatch() {
        var tracedScorerCalled = new AtomicReference<>(false);
        var regularScorerCalled = new AtomicReference<>(false);

        var tracedScorer =
                new TracedScorer<String, String>() {
                    @Override
                    public String getName() {
                        return "traced";
                    }

                    @Override
                    public List<Score> score(
                            TaskResult<String, String> taskResult, BrainstoreTrace trace) {
                        assertNotNull(trace);
                        tracedScorerCalled.set(true);
                        return List.of(new Score(getName(), 1.0));
                    }
                };

        var regularScorer =
                new Scorer<String, String>() {
                    @Override
                    public String getName() {
                        return "regular";
                    }

                    @Override
                    public List<Score> score(TaskResult<String, String> taskResult) {
                        regularScorerCalled.set(true);
                        return List.of(new Score(getName(), 0.5));
                    }
                };

        var eval =
                testHarness
                        .braintrust()
                        .<String, String>evalBuilder()
                        .name("unit-test-eval")
                        .cases(DatasetCase.of("input", "expected"))
                        .taskFunction(input -> "output")
                        .scorers(tracedScorer, regularScorer)
                        .build();

        eval.run();

        assertTrue(tracedScorerCalled.get(), "TracedScorer should have been called");
        assertTrue(regularScorerCalled.get(), "regular Scorer should have been called");
    }

    /**
     * Verifies that a {@link TracedScorer} that throws falls back to {@link
     * Scorer#scoreForScorerException}, just like a regular scorer.
     */
    @Test
    @SneakyThrows
    void evalTracedScorerExceptionFallsBackToScoreForScorerException() {
        var fallbackCalled = new AtomicReference<>(false);

        var brokenTracedScorer =
                new TracedScorer<String, String>() {
                    @Override
                    public String getName() {
                        return "broken_traced";
                    }

                    @Override
                    public List<Score> score(
                            TaskResult<String, String> taskResult, BrainstoreTrace trace) {
                        throw new RuntimeException("traced scorer is broken");
                    }

                    @Override
                    public List<Score> scoreForScorerException(
                            Exception e, TaskResult<String, String> taskResult) {
                        fallbackCalled.set(true);
                        return List.of(new Score(getName(), 0.0));
                    }
                };

        var eval =
                testHarness
                        .braintrust()
                        .<String, String>evalBuilder()
                        .name("unit-test-eval-scorer-error")
                        .cases(DatasetCase.of("input", "expected"))
                        .taskFunction(input -> "output")
                        .scorers(brokenTracedScorer)
                        .build();

        // Should not throw — the broken scorer falls back gracefully
        var result = eval.run();
        assertNotNull(result.getExperimentUrl());
        assertTrue(fallbackCalled.get(), "scoreForScorerException should have been called");

        // Verify the score span has ERROR status and the fallback score of 0.0
        var spans = testHarness.awaitExportedSpans();
        var scoreSpans =
                spans.stream()
                        .filter(
                                s -> {
                                    var attrs =
                                            s.getAttributes()
                                                    .get(
                                                            io.opentelemetry.api.common.AttributeKey
                                                                    .stringKey(
                                                                            "braintrust.span_attributes"));
                                    return attrs != null && attrs.contains("\"type\":\"score\"");
                                })
                        .toList();
        assertEquals(1, scoreSpans.size());
        var scoreSpan = scoreSpans.get(0);
        assertEquals(
                io.opentelemetry.api.trace.StatusCode.ERROR,
                scoreSpan.getStatus().getStatusCode(),
                "broken traced scorer span should be ERROR");
        assertTrue(
                scoreSpan.getStatus().getDescription().contains("traced scorer is broken"),
                "error description should include exception message");
    }

    /**
     * Verifies that a regular {@link Scorer} continues to work correctly when mixed with a {@link
     * TracedScorer} — specifically that the regular scorer's plain {@code score(TaskResult)} is
     * called (not the trace overload).
     */
    @Test
    @SneakyThrows
    void regularScorerUnaffectedByTracedScorerPresence() {
        // This test ensures backward compatibility: existing scorers work fine.
        var regularScoreCalled = new AtomicReference<>(false);

        Scorer<String, String> regularScorer =
                Scorer.of(
                        "exact_match",
                        (String expected, String result) -> {
                            regularScoreCalled.set(true);
                            return expected.equals(result) ? 1.0 : 0.0;
                        });

        var tracedScorer =
                new TracedScorer<String, String>() {
                    @Override
                    public String getName() {
                        return "noop_traced";
                    }

                    @Override
                    public List<Score> score(
                            TaskResult<String, String> taskResult, BrainstoreTrace trace) {
                        return List.of(new Score(getName(), 1.0));
                    }
                };

        var eval =
                testHarness
                        .braintrust()
                        .<String, String>evalBuilder()
                        .name("unit-test-eval")
                        .cases(DatasetCase.of("fruit", "fruit"))
                        .taskFunction(input -> "fruit")
                        .scorers(regularScorer, tracedScorer)
                        .build();

        eval.run();

        assertTrue(regularScoreCalled.get(), "regular scorer should have been called");

        var spans = testHarness.awaitExportedSpans();

        // Should have: 1 root + 1 task + 2 score spans
        var rootSpans =
                spans.stream()
                        .filter(s -> s.getParentSpanId().equals(SpanId.getInvalid()))
                        .toList();
        assertEquals(1, rootSpans.size());

        var scoreSpans = spans.stream().filter(s -> isScoreSpan(s)).toList();
        assertEquals(2, scoreSpans.size(), "one score span per scorer");
    }

    /**
     * Verifies that a {@link TracedScorer} correctly receives the task exception path (no trace)
     * via {@link Scorer#scoreForTaskException} when the task throws.
     */
    @Test
    @SneakyThrows
    void tracedScorerReceivesTaskExceptionFallback() {
        var taskExceptionFallbackCalled = new AtomicReference<>(false);

        var tracedScorer =
                new TracedScorer<String, String>() {
                    @Override
                    public String getName() {
                        return "traced_task_ex_scorer";
                    }

                    @Override
                    public List<Score> score(
                            TaskResult<String, String> taskResult, BrainstoreTrace trace) {
                        fail("score(taskResult, trace) should not be called when task throws");
                        return List.of();
                    }

                    @Override
                    public List<Score> scoreForTaskException(
                            Exception taskException, DatasetCase<String, String> datasetCase) {
                        taskExceptionFallbackCalled.set(true);
                        return List.of(new Score(getName(), 0.0));
                    }
                };

        var eval =
                testHarness
                        .braintrust()
                        .<String, String>evalBuilder()
                        .name("unit-test-eval-task-error")
                        .cases(DatasetCase.of("bad-input", "anything"))
                        .taskFunction(
                                input -> {
                                    throw new RuntimeException("task always fails");
                                })
                        .scorers(tracedScorer)
                        .build();

        var result = eval.run();
        assertNotNull(result.getExperimentUrl());
        assertTrue(
                taskExceptionFallbackCalled.get(),
                "scoreForTaskException should have been called when task throws");
    }

    private static boolean isScoreSpan(SpanData span) {
        var attrs =
                span.getAttributes()
                        .get(
                                io.opentelemetry.api.common.AttributeKey.stringKey(
                                        "braintrust.span_attributes"));
        return attrs != null && attrs.contains("\"type\":\"score\"");
    }
}
