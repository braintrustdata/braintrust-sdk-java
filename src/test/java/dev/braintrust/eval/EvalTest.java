package dev.braintrust.eval;

import static dev.braintrust.json.BraintrustJsonMapper.fromJson;
import static dev.braintrust.json.BraintrustJsonMapper.toJson;
import static org.junit.jupiter.api.Assertions.*;

import dev.braintrust.Origin;
import dev.braintrust.TestHarness;
import dev.braintrust.api.BraintrustApiClient;
import dev.braintrust.trace.BraintrustTracing;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.trace.SpanId;
import io.opentelemetry.sdk.trace.data.SpanData;
import java.util.List;
import java.util.Map;
import java.util.Optional;
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
                        fromJson(
                                span.getAttributes()
                                        .get(AttributeKey.stringKey("braintrust.input_json")),
                                Map.class);
                assertNotNull(inputJson.get("input"), "invlaid input: " + inputJson);

                var expected =
                        fromJson(
                                span.getAttributes()
                                        .get(AttributeKey.stringKey("braintrust.expected")),
                                String.class);
                assertTrue(isFruitOrVegetable(expected), "invalid expected: " + expected);

                var outputJson =
                        fromJson(
                                span.getAttributes()
                                        .get(AttributeKey.stringKey("braintrust.output_json")),
                                Map.class);
                var output = outputJson.get("output");
                assertNotNull(output, "invlaid output: " + outputJson);
                assertTrue(isFruitOrVegetable(String.valueOf(output)), "invalid output: " + output);
            }
        }
        assertEquals(2, numRootSpans.get(), "each case should make a root span");
        // 1 root span + 1 task span + 2 scorer spans (one per scorer)
        assertEquals(
                numRootSpans.get() * 4,
                spans.size(),
                "each eval case should make four spans (one per scorer)");
    }

    boolean isFruitOrVegetable(String str) {
        return "vegetable".equals(str) || "fruit".equals(str);
    }

    @Test
    @SneakyThrows
    public void evalWithTagsAndMetadata() {
        var experimentName = "unit-test-eval-tags-metadata";

        var eval =
                testHarness
                        .braintrust()
                        .<String, String>evalBuilder()
                        .name(experimentName)
                        .cases(
                                DatasetCase.of(
                                        "strawberry",
                                        "fruit",
                                        List.of("red", "sweet"),
                                        Map.of("calories", 32, "season", "summer")),
                                DatasetCase.of(
                                        "asparagus",
                                        "vegetable",
                                        List.of("green", "savory"),
                                        Map.of("calories", 20, "season", "spring")))
                        .taskFunction(food -> "fruit")
                        .scorers(
                                new Scorer<String, String>() {
                                    @Override
                                    public String getName() {
                                        return "fruit_scorer";
                                    }

                                    @Override
                                    public List<Score> score(
                                            TaskResult<String, String> taskResult) {
                                        // Assert metadata is accessible and valid
                                        var metadata = taskResult.datasetCase().metadata();
                                        assertNotNull(
                                                metadata,
                                                "metadata should be accessible in scorer");
                                        assertFalse(
                                                metadata.isEmpty(), "metadata should not be empty");
                                        assertTrue(
                                                metadata.containsKey("calories"),
                                                "metadata should contain calories");
                                        assertTrue(
                                                metadata.containsKey("season"),
                                                "metadata should contain season");

                                        // Verify specific values based on input
                                        var input = taskResult.datasetCase().input();
                                        if ("strawberry".equals(input)) {
                                            assertEquals(
                                                    32,
                                                    metadata.get("calories"),
                                                    "strawberry should have 32 calories");
                                            assertEquals(
                                                    "summer",
                                                    metadata.get("season"),
                                                    "strawberry should be summer season");
                                        } else if ("asparagus".equals(input)) {
                                            assertEquals(
                                                    20,
                                                    metadata.get("calories"),
                                                    "asparagus should have 20 calories");
                                            assertEquals(
                                                    "spring",
                                                    metadata.get("season"),
                                                    "asparagus should be spring season");
                                        }

                                        var score = "fruit".equals(taskResult.result()) ? 1.0 : 0.0;
                                        return List.of(new Score("fruit_scorer", score));
                                    }
                                })
                        .build();
        var result = eval.run();
        assertEquals(
                "%s/experiments/%s"
                        .formatted(testHarness.braintrust().projectUri(), experimentName),
                result.getExperimentUrl());
        var spans = testHarness.awaitExportedSpans();

        final AtomicInteger numRootSpans = new AtomicInteger(0);
        for (SpanData span : spans) {
            if (span.getParentSpanId().equals(SpanId.getInvalid())) {
                // This is a root span - check for tags and metadata
                var tags = span.getAttributes().get(AttributeKey.stringArrayKey("braintrust.tags"));

                assertNotNull(tags, "root span should have tags");

                assertEquals(2, tags.size(), "each case should have 2 tags");
                assertTrue(
                        tags.contains("red") || tags.contains("green"),
                        "tags should contain color");
                assertTrue(
                        tags.contains("sweet") || tags.contains("savory"),
                        "tags should contain taste");
                numRootSpans.incrementAndGet();
            }
        }
        assertEquals(2, numRootSpans.get(), "both cases should have tags and metadata");
    }

    @Test
    @SneakyThrows
    public void evalRootSpanPassesOriginIfPresent() {
        var experimentName = "unit-test-eval-origin";
        var testOrigin = new Origin("unit-test", "1234", "5678", "9", "whatever");

        var eval =
                testHarness
                        .braintrust()
                        .<String, String>evalBuilder()
                        .name(experimentName)
                        .cases(
                                DatasetCase.of("no-origin", "whatever"),
                                new DatasetCase<>(
                                        "has-origin",
                                        "whatever",
                                        List.of(),
                                        Map.of(),
                                        Optional.of(testOrigin)))
                        .taskFunction(food -> "fruit")
                        .scorers(
                                Scorer.of(
                                        "exact_match",
                                        (expected, result) -> expected.equals(result) ? 1.0 : 0.0))
                        .build();
        var result = eval.run();
        assertEquals(
                "%s/experiments/%s"
                        .formatted(testHarness.braintrust().projectUri(), experimentName),
                result.getExperimentUrl());
        var spans = testHarness.awaitExportedSpans();

        final AtomicInteger numRootSpans = new AtomicInteger(0);
        for (SpanData span : spans) {
            if (span.getParentSpanId().equals(SpanId.getInvalid())) {
                // This is a root span - check for origin
                var inputJson =
                        span.getAttributes().get(AttributeKey.stringKey("braintrust.input_json"));
                assertNotNull(inputJson);
                fromJson(inputJson, Map.class);
                var input = (String) (fromJson(inputJson, Map.class)).get("input");
                assertNotNull(input);
                var origin = span.getAttributes().get(AttributeKey.stringKey("braintrust.origin"));
                switch (input) {
                    case "no-origin" -> assertNull(origin);
                    case "has-origin" -> assertEquals(toJson(testOrigin), origin);
                    default -> fail("unexpected input: " + input);
                }
                numRootSpans.incrementAndGet();
            }
        }
        assertEquals(2, numRootSpans.get(), "should test for origin presence and absence");
    }
}
