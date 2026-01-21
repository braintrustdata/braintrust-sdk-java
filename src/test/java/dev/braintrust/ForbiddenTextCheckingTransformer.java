package dev.braintrust;

import com.github.tomakehurst.wiremock.common.FileSource;
import com.github.tomakehurst.wiremock.common.Json;
import com.github.tomakehurst.wiremock.extension.Parameters;
import com.github.tomakehurst.wiremock.extension.StubMappingTransformer;
import com.github.tomakehurst.wiremock.stubbing.StubMapping;
import java.util.List;

/**
 * WireMock transformer that checks stub mappings for forbidden text (e.g., API keys) before they
 * are written to disk. Throws an exception immediately if any forbidden text is found, preventing
 * accidental commit of secrets.
 */
public class ForbiddenTextCheckingTransformer extends StubMappingTransformer {

    public static final String NAME = "forbidden-text-checking-transformer";

    private final List<String> forbiddenTexts;

    public ForbiddenTextCheckingTransformer(List<String> forbiddenTexts) {
        // Filter out null/empty strings
        this.forbiddenTexts =
                forbiddenTexts.stream().filter(s -> s != null && !s.isEmpty()).toList();
    }

    @Override
    public String getName() {
        return NAME;
    }

    @Override
    public StubMapping transform(StubMapping stubMapping, FileSource files, Parameters parameters) {
        if (forbiddenTexts.isEmpty()) {
            return stubMapping;
        }

        // Serialize the stub mapping to JSON to check the full content
        String json = Json.write(stubMapping);

        for (String forbidden : forbiddenTexts) {
            if (json.contains(forbidden)) {
                throw new IllegalStateException(
                        "SECURITY: Recording contains forbidden text (likely an API key). "
                                + "URL: "
                                + stubMapping.getRequest().getUrl()
                                + ". "
                                + "This recording should not be saved. "
                                + "Check that sensitive data is being properly redacted.");
            }
        }

        return stubMapping;
    }
}
