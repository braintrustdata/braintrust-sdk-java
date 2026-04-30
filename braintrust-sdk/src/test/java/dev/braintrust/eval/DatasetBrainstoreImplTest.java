package dev.braintrust.eval;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.junit.jupiter.api.Assertions.*;

import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import dev.braintrust.api.BraintrustOpenApiClient;
import dev.braintrust.config.BraintrustConfig;
import java.util.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

public class DatasetBrainstoreImplTest {

    @RegisterExtension
    static WireMockExtension wireMock =
            WireMockExtension.newInstance().options(wireMockConfig().dynamicPort()).build();

    private BraintrustOpenApiClient apiClient;
    private String datasetId;

    @BeforeEach
    void beforeEach() {
        wireMock.resetAll();
        datasetId = "00000000-0000-0000-0000-000000000123";

        var config =
                BraintrustConfig.builder()
                        .apiKey("test-api-key")
                        .apiUrl("http://localhost:" + wireMock.getPort())
                        .build();
        apiClient = BraintrustOpenApiClient.of(config);
    }

    @Test
    void testFetchAll() {
        // Mock the first batch with a cursor
        wireMock.stubFor(
                post(urlEqualTo("/v1/dataset/" + datasetId + "/fetch"))
                        .withRequestBody(matchingJsonPath("$.cursor", absent()))
                        .willReturn(
                                aResponse()
                                        .withStatus(200)
                                        .withHeader("Content-Type", "application/json")
                                        .withBody(
                                                """
                                {
                                  "events": [
                                    {
                                      "object_type": "dataset",
                                      "dataset_id": "%s",
                                      "id": "123-1",
                                      "created": "2024-01-01T00:00:00Z",
                                      "_xact_id": "1",
                                      "input": "Question 1",
                                      "expected": "Answer 1"
                                    },
                                    {
                                      "object_type": "dataset",
                                      "dataset_id": "%s",
                                      "id": "123-2",
                                      "_xact_id": "1",
                                      "created": "2024-01-01T00:00:00Z",
                                      "input": "Question 2",
                                      "expected": "Answer 2"
                                    }
                                  ],
                                  "cursor": "next-page-token"
                                }
                                """
                                                        .formatted(datasetId, datasetId))));

        // Mock the second batch without a cursor (last page)
        wireMock.stubFor(
                post(urlEqualTo("/v1/dataset/" + datasetId + "/fetch"))
                        .withRequestBody(matchingJsonPath("$.cursor", equalTo("next-page-token")))
                        .willReturn(
                                aResponse()
                                        .withStatus(200)
                                        .withHeader("Content-Type", "application/json")
                                        .withBody(
                                                """
                                {
                                  "events": [
                                    {
                                      "object_type": "dataset",
                                      "dataset_id": "%s",
                                      "id": "123-3",
                                      "_xact_id": "1",
                                      "created": "2024-01-01T00:00:00Z",
                                      "input": "Question 3",
                                      "expected": "Answer 3"
                                    }
                                  ],
                                  "cursor": null
                                }
                                """
                                                        .formatted(datasetId))));

        DatasetBrainstoreImpl<String, String> dataset =
                new DatasetBrainstoreImpl<>(apiClient, datasetId, "test-version", 2);

        List<DatasetCase<String, String>> cases = new ArrayList<>();
        dataset.forEach(cases::add);

        assertEquals(3, cases.size());
        assertEquals("Question 1", cases.get(0).input());
        assertEquals("Answer 1", cases.get(0).expected());
        assertEquals("Question 2", cases.get(1).input());
        assertEquals("Answer 2", cases.get(1).expected());
        assertEquals("Question 3", cases.get(2).input());
        assertEquals("Answer 3", cases.get(2).expected());

        wireMock.verify(2, postRequestedFor(urlEqualTo("/v1/dataset/" + datasetId + "/fetch")));
    }

