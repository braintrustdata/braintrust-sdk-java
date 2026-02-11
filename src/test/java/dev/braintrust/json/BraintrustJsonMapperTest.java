package dev.braintrust.json;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.SerializationFeature;
import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class BraintrustJsonMapperTest {

    @AfterEach
    void tearDown() {
        BraintrustJsonMapper.reset();
    }

    @Test
    void toJson_serializesObject() {
        record Person(String name, int age) {}

        String json = BraintrustJsonMapper.toJson(new Person("Alice", 30));

        assertEquals("{\"name\":\"Alice\",\"age\":30}", json);
    }

    @Test
    void fromJson_deserializesObject() {
        record Person(String name, int age) {}

        Person person =
                BraintrustJsonMapper.fromJson("{\"name\":\"Bob\",\"age\":25}", Person.class);

        assertEquals("Bob", person.name());
        assertEquals(25, person.age());
    }

    @Test
    void toJson_handlesJavaTimeModule() {
        var instant = Instant.parse("2024-01-15T10:30:00Z");

        String json = BraintrustJsonMapper.toJson(instant);

        // JavaTimeModule serializes Instant as a number by default
        assertNotNull(json);
        assertFalse(json.contains("Instant")); // Not toString() output
    }

    @Test
    void toJson_handlesJdk8ModuleWithOptional() {
        record TestRecord(String name, Optional<String> nickname) {}

        // Present Optional
        var withNickname = new TestRecord("Alice", Optional.of("Ali"));
        String json1 = BraintrustJsonMapper.toJson(withNickname);
        assertTrue(json1.contains("\"nickname\":\"Ali\""));

        // Empty Optional should be excluded (NON_ABSENT)
        var withoutNickname = new TestRecord("Bob", Optional.empty());
        String json2 = BraintrustJsonMapper.toJson(withoutNickname);
        assertFalse(json2.contains("nickname"));
    }

    @Test
    void toJson_excludesNullValues() {
        record TestRecord(String name, String email) {}

        String json = BraintrustJsonMapper.toJson(new TestRecord("Alice", null));

        assertFalse(json.contains("email"));
        assertTrue(json.contains("\"name\":\"Alice\""));
    }

    @Test
    void fromJson_ignoresUnknownProperties() {
        record TestRecord(String name) {}

        var record =
                BraintrustJsonMapper.fromJson(
                        "{\"name\":\"Alice\",\"unknownField\":123}", TestRecord.class);

        assertEquals("Alice", record.name());
    }

    @Test
    void configure_appliesConfiguration() {
        AtomicBoolean configurerCalled = new AtomicBoolean(false);

        BraintrustJsonMapper.configure(
                mapper -> {
                    configurerCalled.set(true);
                    mapper.enable(SerializationFeature.INDENT_OUTPUT);
                });

        var mapper = BraintrustJsonMapper.get();

        assertTrue(configurerCalled.get());
        assertTrue(mapper.isEnabled(SerializationFeature.INDENT_OUTPUT));
    }

    @Test
    void configure_throwsIfCalledAfterGet() {
        BraintrustJsonMapper.get(); // Initialize

        assertThrows(
                IllegalStateException.class, () -> BraintrustJsonMapper.configure(mapper -> {}));
    }

    @Test
    void configure_allowsMultipleConfigurers() {
        BraintrustJsonMapper.configure(mapper -> mapper.enable(SerializationFeature.INDENT_OUTPUT));

        BraintrustJsonMapper.configure(
                mapper -> mapper.enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS));

        var mapper = BraintrustJsonMapper.get();

        assertTrue(mapper.isEnabled(SerializationFeature.INDENT_OUTPUT));
        assertTrue(mapper.isEnabled(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS));
    }

    @Test
    void roundTrip_objectEqualsAfterSerializationAndDeserialization() {
        record Person(String name, int age, Optional<String> email) {}

        var original = new Person("Alice", 30, Optional.of("alice@example.com"));

        String json = BraintrustJsonMapper.toJson(original);
        Person restored = BraintrustJsonMapper.fromJson(json, Person.class);

        assertEquals(original, restored);
    }

    @Test
    void toJson_usesSnakeCaseNaming() {
        record UserProfile(String firstName, String lastName, int totalScore) {}

        String json = BraintrustJsonMapper.toJson(new UserProfile("Alice", "Smith", 100));

        assertTrue(json.contains("\"first_name\":\"Alice\""));
        assertTrue(json.contains("\"last_name\":\"Smith\""));
        assertTrue(json.contains("\"total_score\":100"));
    }
}
