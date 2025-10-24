package dev.braintrust;

import static org.junit.jupiter.api.Assertions.assertEquals;

import dev.braintrust.api.BraintrustApiClient;
import java.net.URI;
import org.junit.jupiter.api.Test;

public class BraintrustUtilsTest {
    @Test
    public void testBuildProjectUri() {
        var orgAndProject =
                new BraintrustApiClient.OrganizationAndProjectInfo(
                        new BraintrustApiClient.OrganizationInfo("123", "some org"),
                        new BraintrustApiClient.Project(
                                "456", "some project", "123", "doesntmatter", "doesntmatter"));
        assertEquals(
                URI.create("http://someserver:3009/app/some%20org/p/some%20project"),
                BraintrustUtils.createProjectURI("http://someserver:3009/", orgAndProject));
    }
}
