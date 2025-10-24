package dev.braintrust.eval;

import static org.junit.jupiter.api.Assertions.*;

import dev.braintrust.TestHarness;
import dev.braintrust.api.BraintrustApiClient;
import dev.braintrust.trace.BraintrustTracing;
import io.opentelemetry.api.common.AttributeKey;
import java.util.function.Function;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

public class EvalTest {
    private TestHarness testHarness;

    @BeforeEach
    void beforeEach() {
        testHarness = TestHarness.setup();
    }

    @Test
    public void evalOtelTraceWithProperAttributes() {
        var experimentName = "unit-test-eval";

        var eval =
                testHarness
                        .braintrust()
                        .<String, String>evalBuilder()
                        .name(experimentName)
                        .cases(
                                EvalCase.of("strawberry", "fruit"),
                                EvalCase.of("asparagus", "vegetable"))
                        .task((Function<String, String>) food -> "fruit")
                        .scorers(
                                Scorer.of(
                                        "fruit_scorer",
                                        result -> "fruit".equals(result) ? 1.0 : 0.0),
                                Scorer.of(
                                        "vegetable_scorer",
                                        result -> "vegetable".equals(result) ? 1.0 : 0.0))
                        .build();
        var result = eval.run();
        assertEquals(
                "%s/experiments/%s"
                        .formatted(testHarness.braintrust().projectUri(), experimentName),
                result.getExperimentUrl());
        var spans = testHarness.awaitExportedSpans();
        assertEquals(6, spans.size(), "each eval case should make three spans");
        // TODO: assert each case makes the expected spans
        var experiment =
                testHarness
                        .braintrust()
                        .apiClient()
                        .getOrCreateExperiment(
                                new BraintrustApiClient.CreateExperimentRequest(
                                        TestHarness.defaultProjectId(), experimentName));
        spans.forEach(
                span -> {
                    var parent =
                            span.getAttributes()
                                    .get(AttributeKey.stringKey(BraintrustTracing.PARENT_KEY));
                    assertEquals(
                            "experiment_id:" + experiment.id(),
                            parent,
                            "all eval spans must set the parent to the experiment id");
                });
    }
}
