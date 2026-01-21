package dev.braintrust;

import com.github.tomakehurst.wiremock.common.FileSource;
import com.github.tomakehurst.wiremock.extension.Parameters;
import com.github.tomakehurst.wiremock.extension.StubMappingTransformer;
import com.github.tomakehurst.wiremock.matching.RequestPattern;
import com.github.tomakehurst.wiremock.matching.RequestPatternBuilder;
import com.github.tomakehurst.wiremock.stubbing.StubMapping;

/**
 * WireMock transformer that removes request body patterns from login endpoint recordings. This
 * prevents API keys from being stored in cassette files, since the login endpoint sends the token
 * in the request body.
 */
public class LoginBodyRedactingTransformer extends StubMappingTransformer {

    public static final String NAME = "login-body-redacting-transformer";

    @Override
    public String getName() {
        return NAME;
    }

    @Override
    public StubMapping transform(StubMapping stubMapping, FileSource files, Parameters parameters) {
        RequestPattern request = stubMapping.getRequest();
        String url = request.getUrl();
        if (url != null && url.contains("/api/apikey/login")) {
            // Create a new request pattern without body patterns to avoid storing API keys
            RequestPattern newRequest =
                    new RequestPatternBuilder(request.getMethod(), request.getUrlMatcher()).build();
            stubMapping.setRequest(newRequest);
        }
        return stubMapping;
    }
}
