package dev.braintrust.eval;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

public class ClassifierTest {

    private static <T> TaskResult<String, T> taskResult(String input, T output) {
        return new TaskResult<>(output, DatasetCase.of(input, null));
    }

    @Test
    void singleFactoryReturnsOneClassification() {
        Classifier<String, String> classifier =
                Classifier.single(
                        "category",
                        tr -> new Classification("category", "greeting", "Greeting", null));

        var result = classifier.classify(taskResult("hello", "hello"));
        assertEquals(1, result.size());
        assertEquals("greeting", result.get(0).id());
        assertEquals("Greeting", result.get(0).label());
        assertEquals("category", classifier.getName());
    }

    @Test
    void singleFactoryNullReturnNormalizesToEmptyList() {
        Classifier<String, String> classifier = Classifier.single("maybe", tr -> null);
        assertEquals(List.of(), classifier.classify(taskResult("x", "x")));
    }

    @Test
    void listFactoryReturnsMultipleClassifications() {
        Classifier<String, String> classifier =
                Classifier.of(
                        "sentiment",
                        tr ->
                                List.of(
                                        new Classification(
                                                "sentiment", "positive", "Positive", null),
                                        new Classification(
                                                "sentiment",
                                                "enthusiastic",
                                                "Enthusiastic",
                                                null)));

        var result = classifier.classify(taskResult("great!", "great!"));
        assertEquals(2, result.size());
        assertEquals("positive", result.get(0).id());
        assertEquals("enthusiastic", result.get(1).id());
    }

    @Test
    void listFactoryNullReturnNormalizesToEmptyList() {
        Classifier<String, String> classifier = Classifier.of("maybe", tr -> null);
        assertEquals(List.of(), classifier.classify(taskResult("x", "x")));
    }

    @Test
    void classificationOfHelpers() {
        var c1 = Classification.of("id1");
        assertNull(c1.name());
        assertEquals("id1", c1.id());
        assertNull(c1.label());
        assertNull(c1.metadata());

        var c2 = Classification.of("id2", "Label 2");
        assertEquals("id2", c2.id());
        assertEquals("Label 2", c2.label());

        var c3 = Classification.of("nm", "id3", "Label 3");
        assertEquals("nm", c3.name());
        assertEquals("id3", c3.id());
        assertEquals("Label 3", c3.label());
    }

    @Test
    void classificationWithMetadataIsPreserved() {
        var item =
                new Classification(
                        "category", "greeting", "Greeting", Map.of("source", "unit-test"));
        Classifier<String, String> classifier = Classifier.single("category", tr -> item);

        var result = classifier.classify(taskResult("hi", "hi"));
        assertEquals(1, result.size());
        assertEquals(Map.of("source", "unit-test"), result.get(0).metadata());
    }

    @Test
    void validationThrowsForBlankId() {
        Classifier<String, String> classifier =
                Classifier.single("bad", tr -> new Classification("bad", "", null, null));

        var error =
                assertThrows(
                        IllegalArgumentException.class,
                        () -> classifier.classify(taskResult("x", "x")));
        assertTrue(
                error.getMessage().contains("each classification must be a non-empty object"),
                "expected spec wording, got: " + error.getMessage());
    }

    @Test
    void validationThrowsForNullItemInList() {
        Classifier<String, String> classifier =
                Classifier.of("bad", tr -> java.util.Arrays.asList((Classification) null));

        var error =
                assertThrows(
                        IllegalArgumentException.class,
                        () -> classifier.classify(taskResult("x", "x")));
        assertTrue(
                error.getMessage().contains("each classification must be a non-empty object"),
                "expected spec wording, got: " + error.getMessage());
    }

    @Test
    void getNameReturnsConstructorName() {
        Classifier<String, String> c1 = Classifier.of("foo", tr -> List.of());
        assertEquals("foo", c1.getName());

        Classifier<String, String> c2 = Classifier.single("bar", tr -> null);
        assertEquals("bar", c2.getName());
    }
}
