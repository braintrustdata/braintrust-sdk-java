package dev.braintrust;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.net.URI;
import org.junit.jupiter.api.Test;

public class BraintrustUtilsTest {
    @Test
    public void testBuildProjectUri() {
        assertEquals(
                URI.create("http://someserver:3009/app/some%20org/p/some%20project"),
                BraintrustUtils.createProjectURI(
                        "http://someserver:3009/", "some org", "some project"));
    }

    @Test
    void testParseParent() {
        var parsed1 = BraintrustUtils.parseParent("experiment_id:abc123");
        assertEquals("experiment_id", parsed1.type());
        assertEquals("abc123", parsed1.id());

        var parsed2 = BraintrustUtils.parseParent("project_name:my-project");
        assertEquals("project_name", parsed2.type());
        assertEquals("my-project", parsed2.id());

        assertThrows(
                Exception.class,
                () -> BraintrustUtils.parseParent("invalid-no-colon"),
                "Should throw on invalid format");
        assertThrows(
                Exception.class,
                () -> BraintrustUtils.parseParent("invalid:too:many:colons"),
                "Should throw on invalid format");
        assertThrows(
                Exception.class,
                () -> BraintrustUtils.parseParent(""),
                "Should throw on empty string");
    }
}
