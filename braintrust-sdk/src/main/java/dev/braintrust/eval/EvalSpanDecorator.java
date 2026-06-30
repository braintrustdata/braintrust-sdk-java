package dev.braintrust.eval;

import static dev.braintrust.json.BraintrustJsonMapper.toJson;

import dev.braintrust.trace.BraintrustTracing;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.StatusCode;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javax.annotation.Nullable;

/**
 * The standard {@link EvalListener} that decorates the spans created by {@link Eval} with the
 * canonical Braintrust attributes (root {@code eval}, {@code task}, {@code score}, and classifier
 * spans). Attached automatically by {@link Eval.Builder}; can be removed via {@link
 * Eval.Builder#clearListeners()}.
 */
public final class EvalSpanDecorator implements EvalListener {
    private static final AttributeKey<String> PARENT =
            AttributeKey.stringKey(BraintrustTracing.PARENT_KEY);

    @Override
    public RunListener createRunListener(EvalRunInfo info) {
        return new RunListener() {
            @Override
            public CaseListener createCaseListener(DatasetCase<?, ?> datasetCase) {
                return new Decorator(info);
            }
        };
    }

    private static final class Decorator implements CaseListener {
        private final EvalRunInfo info;
        private final Map<String, List<Map<String, Object>>> caseClassifications =
                new LinkedHashMap<>();
        private final Map<String, String> classifierErrors = new LinkedHashMap<>();
        private @Nullable DatasetCase<?, ?> datasetCase;

        Decorator(EvalRunInfo info) {
            this.info = info;
        }

        private String parentValue() {
            return info.parent().toParentValue();
        }

        private Map<String, Object> spanAttrs(String type) {
            var m = new LinkedHashMap<String, Object>();
            m.put("type", type);
            if (info.generation() != null) {
                m.put("generation", info.generation());
            }
            return m;
        }

        @Override
        public void onRootSpan(Span rootSpan, DatasetCase<?, ?> datasetCase) {
            this.datasetCase = datasetCase;
            rootSpan.setAttribute(PARENT, parentValue());
            rootSpan.setAttribute("braintrust.span_attributes", toJson(spanAttrs("eval")));
            rootSpan.setAttribute(
                    "braintrust.input_json", toJson(Map.of("input", datasetCase.input())));
            rootSpan.setAttribute("braintrust.expected", toJson(datasetCase.expected()));
            if (datasetCase.origin().isPresent()) {
                rootSpan.setAttribute("braintrust.origin", toJson(datasetCase.origin().get()));
            }
            if (!datasetCase.tags().isEmpty()) {
                rootSpan.setAttribute(
                        AttributeKey.stringArrayKey("braintrust.tags"), datasetCase.tags());
            }
            if (!datasetCase.metadata().isEmpty()) {
                rootSpan.setAttribute(
                        AttributeKey.stringKey("braintrust.metadata"),
                        toJson(datasetCase.metadata()));
            }
        }

        @Override
        public void onTaskSpan(Span taskSpan, DatasetCase<?, ?> datasetCase) {
            taskSpan.setAttribute(PARENT, parentValue());
            taskSpan.setAttribute("braintrust.span_attributes", toJson(spanAttrs("task")));
        }

        @Override
        public void onTaskSuccess(Span rootSpan, Span taskSpan, TaskResult<?, ?> taskResult) {
            rootSpan.setAttribute(
                    "braintrust.output_json", toJson(Map.of("output", taskResult.result())));
        }

        @Override
        public void onTaskError(
                Span rootSpan, Span taskSpan, DatasetCase<?, ?> datasetCase, Exception error) {
            taskSpan.setStatus(StatusCode.ERROR, error.getMessage());
            taskSpan.recordException(error);
            rootSpan.setStatus(StatusCode.ERROR, error.getMessage());
            rootSpan.setAttribute(
                    "braintrust.output_json", toJson(Collections.singletonMap("output", null)));
        }

