package dev.braintrust.openapi.model;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.braintrust.openapi.JSON;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * Tests for the custom anyof_model.mustache template.
 *
 * <p>The upstream template has a bug where anyOf schemas with generic container variants (e.g.
 * List&lt;Foo&gt;, Map&lt;K,V&gt;) produce invalid Java: {@code List<Foo>.class} literals and
 * {@code getList<Foo>()} method names. Our override fixes this by switching to {@code baseType}
 * (the raw erased type) for .class references.
 *
 * <p>Our template also adds:
 *
 * <ul>
 *   <li>A {@code SchemaType} enum per anyOf class for exhaustive switch handling
 *   <li>{@code getVariantType()} returning the enum constant, accurate even for same-erased
 *       variants — set during deserialization, or explicitly via the {@code (SchemaType, Object)}
 *       constructor
 *   <li>Typed getters named after the enum constant (e.g. {@code getStringInstance()}, {@code
 *       getWeightedInstance()}) rather than synthetic {@code getanyOf0Instance()} names
 *   <li>Enum constants named from the spec {@code title} when present (titlecased), falling back to
 *       {@code baseType} otherwise
 * </ul>
 *
 * <p>Test classes:
 *
 * <ul>
 *   <li>{@link AISecretType} — anyOf: [String, List&lt;String&gt;] — no titles, uses baseType names
 *       ({@code String}, {@code List})
 *   <li>{@link ProjectScoreCategories} — anyOf: [List&lt;ProjectScoreCategory&gt;,
 *       Map&lt;String,BigDecimal&gt;, List&lt;String&gt;] — has titles ({@code categorical}, {@code
 *       weighted}, {@code minimum}), exercises title-based naming, the single-constructor fix for
 *       duplicate-erasing types, and accurate {@code getVariantType()} via deserialization and
 *       explicit constructor
 * </ul>
 */
public class AnyOfModelTemplateTest {

    private static ObjectMapper mapper;

    @BeforeAll
    static void setUp() {
        mapper = new JSON().getMapper();
    }

    // ── AISecretType: anyOf [String, List<String>] ────────────────────────────

    @Test
    void aiSecretType_setActualInstance_acceptsString() {
        AISecretType t = new AISecretType();
        assertDoesNotThrow(() -> t.setActualInstance("openai"));
        assertEquals("openai", t.getActualInstance());
    }

    @Test
    void aiSecretType_setActualInstance_acceptsList() {
        AISecretType t = new AISecretType();
        List<String> list = List.of("openai", "anthropic");
        assertDoesNotThrow(() -> t.setActualInstance(list));
        assertEquals(list, t.getActualInstance());
    }

    @Test
    void aiSecretType_setActualInstance_rejectsInvalidType() {
        AISecretType t = new AISecretType();
        assertThrows(RuntimeException.class, () -> t.setActualInstance(42));
    }

    @Test
    void aiSecretType_typedGetter_string() {
        AISecretType t = new AISecretType(AISecretType.SchemaType.String, "anthropic");
        assertEquals("anthropic", t.getStringInstance());
    }

    @Test
    void aiSecretType_typedGetter_list() {
        AISecretType t = new AISecretType(AISecretType.SchemaType.List, List.of("a", "b"));
        List<String> result = t.getListInstance();
        assertEquals(List.of("a", "b"), result);
    }

    @Test
    void aiSecretType_deserialize_fromStringJson() throws Exception {
        AISecretType t = mapper.readValue("\"openai\"", AISecretType.class);
        assertNotNull(t);
        assertInstanceOf(String.class, t.getActualInstance());
        assertEquals("openai", t.getActualInstance());
    }

    @Test
    void aiSecretType_deserialize_fromArrayJson() throws Exception {
        AISecretType t = mapper.readValue("[\"openai\",\"anthropic\"]", AISecretType.class);
        assertNotNull(t);
        assertInstanceOf(List.class, t.getActualInstance());
        @SuppressWarnings("unchecked")
        List<String> list = (List<String>) t.getActualInstance();
        assertEquals(List.of("openai", "anthropic"), list);
    }

    @Test
    void aiSecretType_roundTrip_string() throws Exception {
        AISecretType original = new AISecretType(AISecretType.SchemaType.String, "openai");
        String json = mapper.writeValueAsString(original);
        AISecretType roundTripped = mapper.readValue(json, AISecretType.class);
        assertEquals(original.getActualInstance(), roundTripped.getActualInstance());
        assertEquals(AISecretType.SchemaType.String, roundTripped.getVariantType());
    }

    @Test
    void aiSecretType_getVariantType_viaConstructor_string() {
        // No title in spec → enum constant name comes from baseType
        AISecretType t = new AISecretType(AISecretType.SchemaType.String, "openai");
        assertEquals(AISecretType.SchemaType.String, t.getVariantType());
    }

    @Test
    void aiSecretType_getVariantType_viaConstructor_list() {
        AISecretType t = new AISecretType(AISecretType.SchemaType.List, List.of("openai"));
        assertEquals(AISecretType.SchemaType.List, t.getVariantType());
    }

    @Test
    void aiSecretType_getVariantType_viaDeserialization_string() throws Exception {
        AISecretType t = mapper.readValue("\"openai\"", AISecretType.class);
        assertEquals(AISecretType.SchemaType.String, t.getVariantType());
    }

    @Test
    void aiSecretType_getVariantType_viaDeserialization_list() throws Exception {
        AISecretType t = mapper.readValue("[\"openai\"]", AISecretType.class);
        assertEquals(AISecretType.SchemaType.List, t.getVariantType());
    }

