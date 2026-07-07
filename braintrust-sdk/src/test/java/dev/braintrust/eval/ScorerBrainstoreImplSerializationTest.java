package dev.braintrust.eval;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import dev.braintrust.api.BraintrustOpenApiClient;
import dev.braintrust.config.BraintrustConfig;
import dev.braintrust.json.BraintrustJsonMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

/**
 * Verifies that user-supplied task/dataset objects in scorer invoke payloads are serialized with
 * the SDK's shared mapper ({@link BraintrustJsonMapper}) by default, and that custom input/output
 * converters can be supplied to control serialization.
 */
public class ScorerBrainstoreImplSerializationTest {

    @RegisterExtension
    static WireMockExtension wireMock =
            WireMockExtension.newInstance().options(wireMockConfig().dynamicPort()).build();

    private static final String FUNCTION_ID = "00000000-0000-0000-0000-000000000abc";
    private static final String PROJECT_ID = "00000000-0000-0000-0000-000000000def";

    private BraintrustOpenApiClient apiClient;

    public record MyInput(String userPrompt, @JsonIgnore String secretKey) {}

    public record MyOutput(String finalAnswer) {}

    @BeforeEach
    void beforeEach() {
        wireMock.resetAll();
        var config =
                BraintrustConfig.builder()
                        .apiKey("test-api-key")
                        .apiUrl("http://localhost:" + wireMock.getPort())
                        .build();
        apiClient = BraintrustOpenApiClient.of(config);

        wireMock.stubFor(
                get(urlPathEqualTo("/v1/function/" + FUNCTION_ID))
                        .willReturn(
                                aResponse()
                                        .withStatus(200)
                                        .withHeader("Content-Type", "application/json")
                                        .withBody(
                                                """
                                {
                                  "id": "%s",
                                  "project_id": "%s",
                                  "name": "my-scorer",
                                  "slug": "my-scorer",
                                  "_xact_id": "1",
                                  "created": "2024-01-01T00:00:00Z",
                                  "log_id": "p",
                                  "org_id": "00000000-0000-0000-0000-000000000001",
                                  "function_data": {"type": "code"}
                                }
                                """
                                                        .formatted(FUNCTION_ID, PROJECT_ID))));

        wireMock.stubFor(
                get(urlPathEqualTo("/v1/project/" + PROJECT_ID))
                        .willReturn(
                                aResponse()
                                        .withStatus(200)
                                        .withHeader("Content-Type", "application/json")
                                        .withBody(
                                                """
                                {
                                  "id": "%s",
                                  "org_id": "00000000-0000-0000-0000-000000000001",
                                  "name": "test-project"
                                }
                                """
                                                        .formatted(PROJECT_ID))));

        wireMock.stubFor(
                post(urlPathEqualTo("/v1/function/" + FUNCTION_ID + "/invoke"))
                        .willReturn(
                                aResponse()
                                        .withStatus(200)
                                        .withHeader("Content-Type", "application/json")
                                        .withBody("0.5")));
    }

    @Test
    void scorerArgsAreSerializedWithBraintrustJsonMapperByDefault() {
        Scorer<MyInput, MyOutput> scorer = new ScorerBrainstoreImpl<>(apiClient, FUNCTION_ID, null);

        var datasetCase =
                DatasetCase.of(new MyInput("what is 2+2?", "hunter2"), new MyOutput("four"));
        var taskResult = new TaskResult<>(new MyOutput("4"), datasetCase);

        var scores = scorer.score(taskResult);
        assertEquals(1, scores.size());
        assertEquals(0.5, scores.get(0).value(), 0.001);

        wireMock.verify(
                postRequestedFor(urlPathEqualTo("/v1/function/" + FUNCTION_ID + "/invoke"))
                        // default (BraintrustJsonMapper) uses snake_case field names,
                        // matching typed dataset deserialization and eval span logging
                        .withRequestBody(
                                matchingJsonPath(
                                        "$.input.input.user_prompt", equalTo("what is 2+2?")))
                        .withRequestBody(
                                matchingJsonPath("$.input.output.final_answer", equalTo("4")))
                        .withRequestBody(
                                matchingJsonPath("$.input.expected.final_answer", equalTo("four")))
                        // @JsonIgnore fields must not leak into the payload
                        .withRequestBody(notMatching(".*secret.*")));
    }

    @Test
    void customConvertersControlSerialization() {
        // Converters that wrap the input/output values, proving user control over serialization.
        Scorer<MyInput, MyOutput> scorer =
                new ScorerBrainstoreImpl<>(
                        apiClient,
                        FUNCTION_ID,
                        null,
                        (MyInput input) ->
                                java.util.Map.of(
                                        "wrapped", BraintrustJsonMapper.get().valueToTree(input)),
                        (MyOutput output) ->
                                java.util.Map.of(
                                        "wrapped", BraintrustJsonMapper.get().valueToTree(output)));

        var datasetCase =
                DatasetCase.of(new MyInput("what is 2+2?", "hunter2"), new MyOutput("four"));
        var taskResult = new TaskResult<>(new MyOutput("4"), datasetCase);

        var scores = scorer.score(taskResult);
        assertEquals(1, scores.size());
        assertEquals(0.5, scores.get(0).value(), 0.001);

        wireMock.verify(
                postRequestedFor(urlPathEqualTo("/v1/function/" + FUNCTION_ID + "/invoke"))
                        .withRequestBody(
                                matchingJsonPath(
                                        "$.input.input.wrapped.user_prompt",
                                        equalTo("what is 2+2?")))
                        .withRequestBody(
                                matchingJsonPath(
                                        "$.input.output.wrapped.final_answer", equalTo("4"))));
    }
}
