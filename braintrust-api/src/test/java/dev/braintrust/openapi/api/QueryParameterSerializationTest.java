package dev.braintrust.openapi.api;

import static org.junit.jupiter.api.Assertions.*;

import dev.braintrust.openapi.ApiClient;
import dev.braintrust.openapi.model.Ids;
import dev.braintrust.openapi.model.UserEmail;
import java.net.URI;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

/**
 * Verifies that filters typed as anyOf wrappers reach the wire.
 *
 * <p>These assert against the request URI rather than the wrapper's {@code toUrlQueryString} in
 * isolation, because the request is what callers actually depend on: a filter that fails to
 * serialize produces a successful response over an unfiltered result set, which is worse than an
 * error.
 */
public class QueryParameterSerializationTest {

    private static final UUID ID_A = UUID.fromString("11111111-2222-3333-4444-555555555555");
    private static final UUID ID_B = UUID.fromString("66666666-7777-8888-9999-000000000000");

    /**
     * Captures the URI of the request an api call would send, without sending it. The generated
     * request interceptor runs before dispatch, so the connection failure afterwards is irrelevant.
     */
    private static String capturedUri(java.util.function.Consumer<ApiClient> call) {
        var captured = new AtomicReference<String>();
        var client = new ApiClient();
        client.updateBaseUri("http://localhost:1");
        client.setRequestInterceptor(builder -> captured.set(builder.build().uri().toString()));
        try {
            call.accept(client);
        } catch (RuntimeException expected) {
            // The request never completes; we only care about the URI it was built with.
        }
        assertNotNull(captured.get(), "no request was built");
        return captured.get();
    }

    private static String query(String uri) {
        return URI.create(uri).getQuery();
    }

    @Test
    void idsFilter_singleValue_isSentAsQueryParameter() {
        var uri =
                capturedUri(
                        client ->
                                new ProjectAutomationsApi(client)
                                        .getProjectAutomation(
                                                5,
                                                null,
                                                null,
                                                new Ids(Ids.SchemaType.UUID, ID_A),
                                                null,
                                                null));

        assertEquals("limit=5&ids=" + ID_A, query(uri));
    }

    /** The spec documents repeating the parameter to pass a list of ids. */
    @Test
    void idsFilter_multipleValues_isSentAsRepeatedQueryParameter() {
        var uri =
                capturedUri(
                        client ->
                                new ProjectAutomationsApi(client)
                                        .getProjectAutomation(
                                                null,
                                                null,
                                                null,
                                                new Ids(Ids.SchemaType.List, List.of(ID_A, ID_B)),
                                                null,
                                                null));

        assertEquals("ids=" + ID_A + "&ids=" + ID_B, query(uri));
    }

    /** The same wrapper machinery backs the scalar string filters on other endpoints. */
    @Test
    void emailFilter_isSentAsQueryParameter() {
        var uri =
                capturedUri(
                        client ->
                                new UsersApi(client)
                                        .getUser(
                                                null,
                                                null,
                                                null,
                                                null,
                                                null,
                                                null,
                                                new UserEmail(
                                                        UserEmail.SchemaType.String, "a@b.com"),
                                                null));

        assertEquals("email=a@b.com", query(uri));
    }
}
