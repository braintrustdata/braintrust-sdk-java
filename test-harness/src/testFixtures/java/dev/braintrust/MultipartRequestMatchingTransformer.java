package dev.braintrust;

import static com.github.tomakehurst.wiremock.client.WireMock.*;

import com.github.tomakehurst.wiremock.common.FileSource;
import com.github.tomakehurst.wiremock.extension.Parameters;
import com.github.tomakehurst.wiremock.extension.StubMappingTransformer;
import com.github.tomakehurst.wiremock.matching.RequestPatternBuilder;
import com.github.tomakehurst.wiremock.stubbing.ServeEvent;
import com.github.tomakehurst.wiremock.stubbing.StubMapping;
import java.util.List;
import java.util.function.Supplier;

/** Records multipart fields and bytes, rather than the SDK's randomly generated MIME boundary. */
public class MultipartRequestMatchingTransformer extends StubMappingTransformer {
    public static final String NAME = "multipart-request-matching-transformer";

    private final Supplier<List<ServeEvent>> serveEvents;

    public MultipartRequestMatchingTransformer(Supplier<List<ServeEvent>> serveEvents) {
        this.serveEvents = serveEvents;
    }

    @Override
    public String getName() {
        return NAME;
    }

    @Override
    public StubMapping transform(StubMapping stubMapping, FileSource files, Parameters parameters) {
        var pattern = stubMapping.getRequest();
        if (pattern.getHeaders() == null) {
            return stubMapping;
        }
        var contentType =
                pattern.getHeaders().entrySet().stream()
                        .filter(entry -> entry.getKey().equalsIgnoreCase("Content-Type"))
                        .findFirst();
        if (contentType.isEmpty()
                || !contentType
                        .get()
                        .getValue()
                        .getExpected()
                        .regionMatches(
                                true,
                                0,
                                "multipart/form-data",
                                0,
                                "multipart/form-data".length())) {
            return stubMapping;
        }

        // WireMock's automatic multipart body matcher is AnythingPattern. Recover the original
        // parsed parts from the request journal before replacing its boundary-specific header.
        var request =
                serveEvents.get().stream()
                        .map(ServeEvent::getRequest)
                        .filter(candidate -> pattern.match(candidate).isExactMatch())
                        .findFirst()
                        .orElseThrow(
                                () ->
                                        new IllegalStateException(
                                                "Missing recorded multipart request"));
        if (!request.isMultipart()) {
            throw new IllegalStateException("Recorded multipart request has no parsed parts");
        }
        var builder = RequestPatternBuilder.like(pattern);
        builder.withHeader(
                contentType.get().getKey(), matching("(?i)multipart/form-data(?:\\s*;.*)?"));
        for (var part : request.getParts()) {
            var partPattern =
                    aMultipart()
                            .withName(part.getName())
                            .withBody(
                                    part.getFileName() == null
                                            ? equalTo(part.getBody().asString())
                                            : binaryEqualTo(part.getBody().asBytes()));
            for (String name : List.of("Content-Disposition", "Content-Type")) {
                var header = part.getHeader(name);
                if (header.isPresent()) {
                    partPattern.withHeader(name, equalTo(header.firstValue()));
                }
            }
            builder.withAnyRequestBodyPart(partPattern);
        }
        stubMapping.setRequest(builder.build());
        return stubMapping;
    }
}