    @Test
    void aiSecretType_getVariantType_throwsIfNotSet() {
        // No-arg constructor + setActualInstance does not set resolvedVariantType
        AISecretType t = new AISecretType();
        t.setActualInstance("openai");
        assertThrows(IllegalStateException.class, t::getVariantType);
    }

    // ── ProjectScoreCategories: anyOf [List<ProjectScoreCategory>, Map<String,BigDecimal>,
    //                                   List<String>] ──────────────────────────

    @Test
    void projectScoreCategories_setActualInstance_acceptsList() {
        ProjectScoreCategories c = new ProjectScoreCategories();
        assertDoesNotThrow(() -> c.setActualInstance(List.of()));
    }

    @Test
    void projectScoreCategories_setActualInstance_acceptsMap() {
        ProjectScoreCategories c = new ProjectScoreCategories();
        assertDoesNotThrow(() -> c.setActualInstance(Map.of("accuracy", new BigDecimal("0.8"))));
    }

    @Test
    void projectScoreCategories_setActualInstance_rejectsInvalidType() {
        ProjectScoreCategories c = new ProjectScoreCategories();
        assertThrows(RuntimeException.class, () -> c.setActualInstance("not-valid"));
    }

    @Test
    void projectScoreCategories_getVariantType_categorical_viaConstructor() {
        ProjectScoreCategories c =
                new ProjectScoreCategories(
                        ProjectScoreCategories.SchemaType.Categorical,
                        List.of(new ProjectScoreCategory()));
        assertEquals(ProjectScoreCategories.SchemaType.Categorical, c.getVariantType());
    }

    @Test
    void projectScoreCategories_getVariantType_weighted_viaConstructor() {
        ProjectScoreCategories c =
                new ProjectScoreCategories(
                        ProjectScoreCategories.SchemaType.Weighted,
                        Map.of("accuracy", new BigDecimal("0.9")));
        assertEquals(ProjectScoreCategories.SchemaType.Weighted, c.getVariantType());
    }

    @Test
    void projectScoreCategories_getVariantType_minimum_viaConstructor() {
        // Explicitly declare the List<String> variant as Minimum — this is the key fix.
        // Without the (SchemaType, Object) constructor, getVariantType() would incorrectly
        // return Categorical for both List variants due to erasure.
        ProjectScoreCategories c =
                new ProjectScoreCategories(
                        ProjectScoreCategories.SchemaType.Minimum, List.of("pass", "fail"));
        assertEquals(ProjectScoreCategories.SchemaType.Minimum, c.getVariantType());
    }

    @Test
    void projectScoreCategories_getVariantType_viaDeserialization_minimum() throws Exception {
        // Uses TypeReference<List<String>> so Jackson fails fast on object elements,
        // correctly distinguishing Minimum (List<String>) from Categorical
        // (List<ProjectScoreCategory>)
        ProjectScoreCategories c =
                mapper.readValue("[\"pass\",\"fail\"]", ProjectScoreCategories.class);
        assertEquals(ProjectScoreCategories.SchemaType.Minimum, c.getVariantType());
    }

    @Test
    void projectScoreCategories_getVariantType_viaDeserialization_categorical() throws Exception {
        ProjectScoreCategories c =
                mapper.readValue(
                        "[{\"name\":\"pass\",\"value\":1.0}]", ProjectScoreCategories.class);
        assertEquals(ProjectScoreCategories.SchemaType.Categorical, c.getVariantType());
    }

    @Test
    void projectScoreCategories_getVariantType_viaDeserialization_weighted() throws Exception {
        ProjectScoreCategories c =
                mapper.readValue("{\"accuracy\":0.8}", ProjectScoreCategories.class);
        assertEquals(ProjectScoreCategories.SchemaType.Weighted, c.getVariantType());
    }

    @Test
    void projectScoreCategories_deserialize_fromObjectJson() throws Exception {
        ProjectScoreCategories c =
                mapper.readValue("{\"accuracy\":0.8,\"recall\":0.6}", ProjectScoreCategories.class);
        assertNotNull(c);
        assertInstanceOf(Map.class, c.getActualInstance());
    }

    @Test
    void projectScoreCategories_roundTrip_map() throws Exception {
        ProjectScoreCategories original =
                new ProjectScoreCategories(
                        ProjectScoreCategories.SchemaType.Weighted,
                        Map.of("accuracy", new BigDecimal("0.9")));
        String json = mapper.writeValueAsString(original);
        ProjectScoreCategories roundTripped = mapper.readValue(json, ProjectScoreCategories.class);
        assertInstanceOf(Map.class, roundTripped.getActualInstance());
        assertEquals(ProjectScoreCategories.SchemaType.Weighted, roundTripped.getVariantType());
    }

    @Test
    void projectScoreCategories_typedGetter_weighted() {
        Map<String, BigDecimal> weights = Map.of("accuracy", new BigDecimal("0.8"));
        ProjectScoreCategories c =
                new ProjectScoreCategories(ProjectScoreCategories.SchemaType.Weighted, weights);
        assertEquals(weights, c.getWeightedInstance());
    }

    @Test
    void projectScoreCategories_schemaType_dataType_field() {
        // Each enum constant carries the full generic type as .dataType
        assertEquals(
                "List<ProjectScoreCategory>",
                ProjectScoreCategories.SchemaType.Categorical.dataType);
        assertEquals(
                "Map<String, BigDecimal>", ProjectScoreCategories.SchemaType.Weighted.dataType);
        assertEquals("List<String>", ProjectScoreCategories.SchemaType.Minimum.dataType);
    }
}