    @Test
    void testEmptyDataset() {
        wireMock.stubFor(
                post(urlEqualTo("/v1/dataset/" + datasetId + "/fetch"))
                        .willReturn(
                                aResponse()
                                        .withStatus(200)
                                        .withHeader("Content-Type", "application/json")
                                        .withBody(
                                                """
                                {
                                  "events": [],
                                  "cursor": null
                                }
                                """)));

        DatasetBrainstoreImpl<String, String> dataset =
                new DatasetBrainstoreImpl<>(apiClient, datasetId, "test-version");

        List<DatasetCase<String, String>> cases = new ArrayList<>();
        dataset.forEach(cases::add);

        assertEquals(0, cases.size());
        wireMock.verify(1, postRequestedFor(urlEqualTo("/v1/dataset/" + datasetId + "/fetch")));
    }

    @Test
    void testFetchWithPinnedVersion() {
        String projectName = "test-project";
        String datasetName = "test-dataset";
        String pinnedVersion = "12345";
        String datasetUuid = "00000000-0000-0000-0000-000000000789";

        // Mock the dataset lookup
        wireMock.stubFor(
                get(urlPathEqualTo("/v1/dataset"))
                        .withQueryParam("project_name", equalTo(projectName))
                        .withQueryParam("dataset_name", equalTo(datasetName))
                        .willReturn(
                                aResponse()
                                        .withStatus(200)
                                        .withHeader("Content-Type", "application/json")
                                        .withBody(
                                                """
                                {
                                  "objects": [
                                    {
                                      "id": "%s",
                                      "project_id": "00000000-0000-0000-0000-000000000456",
                                      "name": "test-dataset",
                                      "_xact_id": "12345",
                                      "created": "2024-01-01T00:00:00Z"
                                    }
                                  ]
                                }
                                """
                                                        .formatted(datasetUuid))));

        // Mock the fetch endpoint with version
        wireMock.stubFor(
                post(urlEqualTo("/v1/dataset/" + datasetUuid + "/fetch"))
                        .withRequestBody(matchingJsonPath("$.version", equalTo(pinnedVersion)))
                        .willReturn(
                                aResponse()
                                        .withStatus(200)
                                        .withHeader("Content-Type", "application/json")
                                        .withBody(
                                                """
                                {
                                  "events": [
                                    {
                                      "dataset_id": "%s",
                                      "id": "some-row-id",
                                      "input": "test input",
                                      "expected": "test output",
                                      "metadata": {},
                                      "tags": [],
                                      "_xact_id": "12346",
                                      "created": "2024-01-01T00:00:00Z"
                                    }
                                  ],
                                  "cursor": null
                                }
                                """
                                                        .formatted(datasetUuid))));

        Dataset<String, String> dataset =
                Dataset.fetchFromBraintrust(apiClient, projectName, datasetName, pinnedVersion);

        assertEquals(datasetUuid, dataset.id());
        assertEquals(Optional.of(pinnedVersion), dataset.version());

        List<DatasetCase<String, String>> cases = new ArrayList<>();
        dataset.forEach(cases::add);

        assertEquals(1, cases.size());
        assertEquals("test input", cases.get(0).input());
        assertEquals("test output", cases.get(0).expected());

        wireMock.verify(
                1,
                postRequestedFor(urlEqualTo("/v1/dataset/" + datasetUuid + "/fetch"))
                        .withRequestBody(matchingJsonPath("$.version", equalTo(pinnedVersion))));
    }

    @Test
    void testFetchFromBraintrustNotFound() {
        String projectName = "test-project";
        String datasetName = "nonexistent";

        wireMock.stubFor(
                get(urlPathEqualTo("/v1/dataset"))
                        .withQueryParam("project_name", equalTo(projectName))
                        .withQueryParam("dataset_name", equalTo(datasetName))
                        .willReturn(
                                aResponse()
                                        .withStatus(200)
                                        .withHeader("Content-Type", "application/json")
                                        .withBody(
                                                """
                                {"objects": []}
                                """)));

        RuntimeException exception =
                assertThrows(
                        RuntimeException.class,
                        () ->
                                Dataset.fetchFromBraintrust(
                                        apiClient, projectName, datasetName, null));

        assertTrue(exception.getMessage().contains("Dataset not found"));
    }
}
