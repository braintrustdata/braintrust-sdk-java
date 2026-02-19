package dev.braintrust.eval;

import static dev.braintrust.json.BraintrustJsonMapper.fromJson;
import static dev.braintrust.json.BraintrustJsonMapper.toJson;
import static org.junit.jupiter.api.Assertions.*;

import dev.braintrust.Origin;
import dev.braintrust.TestHarness;
import dev.braintrust.VCR;
import dev.braintrust.api.BraintrustApiClient;
import dev.braintrust.trace.BraintrustTracing;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.trace.SpanId;
import io.opentelemetry.api.trace.StatusCode;
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
                var metadata =
                        span.getAttributes().get(AttributeKey.stringKey("braintrust.metadata"));
                assertNotNull(metadata, "root span should have metadata");

                // Parse metadata JSON and verify values based on which case this is
                @SuppressWarnings("unchecked")
                Map<String, Object> metadataMap = fromJson(metadata, Map.class);
                assertNotNull(metadataMap, "metadata should parse as a map");
                assertTrue(metadataMap.containsKey("calories"), "metadata should contain calories");
                assertTrue(metadataMap.containsKey("season"), "metadata should contain season");

                if (tags.contains("red")) {
                    // strawberry case
                    assertEquals(
                            32, metadataMap.get("calories"), "strawberry should have 32 calories");
                    assertEquals(
                            "summer",
                            metadataMap.get("season"),
                            "strawberry should be summer season");
                } else {
                    // asparagus case
                    assertEquals(
                            20, metadataMap.get("calories"), "asparagus should have 20 calories");
                    assertEquals(
                            "spring",
                            metadataMap.get("season"),
                            "asparagus should be spring season");
                }

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

    @Test
    @SneakyThrows
    void evalWithExperimentTagsAndMetadata() {
        // This test requires real API calls - skip in replay mode
        if (TestHarness.getVcrMode() == VCR.VcrMode.REPLAY) {
            // TODO: need a vcr solution for dynamically created objects
            return;
        }

        var experimentName = "unit-test-eval-experiment-tags-metadata";
        var expectedTags = List.of("java-sdk", "unit-test");
        var expectedMetadata = Map.<String, Object>of("model", "gpt-4o", "version", "1.0");

        var eval =
                testHarness
                        .braintrust()
                        .<String, String>evalBuilder()
                        .name(experimentName)
                        .cases(DatasetCase.of("strawberry", "fruit"))
                        .taskFunction(food -> "fruit")
                        .scorers(Scorer.of("scorer", result -> 1.0))
                        .tags(expectedTags)
                        .metadata(expectedMetadata)
                        .build();

        eval.run();
        testHarness.awaitExportedSpans();

        // Query the experiment from Braintrust API to verify tags and metadata
        var experiments =
                testHarness
                        .braintrust()
                        .apiClient()
                        .listExperiments(TestHarness.defaultProjectId());
        var experiment =
                experiments.stream()
                        .filter(e -> e.name().equals(experimentName))
                        .findFirst()
                        .orElseThrow(() -> new AssertionError("Experiment not found"));

        assertEquals(expectedTags, experiment.tags(), "Experiment should have tags");
        assertEquals(expectedMetadata, experiment.metadata(), "Experiment should have metadata");
    }

    @Test
    @SneakyThrows
    void evalLinksToRemoteDataset() {
        if (TestHarness.getVcrMode() == VCR.VcrMode.REPLAY) {
            return;
        }

        var experimentName = "test-dataset-linking";
        Dataset<String, String> dataset = testHarness.braintrust().fetchDataset("food");

        var eval =
                testHarness
                        .braintrust()
                        .<String, String>evalBuilder()
                        .name(experimentName)
                        .dataset(dataset)
                        .taskFunction(String::toUpperCase)
                        .scorers(
                                Scorer.of(
                                        "exact",
                                        (expected, result) -> expected.equals(result) ? 1.0 : 0.0))
                        .build();
        eval.run();
        testHarness.awaitExportedSpans();

        // Verify the experiment is linked to the dataset
        var experiments =
                testHarness
                        .braintrust()
                        .apiClient()
                        .listExperiments(TestHarness.defaultProjectId());
        var experiment =
                experiments.stream()
                        .filter(e -> e.name().equals(experimentName))
                        .findFirst()
                        .orElseThrow(() -> new AssertionError("Experiment not found"));

        assertTrue(experiment.datasetId().isPresent(), "Experiment should be linked to a dataset");
        assertEquals(dataset.id(), experiment.datasetId().get(), "Dataset ID should match");
        assertTrue(
                experiment.datasetVersion().isPresent(),
                "Experiment should have a dataset version");
    }

    @Test
    @SneakyThrows
    void evalContinuesWhenTaskThrows() {
        var experimentName = "unit-test-eval-task-error";

        var eval =
                testHarness
                        .braintrust()
                        .<String, String>evalBuilder()
                        .name(experimentName)
                        .cases(
                                DatasetCase.of("good-input", "expected"),
                                DatasetCase.of("bad-input", "expected"))
                        .taskFunction(
                                input -> {
                                    if ("bad-input".equals(input)) {
                                        throw new RuntimeException("task failed on bad-input");
                                    }
                                    return "result";
                                })
                        .scorers(
                                Scorer.of(
                                        "exact_match",
                                        (expected, result) -> expected.equals(result) ? 1.0 : 0.0))
                        .build();

        // eval should complete without throwing, even though one case errors
        var result = eval.run();
        assertNotNull(result.getExperimentUrl());

        var spans = testHarness.awaitExportedSpans();

        // Both cases should produce root spans — the error case is not skipped
        var rootSpans =
                spans.stream()
                        .filter(s -> s.getParentSpanId().equals(SpanId.getInvalid()))
                        .toList();
        assertEquals(2, rootSpans.size(), "both cases should produce root spans");

        // Find the errored root span (the one with ERROR status)
        var erroredRootSpan =
                rootSpans.stream()
                        .filter(s -> s.getStatus().getStatusCode() == StatusCode.ERROR)
                        .findFirst()
                        .orElseThrow(() -> new AssertionError("expected an errored root span"));
        assertTrue(
                erroredRootSpan.getStatus().getDescription().contains("task failed on bad-input"),
                "root span error should contain the exception message");

        // The errored root span should have output: null
        var erroredOutputJson =
                fromJson(
                        erroredRootSpan
                                .getAttributes()
                                .get(AttributeKey.stringKey("braintrust.output_json")),
                        Map.class);
        assertNull(erroredOutputJson.get("output"), "errored case output should be null");

        // Find the task span for the errored case (child of errored root, type=task, status=ERROR)
        var erroredTaskSpan =
                spans.stream()
                        .filter(
                                s ->
                                        s.getParentSpanContext()
                                                .getSpanId()
                                                .equals(
                                                        erroredRootSpan
                                                                .getSpanContext()
                                                                .getSpanId()))
                        .filter(s -> s.getStatus().getStatusCode() == StatusCode.ERROR)
                        .filter(
                                s -> {
                                    var attrs =
                                            s.getAttributes()
                                                    .get(
                                                            AttributeKey.stringKey(
                                                                    "braintrust.span_attributes"));
                                    return attrs != null && attrs.contains("\"type\":\"task\"");
                                })
                        .findFirst()
                        .orElseThrow(() -> new AssertionError("expected an errored task span"));
        // Task span should have the exception recorded as an event
        assertFalse(
                erroredTaskSpan.getEvents().isEmpty(), "task span should have exception events");
        assertTrue(
                erroredTaskSpan.getEvents().stream().anyMatch(e -> e.getName().equals("exception")),
                "task span should have an exception event");

        // The errored case should still have score spans (from scoreForTaskException default = 0.0)
        var erroredScoreSpans =
                spans.stream()
                        .filter(
                                s ->
                                        s.getParentSpanContext()
                                                .getSpanId()
                                                .equals(
                                                        erroredRootSpan
                                                                .getSpanContext()
                                                                .getSpanId()))
                        .filter(
                                s -> {
                                    var attrs =
                                            s.getAttributes()
                                                    .get(
                                                            AttributeKey.stringKey(
                                                                    "braintrust.span_attributes"));
                                    return attrs != null && attrs.contains("\"type\":\"score\"");
                                })
                        .toList();
        assertEquals(1, erroredScoreSpans.size(), "errored case should have a score span");
        var fallbackScoresJson =
                fromJson(
                        erroredScoreSpans
                                .get(0)
                                .getAttributes()
                                .get(AttributeKey.stringKey("braintrust.scores")),
                        Map.class);
        assertEquals(
                0.0,
                ((Number) fallbackScoresJson.get("exact_match")).doubleValue(),
                "scoreForTaskException default should produce 0.0");

        // The successful case should have a non-error root span
        var successRootSpan =
                rootSpans.stream()
                        .filter(s -> s.getStatus().getStatusCode() != StatusCode.ERROR)
                        .findFirst()
                        .orElseThrow(() -> new AssertionError("expected a successful root span"));
        var successOutputJson =
                fromJson(
                        successRootSpan
                                .getAttributes()
                                .get(AttributeKey.stringKey("braintrust.output_json")),
                        Map.class);
        assertEquals(
                "result", successOutputJson.get("output"), "successful case should have output");
    }

    @Test
    @SneakyThrows
    void evalContinuesWhenScorerThrows() {
        var experimentName = "unit-test-eval-scorer-error";

        var eval =
                testHarness
                        .braintrust()
                        .<String, String>evalBuilder()
                        .name(experimentName)
                        .cases(DatasetCase.of("strawberry", "fruit"))
                        .taskFunction(food -> "fruit")
                        .scorers(
                                new Scorer<String, String>() {
                                    @Override
                                    public String getName() {
                                        return "broken_scorer";
                                    }

                                    @Override
                                    public List<Score> score(
                                            TaskResult<String, String> taskResult) {
                                        throw new RuntimeException("scorer is broken");
                                    }
                                },
                                Scorer.of(
                                        "working_scorer",
                                        (expected, result) -> expected.equals(result) ? 1.0 : 0.0))
                        .build();

        // eval should complete — the broken scorer falls back to scoreForScorerException
        var result = eval.run();
        assertNotNull(result.getExperimentUrl());

        var spans = testHarness.awaitExportedSpans();

        var rootSpans =
                spans.stream()
                        .filter(s -> s.getParentSpanId().equals(SpanId.getInvalid()))
                        .toList();
        assertEquals(1, rootSpans.size());
        var rootSpan = rootSpans.get(0);

        // Find all score spans under the root
        var scoreSpans =
                spans.stream()
                        .filter(
                                s ->
                                        s.getParentSpanContext()
                                                .getSpanId()
                                                .equals(rootSpan.getSpanContext().getSpanId()))
                        .filter(
                                s -> {
                                    var attrs =
                                            s.getAttributes()
                                                    .get(
                                                            AttributeKey.stringKey(
                                                                    "braintrust.span_attributes"));
                                    return attrs != null && attrs.contains("\"type\":\"score\"");
                                })
                        .toList();
        assertEquals(2, scoreSpans.size(), "both scorers should produce score spans");

        // Find the broken scorer's span — it should have ERROR status
        var brokenScoreSpan =
                scoreSpans.stream()
                        .filter(s -> s.getStatus().getStatusCode() == StatusCode.ERROR)
                        .findFirst()
                        .orElseThrow(() -> new AssertionError("expected an errored score span"));
        assertTrue(
                brokenScoreSpan.getStatus().getDescription().contains("scorer is broken"),
                "score span error should contain the exception message");
        // The exception should be recorded as an event
        assertTrue(
                brokenScoreSpan.getEvents().stream().anyMatch(e -> e.getName().equals("exception")),
                "score span should have an exception event");
        // The broken scorer should still have fallback scores (default 0.0)
        var brokenScoresJson =
                fromJson(
                        brokenScoreSpan
                                .getAttributes()
                                .get(AttributeKey.stringKey("braintrust.scores")),
                        Map.class);
        assertEquals(
                0.0,
                ((Number) brokenScoresJson.get("broken_scorer")).doubleValue(),
                "scoreForScorerException default should produce 0.0");

        // Find the working scorer's span — it should NOT have ERROR status
        var workingScoreSpan =
                scoreSpans.stream()
                        .filter(s -> s.getStatus().getStatusCode() != StatusCode.ERROR)
                        .findFirst()
                        .orElseThrow(() -> new AssertionError("expected a working score span"));
        var workingScoresJson =
                fromJson(
                        workingScoreSpan
                                .getAttributes()
                                .get(AttributeKey.stringKey("braintrust.scores")),
                        Map.class);
        assertEquals(
                1.0,
                ((Number) workingScoresJson.get("working_scorer")).doubleValue(),
                "working scorer should produce 1.0 (exact match)");
    }
}