        @Override
        public void onScoreSpan(Span scoreSpan, Scorer<?, ?> scorer) {
            scoreSpan.setAttribute(PARENT, parentValue());
        }

        @Override
        public void onScoreResult(
                Span scoreSpan,
                Span rootSpan,
                Scorer<?, ?> scorer,
                List<Score> scores,
                @Nullable Exception scoreException) {
            if (scoreException != null) {
                scoreSpan.setStatus(StatusCode.ERROR, scoreException.getMessage());
                scoreSpan.recordException(scoreException);
            }
            if (scores == null || scores.isEmpty()) {
                return;
            }
            var scorerScores = new LinkedHashMap<String, Double>();
            for (var score : scores) {
                scorerScores.put(score.name(), score.value());
            }
            var attrs = spanAttrs("score");
            attrs.put("name", scorer.getName());
            attrs.put("purpose", "scorer");
            scoreSpan.setAttribute("braintrust.span_attributes", toJson(attrs));
            var scoresJson = toJson(scorerScores);
            scoreSpan.setAttribute("braintrust.output_json", scoresJson);
            scoreSpan.setAttribute("braintrust.scores", scoresJson);
        }

        @Override
        public void onClassifierSpan(
                Span classifierSpan, Classifier<?, ?> classifier, String resolvedName) {
            classifierSpan.setAttribute(PARENT, parentValue());
            var attrs = spanAttrs("classifier");
            attrs.put("name", resolvedName);
            attrs.put("purpose", "scorer");
            classifierSpan.setAttribute("braintrust.span_attributes", toJson(attrs));
        }

        @Override
        public void onClassifierResult(
                Span classifierSpan,
                Span rootSpan,
                Classifier<?, ?> classifier,
                String resolvedName,
                List<Classification> classifications,
                @Nullable Exception classifierException) {
            if (classifierException != null) {
                classifierSpan.setStatus(StatusCode.ERROR, classifierException.getMessage());
                classifierSpan.recordException(classifierException);
                classifierErrors.put(
                        resolvedName,
                        classifierException.getMessage() == null
                                ? classifierException.toString()
                                : classifierException.getMessage());
                return;
            }
            // Group results by resolved item name (item.name, falling back to the classifier name
            // when blank). Same map is logged to the classifier span and merged into the per-case
            // aggregate logged on the root span.
            Map<String, List<Map<String, Object>>> outputByName = new LinkedHashMap<>();
            for (var item : classifications) {
                var itemName = item.name();
                if (itemName == null || itemName.isBlank()) {
                    itemName = resolvedName;
                }
                var itemMap = toClassificationItem(item);
                outputByName.computeIfAbsent(itemName, k -> new ArrayList<>()).add(itemMap);
                caseClassifications.computeIfAbsent(itemName, k -> new ArrayList<>()).add(itemMap);
            }
            classifierSpan.setAttribute("braintrust.output_json", toJson(outputByName));
        }

        @Override
        public void onCaseEnd(Span rootSpan) {
            if (!caseClassifications.isEmpty()) {
                rootSpan.setAttribute("braintrust.classifications", toJson(caseClassifications));
            }
            if (!classifierErrors.isEmpty()) {
                Map<String, Object> mergedMetadata =
                        new LinkedHashMap<>(
                                datasetCase == null ? Map.of() : datasetCase.metadata());
                mergedMetadata.put("classifier_errors", classifierErrors);
                rootSpan.setAttribute(
                        AttributeKey.stringKey("braintrust.metadata"), toJson(mergedMetadata));
            }
        }
    }

    /**
     * Converts a {@link Classification} to the wire-format {@code ClassificationItem}: drops {@code
     * name}, includes {@code label} and {@code metadata} only when present.
     */
    private static Map<String, Object> toClassificationItem(Classification c) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", c.id());
        if (c.label() != null) {
            m.put("label", c.label());
        }
        if (c.metadata() != null) {
            m.put("metadata", c.metadata());
        }
        return m;
    }
}
