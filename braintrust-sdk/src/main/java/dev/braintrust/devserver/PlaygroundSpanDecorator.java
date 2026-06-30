package dev.braintrust.devserver;

import static dev.braintrust.json.BraintrustJsonMapper.toJson;

import dev.braintrust.eval.Classifier;
import dev.braintrust.eval.DatasetCase;
import dev.braintrust.eval.EvalListener;
import dev.braintrust.eval.EvalRunInfo;
import dev.braintrust.eval.Score;
import dev.braintrust.eval.Scorer;
import dev.braintrust.eval.TaskResult;
import dev.braintrust.trace.BraintrustTracing;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.StatusCode;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javax.annotation.Nullable;

/**
 * Playground variant of the span decorator. Mirrors {@link dev.braintrust.eval.EvalSpanDecorator}
 * but emits the playground attribute shape: a {@code playground_id} parent, a {@code generation}
 * woven into each {@code span_attributes}, a {@code name} on the eval/task span attributes, {@code
 * braintrust.expected_json} (rather than {@code braintrust.expected}), and input/output on the task
 * span.
 *
 * <p>Standalone (does not extend {@code EvalSpanDecorator}) so the two attribute shapes can evolve
 * independently.
 */
final class PlaygroundSpanDecorator implements EvalListener {
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

        Decorator(EvalRunInfo info) {
            this.info = info;
        }

        private String parentValue() {
            return info.parent().toParentValue();
        }

        private Map<String, Object> spanAttrs(String type, String name) {
            var m = new LinkedHashMap<String, Object>();
            m.put("type", type);
            m.put("name", name);
            if (info.generation() != null) {
                m.put("generation", info.generation());
            }
            return m;
        }

        @Override
        public void onRootSpan(Span rootSpan, DatasetCase<?, ?> datasetCase) {
            rootSpan.setAttribute(PARENT, parentValue());
            rootSpan.setAttribute("braintrust.span_attributes", toJson(spanAttrs("eval", "eval")));
            rootSpan.setAttribute(
                    "braintrust.input_json", toJson(Map.of("input", datasetCase.input())));
            rootSpan.setAttribute("braintrust.expected_json", toJson(datasetCase.expected()));
            if (datasetCase.origin().isPresent()) {
                rootSpan.setAttribute("braintrust.origin", toJson(datasetCase.origin().get()));
            }
            if (!datasetCase.tags().isEmpty()) {
                rootSpan.setAttribute(
                        AttributeKey.stringArrayKey("braintrust.tags"), datasetCase.tags());
            }
            if (!datasetCase.metadata().isEmpty()) {
                rootSpan.setAttribute("braintrust.metadata", toJson(datasetCase.metadata()));
            }
        }

        @Override
        public void onTaskSpan(Span taskSpan, DatasetCase<?, ?> datasetCase) {
            taskSpan.setAttribute(PARENT, parentValue());
            taskSpan.setAttribute("braintrust.span_attributes", toJson(spanAttrs("task", "task")));
            taskSpan.setAttribute(
                    "braintrust.input_json", toJson(Map.of("input", datasetCase.input())));
        }

        @Override
        public void onTaskSuccess(Span rootSpan, Span taskSpan, TaskResult<?, ?> taskResult) {
            var output = toJson(Map.of("output", taskResult.result()));
            taskSpan.setAttribute("braintrust.output_json", output);
            rootSpan.setAttribute("braintrust.output_json", output);
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
            var attrs = spanAttrs("score", scorer.getName());
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
            var attrs = spanAttrs("classifier", resolvedName);
            attrs.put("purpose", "scorer");
            classifierSpan.setAttribute("braintrust.span_attributes", toJson(attrs));
        }
    }
}
