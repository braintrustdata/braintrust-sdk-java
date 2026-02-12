package dev.braintrust.eval;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.junit.jupiter.api.Assertions.*;

import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import dev.braintrust.api.BraintrustApiClient;
import dev.braintrust.config.BraintrustConfig;
import java.util.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

public class DatasetBrainstoreImplTest {

    @RegisterExtension
    static WireMockExtension wireMock =
            WireMockExtension.newInstance().options(wireMockConfig().dynamicPort()).build();

    private BraintrustApiClient apiClient;
    private String datasetId;

    @BeforeEach
    void beforeEach() {
        wireMock.resetAll();
        datasetId = "test-dataset-123";

        // Create API client pointing to WireMock server
        var config =
                BraintrustConfig.builder()
                        .apiKey("test-api-key")
                        .apiUrl("http://localhost:" + wireMock.getPort())
                        .build();
        apiClient = BraintrustApiClient.of(config);
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
                                      "dataset_id": "test-dataset-123",
                                      "id": "123-1",
                                      "created": "sometimestamp",
                                      "_xact_id": "1",
                                      "input": "Question 1",
                                      "expected": "Answer 1"
                                    },
                                    {
                                      "object_type": "dataset",
                                      "dataset_id": "test-dataset-123",
                                      "id": "123-2",
                                      "_xact_id": "1",
                                      "created": "sometimestamp",
                                      "input": "Question 2",
                                      "expected": "Answer 2"
                                    }
                                  ],
                                  "cursor": "next-page-token"
                                }
                                """)));

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
                                      "dataset_id": "test-dataset-123",
                                      "id": "123-3",
                                      "_xact_id": "1",
                                      "created": "sometimestamp",
                                      "input": "Question 3",
                                      "expected": "Answer 3"
                                    }
                                  ],
                                  "cursor": null
                                }
                                """)));

        // Create dataset with smaller batch size
        DatasetBrainstoreImpl<String, String> dataset =
                new DatasetBrainstoreImpl<>(apiClient, datasetId, "test-version", 2);

        List<DatasetCase<String, String>> cases = new ArrayList<>();
        dataset.forEach(cases::add);

        // Verify we got all 3 cases
        assertEquals(3, cases.size());
        List<String> tags = List.of();
        Map<String, Object> metadata = Map.of();
        assertEquals(
                new DatasetCase<>(
                        "Question 1",
                        "Answer 1",
                        tags,
                        metadata,
                        Optional.of(
                                new dev.braintrust.Origin(
                                        "dataset", datasetId, "123-1", "1", "sometimestamp"))),
                cases.get(0));
        assertEquals("Question 2", cases.get(1).input());
        assertEquals(
                new DatasetCase<>(
                        "Question 2",
                        "Answer 2",
                        tags,
                        metadata,
                        Optional.of(
                                new dev.braintrust.Origin(
                                        "dataset", datasetId, "123-2", "1", "sometimestamp"))),
                cases.get(1));
        assertEquals("Question 3", cases.get(2).input());
        assertEquals(
                new DatasetCase<>(
                        "Question 3",
                        "Answer 3",
                        tags,
                        metadata,
                        Optional.of(
                                new dev.braintrust.Origin(
                                        "dataset", datasetId, "123-3", "1", "sometimestamp"))),
                cases.get(2));

        // Verify the API was called twice (once for each batch)
        wireMock.verify(2, postRequestedFor(urlEqualTo("/v1/dataset/" + datasetId + "/fetch")));
    }

    @Test
    void testEmptyDataset() {
        // Mock empty dataset
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

        // Verify we got no cases
        assertEquals(0, cases.size());

        // Verify the API was called once
        wireMock.verify(1, postRequestedFor(urlEqualTo("/v1/dataset/" + datasetId + "/fetch")));
    }

    @Test
    void testFetchWithPinnedVersion() {
        String projectName = "test-project";
        String datasetName = "test-dataset";
        String pinnedVersion = "12345";

        // Mock the query endpoint
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
                                      "object_type": "dataset",
                                      "dataset_id": "test-dataset-123",
                                      "id": "dataset-789",
                                      "project_id": "proj-456",
                                      "name": "test-dataset",
                                      "description": "Test dataset",
                                      "_xact_id": "12345",
                                      "input": "test input",
                                      "expected": "test output",
                                      "created": "sometimestamp"
                                    }
                                  ]
                                }
                                """)));

        // Mock the fetch endpoint - only succeeds if version is passed correctly
        wireMock.stubFor(
                post(urlEqualTo("/v1/dataset/dataset-789/fetch"))
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
                                      "object_type": "dataset",
                                      "dataset_id": "test-dataset-123",
                                      "id": "some-row-id",
                                      "input": "test input",
                                      "expected": "test output",
                                      "metadata": {},
                                      "tags": [],
                                      "_xact_id": "12346",
                                      "created": "sometimestamp"
                                    }
                                  ],
                                  "cursor": null
                                }
                                """)));

        Dataset<String, String> dataset =
                Dataset.fetchFromBraintrust(apiClient, projectName, datasetName, pinnedVersion);

        assertEquals("dataset-789", dataset.id());
        assertEquals(Optional.of(pinnedVersion), dataset.version());

        // Open cursor and fetch data to trigger the API call with version
        List<DatasetCase<String, String>> cases = new ArrayList<>();
        dataset.forEach(cases::add);

        // Verify we got the expected case
        assertEquals(1, cases.size());
        assertEquals("test input", cases.get(0).input());
        assertEquals("test output", cases.get(0).expected());

        // Verify the fetch endpoint was called with the version
        wireMock.verify(
                1,
                postRequestedFor(urlEqualTo("/v1/dataset/dataset-789/fetch"))
                        .withRequestBody(matchingJsonPath("$.version", equalTo(pinnedVersion))));
    }

    @Test
    void testFetchFromBraintrustNotFound() {
        String projectName = "test-project";
        String datasetName = "nonexistent";

        // Mock empty response
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
