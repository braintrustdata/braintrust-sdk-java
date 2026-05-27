package dev.braintrust.examples;

import dev.braintrust.Braintrust;
import dev.braintrust.eval.Classification;
import dev.braintrust.eval.Classifier;
import dev.braintrust.eval.DatasetCase;
import java.util.ArrayList;
import java.util.List;

/**
 * Classifiers categorize and label eval outputs. Unlike scorers (numeric 0-1), classifiers return
 * structured {@link Classification} items with an id, optional label, and optional metadata.
 *
 * <p>Three patterns are shown:
 *
 * <ol>
 *   <li>{@link Classifier#single} for a single-label classifier returning one {@link
 *       Classification}.
 *   <li>{@link Classifier#of} for a multi-label classifier returning a list.
 *   <li>An anonymous {@link Classifier} implementation for reusable classifiers with their own
 *       logic.
 * </ol>
 *
 * <p>Classifiers and scorers run independently — you can use either, or both together.
 */
public class ClassifiersExample {
    public static void main(String[] args) throws Exception {
        var braintrust = Braintrust.get();

        // 1. Single-label classifier.
        Classifier<String, String> intentClassifier =
                Classifier.single(
                        "intent",
                        tr -> {
                            var input = tr.datasetCase().input();
                            String id;
                            if (input.matches("(?i).*thank.*")) {
                                id = "praise";
                            } else if (input.matches("(?i).*(waiting|order|update).*")) {
                                id = "follow_up";
                            } else if (input.matches("(?i).*(password|reset|find).*")) {
                                id = "how_to";
                            } else if (input.matches("(?i).*(damaged|refund).*")) {
                                id = "complaint";
                            } else {
                                id = "other";
                            }
                            return new Classification(
                                    "intent", id, capitalize(id.replace('_', ' ')), null);
                        });

        // 2. Multi-label classifier.
        Classifier<String, String> toneClassifier =
                Classifier.of(
                        "tone",
                        tr -> {
                            var input = tr.datasetCase().input();
                            List<Classification> labels = new ArrayList<>();
                            if (input.matches("(?i).*(immediately|unacceptable|waiting).*")) {
                                labels.add(new Classification("tone", "urgent", "Urgent", null));
                            }
                            if (input.matches("(?i).*(please|thank|just checking).*")) {
                                labels.add(new Classification("tone", "polite", "Polite", null));
                            }
                            if (input.matches("(?i).*(unacceptable|damaged|waiting).*")) {
                                labels.add(
                                        new Classification(
                                                "tone", "frustrated", "Frustrated", null));
                            }
                            if (labels.isEmpty()) {
                                labels.add(new Classification("tone", "neutral", "Neutral", null));
                            }
                            return labels;
                        });

        // 3. Custom Classifier implementation — full control over name and logic.
        Classifier<String, String> qualityClassifier =
                new Classifier<>() {
                    @Override
                    public String getName() {
                        return "response_quality";
                    }

                    @Override
                    public List<Classification> classify(
                            dev.braintrust.eval.TaskResult<String, String> tr) {
                        var output = tr.result();
                        int wordCount = output == null ? 0 : output.trim().split("\\s+").length;
                        String id;
                        if (output == null || output.isBlank()) {
                            id = "no_response";
                        } else if (wordCount < 5) {
                            id = "too_short";
                        } else if (output.matches("(?i).*(immediately|right away|look into).*")) {
                            id = "action_oriented";
                        } else {
                            id = "informational";
                        }
                        return List.of(
                                new Classification(
                                        "response_quality",
                                        id,
                                        capitalize(id.replace('_', ' ')),
                                        java.util.Map.of("word_count", wordCount)));
                    }
                };

        var eval =
                braintrust
                        .<String, String>evalBuilder()
                        .name("classifiers-example-" + System.currentTimeMillis())
                        .cases(
                                DatasetCase.of(
                                        "Hi! I just wanted to say thank you, the product is"
                                                + " amazing!",
                                        null),
                                DatasetCase.of(
                                        "I've been waiting 2 weeks for my order. This is"
                                                + " unacceptable!",
                                        null),
                                DatasetCase.of(
                                        "How do I reset my password? I can't find the option"
                                                + " anywhere.",
                                        null),
                                DatasetCase.of(
                                        "The item arrived damaged. I need a refund immediately.",
                                        null),
                                DatasetCase.of(
                                        "Just checking in — any update on my ticket #4821?", null))
                        .taskFunction(ClassifiersExample::generateResponse)
                        .classifiers(intentClassifier, toneClassifier, qualityClassifier)
                        .build();

        var result = eval.run();
        System.out.println("\n\n" + result.createReportString());
    }

    private static String generateResponse(String message) {
        if (message.matches("(?i).*thank.*")) {
            return "You're welcome! So glad you're enjoying it.";
        }
        if (message.matches("(?i).*(waiting|order).*")) {
            return "I sincerely apologise for the delay. Let me look into this right away.";
        }
        if (message.matches("(?i).*(password|reset).*")) {
            return "To reset your password, go to Settings > Account > Reset Password.";
        }
        if (message.matches("(?i).*(damaged|refund).*")) {
            return "I'm sorry to hear that. I'll process your refund immediately.";
        }
        return "Thanks for reaching out! Let me check on that for you.";
    }

    private static String capitalize(String s) {
        if (s == null || s.isEmpty()) return s;
        return Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }
}
