package dev.braintrust.sdkspecimpl.clients;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.braintrust.Bedrock30TestUtils;
import dev.braintrust.instrumentation.awsbedrock.v2_30_0.BraintrustAWSBedrock;
import dev.braintrust.sdkspecimpl.LlmSpanSpec;
import dev.braintrust.sdkspecimpl.SpecClient;
import dev.braintrust.sdkspecimpl.SpecClientContext;
import java.util.Map;
import software.amazon.awssdk.services.bedrockruntime.model.ConverseRequest;
import software.amazon.awssdk.services.bedrockruntime.model.ConverseStreamRequest;
import software.amazon.awssdk.services.bedrockruntime.model.ConverseStreamResponseHandler;

/** AWS Bedrock runtime client: converse and converse-stream. */
public final class BedrockSpecClient implements SpecClient {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private volatile Bedrock30TestUtils bedrockUtils;

    @Override
    public String id() {
        return "bedrock";
    }

    @Override
    public String provider() {
        return "bedrock";
    }

    @Override
    public boolean supports(LlmSpanSpec spec) {
        return spec.endpoint().contains("/converse");
    }

    @Override
    public void executeSpec(LlmSpanSpec spec, SpecClientContext ctx) throws Exception {
        for (Map<String, Object> request : spec.requests()) {
            if (spec.endpoint().contains("/converse-stream")) {
                executeConverseStream(ctx, request);
            } else {
                executeConverse(ctx, request);
            }
        }
    }

    private Bedrock30TestUtils utils(SpecClientContext ctx) {
        Bedrock30TestUtils result = bedrockUtils;
        if (result == null) {
            synchronized (this) {
                result = bedrockUtils;
                if (result == null) {
                    result = new Bedrock30TestUtils(ctx.harness());
                    bedrockUtils = result;
                }
            }
        }
        return result;
    }

    /**
     * Unmarshaller that uses the AWS SDK's internal {@link
     * software.amazon.awssdk.protocols.json.internal.unmarshall.JsonProtocolUnmarshaller} (via
     * reflection) to deserialize JSON into SDK model objects (SdkPojo). This is the same machinery
     * the SDK uses to parse API responses.
     */
    private static final Object BEDROCK_UNMARSHALLER;

    private static final software.amazon.awssdk.protocols.jsoncore.JsonNodeParser
            BEDROCK_JSON_PARSER = software.amazon.awssdk.protocols.jsoncore.JsonNodeParser.create();

    static {
        try {
            // JsonProtocolUnmarshaller is @SdkInternalApi, so we construct it reflectively.
            Class<?> unmarshallerClass =
                    Class.forName(
                            "software.amazon.awssdk.protocols.json.internal.unmarshall.JsonProtocolUnmarshaller");
            var builderMethod = unmarshallerClass.getMethod("builder");
            var builderObj = builderMethod.invoke(null);
            var builderClass = builderObj.getClass();

            // Set the parser
            builderClass
                    .getMethod(
                            "parser",
                            software.amazon.awssdk.protocols.jsoncore.JsonNodeParser.class)
                    .invoke(builderObj, BEDROCK_JSON_PARSER);

            // Use default protocol unmarshall dependencies
            var depsMethod = unmarshallerClass.getMethod("defaultProtocolUnmarshallDependencies");
            var deps = depsMethod.invoke(null);
            builderClass
                    .getMethod(
                            "protocolUnmarshallDependencies",
                            Class.forName(
                                    "software.amazon.awssdk.protocols.json.internal.unmarshall.ProtocolUnmarshallDependencies"))
                    .invoke(builderObj, deps);

            BEDROCK_UNMARSHALLER = builderClass.getMethod("build").invoke(builderObj);
        } catch (Exception e) {
            throw new RuntimeException("Failed to create Bedrock JSON unmarshaller", e);
        }
    }

    /**
     * Deserialize a JSON string into an AWS SDK model object using the SDK's internal unmarshaller.
     * The object must implement {@link software.amazon.awssdk.core.SdkPojo}.
     */
    @SuppressWarnings("unchecked")
    private static <T extends software.amazon.awssdk.core.SdkPojo> T bedrockFromJson(
            String json, software.amazon.awssdk.core.SdkPojo builderInstance) throws Exception {
        software.amazon.awssdk.protocols.jsoncore.JsonNode jsonNode =
                BEDROCK_JSON_PARSER.parse(
                        new java.io.ByteArrayInputStream(
                                json.getBytes(java.nio.charset.StandardCharsets.UTF_8)));

        // Build a minimal SdkHttpFullResponse — the unmarshaller only uses it for
        // explicit payload members (SdkBytes/String), which normal Converse fields don't have.
        var response =
                software.amazon.awssdk.http.SdkHttpFullResponse.builder().statusCode(200).build();

        // Call unmarshall(SdkPojo, SdkHttpFullResponse, JsonNode) reflectively.
        var method =
                BEDROCK_UNMARSHALLER
                        .getClass()
                        .getMethod(
                                "unmarshall",
                                software.amazon.awssdk.core.SdkPojo.class,
                                software.amazon.awssdk.http.SdkHttpFullResponse.class,
                                software.amazon.awssdk.protocols.jsoncore.JsonNode.class);
        return (T) method.invoke(BEDROCK_UNMARSHALLER, builderInstance, response, jsonNode);
    }

    private void executeConverse(SpecClientContext ctx, Map<String, Object> request)
            throws Exception {
        String json = MAPPER.writeValueAsString(request);
        ConverseRequest converseRequest = bedrockFromJson(json, ConverseRequest.builder());

        var builder = BraintrustAWSBedrock.wrap(ctx.otel(), utils(ctx).syncClientBuilder());
        try (var client = builder.build()) {
            client.converse(converseRequest);
        }
    }

    private void executeConverseStream(SpecClientContext ctx, Map<String, Object> request)
            throws Exception {
        String json = MAPPER.writeValueAsString(request);
        ConverseStreamRequest converseStreamRequest =
                bedrockFromJson(json, ConverseStreamRequest.builder());

        var asyncBuilder = BraintrustAWSBedrock.wrap(ctx.otel(), utils(ctx).asyncClientBuilder());
        try (var client = asyncBuilder.build()) {
            client.converseStream(
                            converseStreamRequest,
                            ConverseStreamResponseHandler.builder()
                                    .subscriber(
                                            ConverseStreamResponseHandler.Visitor.builder().build())
                                    .build())
                    .get();
        }
    }
}
