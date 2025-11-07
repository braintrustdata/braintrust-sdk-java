package dev.braintrust.eval;

import static org.junit.jupiter.api.Assertions.*;

import dev.braintrust.TestHarness;
import dev.braintrust.api.BraintrustApiClient;
import dev.braintrust.trace.BraintrustTracing;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.trace.SpanId;
import io.opentelemetry.sdk.trace.data.SpanData;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import lombok.SneakyThrows;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

public class EvalTest {
    private TestHarness testHarness;

    @BeforeEach
    void beforeEach() {
        testHarness = TestHarness.setup();
    }

    @Test
    @SneakyThrows
    public void evalOtelTraceWithProperAttributes() {
        var experimentName = "unit-test-eval";

        var eval =
                testHarness
                        .braintrust()
                        .<String, String>evalBuilder()
                        .name(experimentName)
                        .cases(
                                DatasetCase.of("strawberry", "fruit"),
                                DatasetCase.of("asparagus", "vegetable"))
                        .taskFunction(food -> "fruit")
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
        var experiment =
                testHarness
                        .braintrust()
                        .apiClient()
                        .getOrCreateExperiment(
                                new BraintrustApiClient.CreateExperimentRequest(
                                        TestHarness.defaultProjectId(), experimentName));
        final AtomicInteger numRootSpans = new AtomicInteger(0);
        for (SpanData span : spans) {
            var parent =
                    span.getAttributes().get(AttributeKey.stringKey(BraintrustTracing.PARENT_KEY));
            assertEquals(
                    "experiment_id:" + experiment.id(),
                    parent,
                    "all eval spans must set the parent to the experiment id");
            if (span.getParentSpanId().equals(SpanId.getInvalid())) {
                numRootSpans.incrementAndGet();
                var inputJson =
                        Eval.JSON_MAPPER.readValue(
                                span.getAttributes()
                                        .get(AttributeKey.stringKey("braintrust.input_json")),
                                Map.class);
                assertNotNull(inputJson.get("input"), "invlaid input: " + inputJson);

                var expected =
                        Eval.JSON_MAPPER.readValue(
                                span.getAttributes()
                                        .get(AttributeKey.stringKey("braintrust.expected")),
                                String.class);
                assertTrue(isFruitOrVegetable(expected), "invalid expected: " + expected);

                var outputJson =
                        Eval.JSON_MAPPER.readValue(
                                span.getAttributes()
                                        .get(AttributeKey.stringKey("braintrust.output_json")),
                                Map.class);
                var output = outputJson.get("output");
                assertNotNull(output, "invlaid output: " + outputJson);
                assertTrue(isFruitOrVegetable(String.valueOf(output)), "invalid output: " + output);
            }
        }
        assertEquals(2, numRootSpans.get(), "each case should make a root span");
        assertEquals(
                numRootSpans.get() * 3, spans.size(), "each eval case should make three spans");
    }

    boolean isFruitOrVegetable(String str) {
        return "vegetable".equals(str) || "fruit".equals(str);
    }
}
