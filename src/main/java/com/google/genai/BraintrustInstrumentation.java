package com.google.genai;

import io.opentelemetry.api.OpenTelemetry;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import lombok.extern.slf4j.Slf4j;

/**
 * Helper class for instrumenting Gemini Client by replacing its internal ApiClient.
 *
 * <p>This class lives in com.google.genai package to access package-private ApiClient class.
 */
@Slf4j
public class BraintrustInstrumentation {
    /**
     * Wraps a Client's internal ApiClient with an instrumented version.
     *
     * @param client the client to instrument
     * @param openTelemetry the OpenTelemetry instance
     * @return the same client instance, but with instrumented ApiClient
     */
    public static Client wrapClient(Client client, OpenTelemetry openTelemetry) throws Exception {
        // Get the apiClient field from Client
        Field clientApiClientField = Client.class.getDeclaredField("apiClient");
        clientApiClientField.setAccessible(true);
        ApiClient originalApiClient = (ApiClient) clientApiClientField.get(client);

        // Create instrumented wrapper
        BraintrustApiClient instrumentedApiClient =
                new BraintrustApiClient(originalApiClient, openTelemetry);

        // Replace apiClient in Client
        setFinalField(client, clientApiClientField, instrumentedApiClient);

        // Replace apiClient in all Client service fields
        replaceApiClientInService(client.models, instrumentedApiClient);
        replaceApiClientInService(client.batches, instrumentedApiClient);
        replaceApiClientInService(client.caches, instrumentedApiClient);
        replaceApiClientInService(client.operations, instrumentedApiClient);
        replaceApiClientInService(client.chats, instrumentedApiClient);
        replaceApiClientInService(client.files, instrumentedApiClient);
        replaceApiClientInService(client.tunings, instrumentedApiClient);

        // Replace apiClient in all Client.async service fields
        if (client.async != null) {
            replaceApiClientInService(client.async.models, instrumentedApiClient);
            replaceApiClientInService(client.async.batches, instrumentedApiClient);
            replaceApiClientInService(client.async.caches, instrumentedApiClient);
            replaceApiClientInService(client.async.operations, instrumentedApiClient);
            replaceApiClientInService(client.async.chats, instrumentedApiClient);
            replaceApiClientInService(client.async.files, instrumentedApiClient);
            replaceApiClientInService(client.async.tunings, instrumentedApiClient);
        }

        log.debug("Successfully instrumented Gemini client");
        return client;
    }

    /** Replaces the apiClient field in a service object (Models, Batches, etc). */
    private static void replaceApiClientInService(Object service, ApiClient instrumentedApiClient)
            throws Exception {
        if (service == null) {
            return;
        }
        try {
            Field apiClientField = service.getClass().getDeclaredField("apiClient");
            apiClientField.setAccessible(true);
            setFinalField(service, apiClientField, instrumentedApiClient);
        } catch (NoSuchFieldException e) {
            // Some services might not have an apiClient field
            log.info("No apiClient field found in " + service.getClass().getSimpleName());
        }
    }

    /**
     * Sets a final field using reflection.
     *
     * <p>This works by making the field accessible and, on older Java versions, removing the final
     * modifier.
     */
    private static void setFinalField(Object target, Field field, Object value) throws Exception {
        field.setAccessible(true);
        // Try to remove final modifier
        try {
            Field modifiersField = Field.class.getDeclaredField("modifiers");
            modifiersField.setAccessible(true);
            modifiersField.setInt(field, field.getModifiers() & ~Modifier.FINAL);
        } catch (NoSuchFieldException e) {
            // ignore
        }
        field.set(target, value);
    }
}
