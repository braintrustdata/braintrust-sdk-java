package dev.braintrust;

import dev.braintrust.api.BraintrustApiClient;
import java.net.URI;
import java.net.URISyntaxException;

public class BraintrustUtils {
    /** construct a URI to link to a specific braintrust project within an org */
    public static URI createProjectURI(
            String appUrl, BraintrustApiClient.OrganizationAndProjectInfo orgAndProject) {
        try {
            var baseURI = new URI(appUrl);
            var path =
                    "/app/%s/p/%s"
                            .formatted(
                                    orgAndProject.orgInfo().name(), orgAndProject.project().name());
            return new URI(
                    baseURI.getScheme(),
                    baseURI.getUserInfo(),
                    baseURI.getHost(),
                    baseURI.getPort(),
                    path,
                    null,
                    null);
        } catch (URISyntaxException e) {
            throw new RuntimeException(e);
        }
    }
}
