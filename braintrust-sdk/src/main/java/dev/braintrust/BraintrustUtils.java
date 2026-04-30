package dev.braintrust;

import dev.braintrust.api.BraintrustApiClient;
import dev.braintrust.openapi.model.Organization;
import dev.braintrust.openapi.model.Project;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import javax.annotation.Nonnull;

public class BraintrustUtils {
    @Deprecated
    public static URI createProjectURI(
            String appUrl, BraintrustApiClient.OrganizationAndProjectInfo orgAndProject) {
        return createProjectURI(
                appUrl, orgAndProject.orgInfo().name(), orgAndProject.project().name());
    }

    /**
     * construct a URI to link to a specific braintrust project within an org, using generated types
     */
    public static URI createProjectURI(String appUrl, Organization org, Project project) {
        return createProjectURI(appUrl, org.getName(), project.getName());
    }

    /** construct a URI to link to a specific braintrust project within an org by name */
    public static URI createProjectURI(String appUrl, String orgName, String projectName) {
        try {
            var baseURI = new URI(appUrl);
            var path = "/app/%s/p/%s".formatted(orgName, projectName);
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

    public static Parent parseParent(@Nonnull String parentStr) {
        String[] parts = parentStr.split(":");
        if (parts.length != 2) {
            throw new IllegalArgumentException("Invalid parent format: " + parentStr);
        }
        return new Parent(parts[0], parts[1]);
    }

    /** Represents a parsed parent with type and ID. */
    public record Parent(String type, String id) {
        public String toParentValue() {
            return type + ":" + id;
        }
    }

    public static List<String> parseCsv(String csv) {
        if (csv == null || csv.isBlank()) {
            return List.of();
        }

        return Arrays.stream(csv.split("\\s*,\\s*")).toList();
    }

    public static <T> List<T> append(List<T> list, T value) {
        List<T> result = new ArrayList<>(list);
        result.add(value);
        return result;
    }
}
