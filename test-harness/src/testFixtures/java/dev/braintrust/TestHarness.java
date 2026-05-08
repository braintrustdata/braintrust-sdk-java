package dev.braintrust;

import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.braintrust.api.BraintrustOpenApiClient;
import dev.braintrust.config.BraintrustConfig;
import dev.braintrust.eval.Dataset;
import dev.braintrust.eval.DatasetCase;
import dev.braintrust.openapi.api.DatasetsApi;
import dev.braintrust.openapi.api.FunctionsApi;
import dev.braintrust.openapi.api.PromptsApi;
import dev.braintrust.openapi.model.Chat;
import dev.braintrust.openapi.model.ChatCompletionMessageParam;
import dev.braintrust.openapi.model.ChatMessageUser;
import dev.braintrust.openapi.model.CodeBundleRuntimeContext;
import dev.braintrust.openapi.model.CodeData;
import dev.braintrust.openapi.model.CreateDataset;
import dev.braintrust.openapi.model.CreateFunction;
import dev.braintrust.openapi.model.CreatePrompt;
import dev.braintrust.openapi.model.FunctionData;
import dev.braintrust.openapi.model.FunctionDataCode;
import dev.braintrust.openapi.model.FunctionDataPrompt;
import dev.braintrust.openapi.model.FunctionTypeEnumNullish;
import dev.braintrust.openapi.model.Inline;
import dev.braintrust.openapi.model.InsertDatasetEvent;
import dev.braintrust.openapi.model.InsertDatasetEventRequest;
import dev.braintrust.openapi.model.InsertProjectLogsEventMetadata;
import dev.braintrust.openapi.model.ModelParams;
import dev.braintrust.openapi.model.OpenAIModelParams;
import dev.braintrust.openapi.model.PatchFunction;
import dev.braintrust.openapi.model.PatchPrompt;
import dev.braintrust.openapi.model.PromptBlockDataNullish;
import dev.braintrust.openapi.model.PromptDataNullish;
import dev.braintrust.openapi.model.PromptOptionsNullish;
import dev.braintrust.openapi.model.PromptParserNullish;
import dev.braintrust.openapi.model.SystemContent;
import dev.braintrust.openapi.model.UserContent;
import dev.braintrust.trace.HarnessShim;
import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.baggage.propagation.W3CBaggagePropagator;
import io.opentelemetry.api.trace.propagation.W3CTraceContextPropagator;
import io.opentelemetry.context.propagation.ContextPropagators;
import io.opentelemetry.context.propagation.TextMapPropagator;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.logs.SdkLoggerProvider;
import io.opentelemetry.sdk.metrics.SdkMeterProvider;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import javax.annotation.Nonnull;
import lombok.Getter;
import lombok.SneakyThrows;
import lombok.experimental.Accessors;

public class TestHarness {
    private static final String TEST_HARNESS_CREATED_TAG = "test-harness-created";
    private static final VCR vcr;
    private static final BraintrustConfig envConfig =
            BraintrustConfig.builder()
                    .defaultProjectName("java-unit-test")
                    .apiKey(apiKeyFromEnv())
                    .build();

    @Getter
    @Accessors(fluent = true)
    private static final String defaultOrgName;

    @Getter
    @Accessors(fluent = true)
    private static final String defaultProjectName;

    @Getter
    @Accessors(fluent = true)
    private static final String defaultOrgId;

    @Getter
    @Accessors(fluent = true)
    private static final String defaultProjectId;

    private static final AtomicReference<TestHarness> INSTANCE = new AtomicReference<>();

    static {
        // Collect all API keys that should never appear in recorded cassettes
        List<String> apiKeysToNeverRecord =
                List.of(
                        getEnv("OPENAI_API_KEY", ""),
                        getEnv("ANTHROPIC_API_KEY", ""),
                        getEnv("GOOGLE_API_KEY", getEnv("GEMINI_API_KEY", "")),
                        getEnv("BRAINTRUST_API_KEY", ""),
                        getEnv("AWS_ACCESS_KEY_ID", ""),
                        getEnv("AWS_SECRET_ACCESS_KEY", ""),
                        getEnv("AWS_SESSION_TOKEN", ""));

        vcr =
                new VCR(
                        java.util.Map.of(
                                "https://api.openai.com/v1",
                                "openai",
                                "https://api.anthropic.com",
                                "anthropic",
                                "https://generativelanguage.googleapis.com",
                                "google",
                                envConfig.apiUrl(),
                                "braintrust",
                                "https://bedrock-runtime.us-east-1.amazonaws.com",
                                "bedrock"),
                        apiKeysToNeverRecord);
        vcr.start();
        HarnessShim.addShutdownHook(vcr::stop);
        { // set up default project if needed
            var harness = setup();
            var client = harness.braintrust.openApiClient();
            var project = client.fetchOrCreateProject(envConfig);
            defaultOrgId = project.getOrgId().toString();
            defaultProjectId = project.getId().toString();
            defaultProjectName = project.getName();
            var orgs = client.login().orgInfo();
            BraintrustOpenApiClient.OrgInfo defaultOrg = null;
            for (var org : orgs) {
                if (org.id().equals(defaultOrgId)) {
                    defaultOrg = org;
                    break;
                }
            }
            Objects.requireNonNull(defaultOrg);
            defaultOrgName = defaultOrg.name();
        }
    }

    public static TestHarness setup() {
        return setup(cfg -> cfg);
    }

    public static TestHarness setup(
            Function<BraintrustConfig.Builder, BraintrustConfig.Builder> configCustomizer) {
        var configBuilder =
                BraintrustConfig.builder()
                        .apiUrl(vcr.getUrlForTargetBase(envConfig.apiUrl()))
                        .defaultProjectName(defaultProjectName());
        if (vcr.getMode() == VCR.VcrMode.REPLAY) {
            // tolerate missing api key in replay mode
            configBuilder.apiKey(apiKeyFromEnv());
        }
        configBuilder = configCustomizer.apply(configBuilder);
        return setup(configBuilder.build());
    }

    private static synchronized TestHarness setup(BraintrustConfig config) {
        GlobalOpenTelemetry.resetForTest();
        Braintrust.resetForTest();

        var braintrust = Braintrust.of(config);
        var harness = new TestHarness(braintrust);
        INSTANCE.set(harness);
        GlobalOpenTelemetry.set(harness.openTelemetry());
        return harness;
    }

    @Getter
    @Accessors(fluent = true)
    private final OpenTelemetrySdk openTelemetry;

    @Getter
    @Accessors(fluent = true)
    private final Braintrust braintrust;

    private final @Nonnull UnitTestSpanExporter spanExporter;

    private TestHarness(@Nonnull Braintrust braintrust) {
        this.braintrust = braintrust;
        var tracerBuilder = SdkTracerProvider.builder();
        this.spanExporter = new UnitTestSpanExporter();
        var loggerBuilder = SdkLoggerProvider.builder();
        var meterBuilder = SdkMeterProvider.builder();
        // Wire the in-memory span exporter as an additional delegate inside the
        // BraintrustSpanProcessor so it sees post-processed spans (attachment references
        // instead of raw base64 data URIs, etc.).
        dev.braintrust.trace.HarnessShim.enableTracing(
                braintrust.config(),
                tracerBuilder,
                List.of(SimpleSpanProcessor.create(this.spanExporter)),
                loggerBuilder,
                meterBuilder);
        var contextPropagator =
                ContextPropagators.create(
                        TextMapPropagator.composite(
                                W3CTraceContextPropagator.getInstance(),
                                W3CBaggagePropagator.getInstance()));
        var openTelemetry =
                OpenTelemetrySdk.builder()
                        .setTracerProvider(tracerBuilder.build())
                        .setLoggerProvider(loggerBuilder.build())
                        .setMeterProvider(meterBuilder.build())
                        .setPropagators(contextPropagator)
                        .build();
        this.openTelemetry = openTelemetry;
    }

    private static String apiKeyFromEnv() {
        return getEnv("BRAINTRUST_API_KEY", "sk-000000000000000000000000000000000000000000000000");
    }

    public String openAiBaseUrl() {
        return vcr.getUrlForTargetBase("https://api.openai.com/v1");
    }

    public String openAiApiKey() {
        return getEnv("OPENAI_API_KEY", "test-key");
    }

    public String anthropicBaseUrl() {
        return vcr.getUrlForTargetBase("https://api.anthropic.com");
    }

    public String anthropicApiKey() {
        return getEnv("ANTHROPIC_API_KEY", "test-key");
    }

    public String googleBaseUrl() {
        return vcr.getUrlForTargetBase("https://generativelanguage.googleapis.com");
    }

    public String googleApiKey() {
        return getEnv("GOOGLE_API_KEY", getEnv("GEMINI_API_KEY", "test-key"));
    }

    /**
     * Returns the VCR proxy URL for the Bedrock Runtime endpoint in the given region. The region
     * must have been registered in the VCR target map at init time.
     */
    public String bedrockBaseUrl(String region) {
        if (!"us-east-1".equals(region)) {
            throw new RuntimeException("unsupported region: " + region);
        }
        return vcr.getUrlForTargetBase("https://bedrock-runtime." + region + ".amazonaws.com");
    }

    public String braintrustApiBaseUrl() {
        return braintrust.config().apiUrl();
    }

    public String braintrustApiKey() {
        return braintrust.config().apiKey();
    }

    /** flush all pending spans and return all spans which have been exported so far */
    public List<SpanData> awaitExportedSpans() {
        assertTrue(
                openTelemetry
                        .getSdkTracerProvider()
                        .forceFlush()
                        .join(10, TimeUnit.SECONDS)
                        .isSuccess());
        return spanExporter.getFinishedSpanItems();
    }

    /**
     * Ensure that the given dataset exists on braintrust with the expected dataset rows
     *
     * <p>creates / replaces the dataset to match the expected data as needed.
     *
     * @param datasetName name of the expected dataset
     * @param expectedData data expected to be in the dataset
     */
    public void ensureRemoteDataset(String datasetName, Dataset<?, ?> expectedData) {
        DatasetsApi datasetsApi = new DatasetsApi(braintrust.openApiClient());
        var dataset =
                datasetsApi.postDataset(
                        new CreateDataset()
                                .projectId(UUID.fromString(TestHarness.defaultProjectId()))
                                .name(datasetName)
                                .tags(List.of(TEST_HARNESS_CREATED_TAG)));
        if (datasetsEqual(expectedData, braintrust.fetchDataset(datasetName))) {
            return;
        }

        // easier to just recreate the dataset from scratch
        datasetsApi.deleteDatasetId(dataset.getId());
        dataset =
                datasetsApi.postDataset(
                        new CreateDataset()
                                .projectId(UUID.fromString(TestHarness.defaultProjectId()))
                                .name(datasetName)
                                .tags(List.of(TEST_HARNESS_CREATED_TAG)));

        var insertRequest = new InsertDatasetEventRequest();
        expectedData.forEach(
                row -> {
                    var meta = new InsertProjectLogsEventMetadata();
                    meta.putAll(row.metadata());
                    insertRequest.addEventsItem(
                            new InsertDatasetEvent()
                                    .input(row.input())
                                    .expected(row.expected())
                                    .metadata(meta)
                                    .tags(row.tags()));
                });

        datasetsApi.postDatasetIdInsert(dataset.getId(), insertRequest);

        // verify
        var btDataset = braintrust.fetchDataset(datasetName);
        if (datasetsEqual(expectedData, btDataset)) {
            throw new RuntimeException(
                    "failed to ensure expected dataset: %s -- %s"
                            .formatted(toList(expectedData), toList(btDataset)));
        }
    }

    private static boolean datasetsEqual(Dataset<?, ?> dataset1, Dataset<?, ?> dataset2) {
        var cases1 = toList(dataset1);
        var cases2 = toList(dataset2);
        if (cases1.size() != cases2.size()) {
            return false;
        }
        for (int i = 0; i < cases1.size(); i++) {
            var expRow = stripOrigin(cases2.get(i));
            var btRow = stripOrigin(cases1.get(i));
            if (!expRow.equals(btRow)) {
                return false;
            }
        }
        return true;
    }

    private static DatasetCase<?, ?> stripOrigin(DatasetCase<?, ?> row) {
        return new DatasetCase<>(
                row.input(), row.expected(), row.tags(), row.metadata(), Optional.empty());
    }

    private static List<DatasetCase<?, ?>> toList(Dataset<?, ?> dataset) {
        List<DatasetCase<?, ?>> result = new ArrayList<>();
        dataset.forEach(result::add);
        return List.copyOf(result);
    }

    /**
     * Result of {@link #ensureRemoteCodeScorer} containing the function slug and the version ID for
     * each uploaded code version.
     *
     * @param slug the function's slug (used to fetch it by name)
     * @param versionIds version IDs in the same order as the input code list (oldest first)
     */
    public record CodeScorerInfo(String slug, List<String> versionIds) {}

    /**
     * Marker prefix for the description field used to store version IDs. The description is
     * formatted as {@code TEST_HARNESS_VERSIONS:id1,id2,...} so that subsequent runs can read back
     * the version IDs without re-creating the function.
     */
    private static final String VERSION_IDS_PREFIX = "TEST_HARNESS_VERSIONS:";

    /**
     * Ensure that a code scorer (function) with the given name exists on Braintrust with the
     * expected version history. Each element of {@code scorerCode} is a TypeScript source string;
     * the first element is the oldest version, the last is the latest.
     *
     * <p>If the function already exists, its latest inline code matches, and the stored version IDs
     * (in the description field) have the expected count, this is a no-op and returns the existing
     * info.
     *
     * <p>Otherwise the function is deleted and recreated from scratch, with one {@code PUT
     * /v1/function} per code version. The resulting version IDs are stored in the description field
     * for future runs.
     *
     * @param scorerName display name (also used to derive the slug)
     * @param scorerCode ordered list of TypeScript source versions (oldest first). Must be
     *     non-empty.
     * @return info about the created function including slug and per-version IDs
     */
    public CodeScorerInfo ensureRemoteCodeScorer(String scorerName, List<String> scorerCode) {
        if (scorerCode.isEmpty()) {
            throw new IllegalArgumentException("scorer code must be non-empty");
        }

        var functionsApi = new FunctionsApi(braintrust.openApiClient());
        var projectId = UUID.fromString(TestHarness.defaultProjectId());
        var projectName = TestHarness.defaultProjectName();

        // Check if the function already exists with the expected code and versions
        var existing = lookupFunction(functionsApi, projectName, scorerName);
        if (existing != null) {
            String existingCode = extractInlineCode(existing);
            String expectedLatestCode = scorerCode.get(scorerCode.size() - 1);
            if (expectedLatestCode.equals(existingCode)) {
                // Latest code matches -- try to read stored version IDs from description
                var storedIds = parseVersionIds(existing.getDescription());
                if (storedIds != null && storedIds.size() == scorerCode.size()) {
                    return new CodeScorerInfo(existing.getSlug(), storedIds);
                }
            }
            // Code or version count doesn't match -- delete and recreate
            functionsApi.deleteFunctionId(existing.getId());
        }

        // Create from scratch: PUT each version in order (oldest first)
        List<String> versionIds = new ArrayList<>();
        dev.braintrust.openapi.model.Function result = null;
        for (String code : scorerCode) {
            result = putCodeScorer(functionsApi, projectId, scorerName, code);
            versionIds.add(result.getXactId());
        }

        // Store version IDs in the description field so future runs can skip recreation.
        // PATCH the description on the final version so we don't create yet another version.
        var patch = new PatchFunction();
        patch.description(VERSION_IDS_PREFIX + String.join(",", versionIds));
        functionsApi.patchFunctionId(result.getId(), patch);

        return new CodeScorerInfo(result.getSlug(), List.copyOf(versionIds));
    }

    /** Parse version IDs from a description string, or null if not in the expected format. */
    private static List<String> parseVersionIds(String description) {
        if (description == null || !description.startsWith(VERSION_IDS_PREFIX)) {
            return null;
        }
        String csv = description.substring(VERSION_IDS_PREFIX.length());
        if (csv.isEmpty()) {
            return null;
        }
        return List.of(csv.split(","));
    }

    /** Look up a function by project name + slug, returning null if not found. */
    private static dev.braintrust.openapi.model.Function lookupFunction(
            FunctionsApi functionsApi, String projectName, String slug) {
        try {
            var response =
                    functionsApi.getFunction(
                            1, null, null, null, null, projectName, null, slug, null, null, null);
            var objects = response.getObjects();
            return (objects != null && !objects.isEmpty()) ? objects.get(0) : null;
        } catch (Exception e) {
            return null;
        }
    }

    /** Extract the inline source code from a Function, or null if not an inline code function. */
    private static String extractInlineCode(dev.braintrust.openapi.model.Function function) {
        try {
            var codeData = (FunctionDataCode) function.getFunctionData().getActualInstance();
            var inline = (Inline) codeData.getData().getActualInstance();
            return inline.getCode();
        } catch (Exception e) {
            return null;
        }
    }

    /** PUT a single code scorer version and return the created/replaced Function. */
    private static dev.braintrust.openapi.model.Function putCodeScorer(
            FunctionsApi functionsApi, UUID projectId, String scorerName, String code) {
        var inlineCode =
                new Inline()
                        .type(Inline.TypeEnum.INLINE)
                        .runtimeContext(
                                new CodeBundleRuntimeContext()
                                        .runtime(CodeBundleRuntimeContext.RuntimeEnum.NODE)
                                        .version("20"))
                        .code(code);

        var functionData =
                new FunctionData(
                        FunctionData.SchemaType.FunctionDataCode,
                        new FunctionDataCode()
                                .type(FunctionDataCode.TypeEnum.CODE)
                                .data(new CodeData(CodeData.SchemaType.Inline, inlineCode)));

        var createFunction =
                new CreateFunction()
                        .projectId(projectId)
                        .name(scorerName)
                        .slug(scorerName)
                        .tags(List.of(TEST_HARNESS_CREATED_TAG))
                        .functionType(FunctionTypeEnumNullish.SCORER)
                        .functionData(functionData);

        return functionsApi.putFunction(createFunction);
    }

    /**
     * Ensure that an LLM judge scorer (prompt-based function) with the given name exists on
     * Braintrust. The scorer uses a chat prompt with the given template and an LLM classifier
     * parser with the given choice-to-score mapping.
     *
     * <p>If the function already exists and its prompt content matches, this is a no-op. Otherwise
     * it is deleted and recreated.
     *
     * @param scorerName display name (also used to derive the slug)
     * @param promptTemplate the user message template (may contain {@code {{expected}}} and {@code
     *     {{output}}} mustache placeholders)
     * @param choiceScores map of choice labels to scores, e.g. {@code {"NO": 0.0, "YES": 1.0}}
     * @return the function's slug
     */
    public String ensureRemoteLLMJudgeScorer(
            String scorerName, String promptTemplate, Map<String, Double> choiceScores) {
        var functionsApi = new FunctionsApi(braintrust.openApiClient());
        var projectId = UUID.fromString(TestHarness.defaultProjectId());
        var projectName = TestHarness.defaultProjectName();

        // Check if the function already exists with the expected prompt
        var existing = lookupFunction(functionsApi, projectName, scorerName);
        if (existing != null) {
            String existingPrompt = extractPromptContent(existing);
            if (promptTemplate.equals(existingPrompt)) {
                return existing.getSlug();
            }
            // Prompt doesn't match -- delete and recreate
            functionsApi.deleteFunctionId(existing.getId());
        }

        // Build the prompt data
        var userContent = new UserContent(UserContent.SchemaType.Text, promptTemplate);
        var userMessage =
                new ChatMessageUser().role(ChatMessageUser.RoleEnum.USER).content(userContent);
        var messageParam =
                new ChatCompletionMessageParam(
                        ChatCompletionMessageParam.SchemaType.ChatMessageUser, userMessage);
        var chat = new Chat().type(Chat.TypeEnum.CHAT).messages(List.of(messageParam));
        var promptBlock = new PromptBlockDataNullish(PromptBlockDataNullish.SchemaType.Chat, chat);

        // Build the parser (llm_classifier with choice_scores)
        var choiceScoresBigDecimal = new java.util.LinkedHashMap<String, java.math.BigDecimal>();
        choiceScores.forEach(
                (k, v) -> choiceScoresBigDecimal.put(k, java.math.BigDecimal.valueOf(v)));
        var parser =
                new PromptParserNullish()
                        .type(PromptParserNullish.TypeEnum.LLM_CLASSIFIER)
                        .useCot(true)
                        .choiceScores(choiceScoresBigDecimal);

        // Build model options
        var modelParams =
                new ModelParams(
                        ModelParams.SchemaType.OpenAIModelParams,
                        new OpenAIModelParams().temperature(java.math.BigDecimal.ZERO));
        var options = new PromptOptionsNullish().model("gpt-4o-mini").params(modelParams);

        var promptData =
                new PromptDataNullish().prompt(promptBlock).options(options).parser(parser);

        // function_data for a prompt-type function just indicates type=prompt
        var functionData =
                new FunctionData(
                        FunctionData.SchemaType.FunctionDataPrompt,
                        new FunctionDataPrompt().type(FunctionDataPrompt.TypeEnum.PROMPT));

        var createFunction =
                new CreateFunction()
                        .projectId(projectId)
                        .name(scorerName)
                        .slug(scorerName)
                        .tags(List.of(TEST_HARNESS_CREATED_TAG))
                        .functionType(FunctionTypeEnumNullish.SCORER)
                        .functionData(functionData)
                        .promptData(promptData);

        var result = functionsApi.putFunction(createFunction);
        return result.getSlug();
    }

    /**
     * Extract the prompt text content from a prompt-type Function, or null if not applicable.
     * Navigates: promptData → prompt → Chat → messages[0] → ChatMessageUser → content → text.
     */
    private static String extractPromptContent(dev.braintrust.openapi.model.Function function) {
        try {
            var promptData = function.getPromptData();
            if (promptData == null) return null;
            var promptBlock = (Chat) promptData.getPrompt().getActualInstance();
            var messages = promptBlock.getMessages();
            if (messages == null || messages.isEmpty()) return null;
            var userMsg = (ChatMessageUser) messages.get(0).getActualInstance();
            return (String) userMsg.getContent().getActualInstance();
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Definition of a single prompt version to be created.
     *
     * @param messages chat messages as role→content pairs, e.g. {@code [Map.of("role", "system",
     *     "content", "...")]}
     * @param model the model to configure in options (e.g. {@code "gpt-4o-mini"}), or null for no
     *     model
     */
    public record PromptVersionDef(List<Map<String, String>> messages, String model) {}

    /**
     * Result of {@link #ensureRemotePrompt} containing the prompt slug and per-version IDs.
     *
     * @param slug the prompt's slug (used to fetch it by name)
     * @param versionIds version IDs in the same order as the input list (oldest first)
     */
    public record PromptInfo(String slug, List<String> versionIds) {}

    /**
     * Ensure that a prompt with the given name exists on Braintrust with the expected version
     * history. Each element of {@code versions} defines one version's chat messages and model; the
     * first element is the oldest version, the last is the latest.
     *
     * <p>If the prompt already exists, its latest chat content matches, and the stored version IDs
     * (in the description field) have the expected count, this is a no-op.
     *
     * <p>Otherwise the prompt is deleted and recreated from scratch via {@code PUT /v1/prompt} for
     * each version. The resulting version IDs are stored in the description field for future runs.
     *
     * @param promptName display name (also used to derive the slug)
     * @param versions ordered list of prompt version definitions (oldest first). Must be non-empty.
     * @return info about the created prompt including slug and per-version IDs
     */
    public PromptInfo ensureRemotePrompt(String promptName, List<PromptVersionDef> versions) {
        if (versions.isEmpty()) {
            throw new IllegalArgumentException("versions must be non-empty");
        }

        var promptsApi = new PromptsApi(braintrust.openApiClient());
        var projectId = UUID.fromString(TestHarness.defaultProjectId());
        var projectName = TestHarness.defaultProjectName();

        // Check if the prompt already exists with the expected content
        var existing = lookupPrompt(promptsApi, projectName, promptName);
        if (existing != null) {
            String existingContent = extractPromptUserContent(existing);
            String expectedLatestContent = latestMessageContent(versions.get(versions.size() - 1));
            if (expectedLatestContent != null && expectedLatestContent.equals(existingContent)) {
                // Content matches -- try to read stored version IDs from description
                var storedIds = parseVersionIds(existing.getDescription());
                if (storedIds != null && storedIds.size() == versions.size()) {
                    return new PromptInfo(existing.getSlug(), storedIds);
                }
            }
            // Content or version count doesn't match -- delete and recreate
            promptsApi.deletePromptId(existing.getId());
        }

        // Create from scratch: PUT each version in order (oldest first)
        List<String> versionIds = new ArrayList<>();
        dev.braintrust.openapi.model.Prompt result = null;
        for (var version : versions) {
            result = putPrompt(promptsApi, projectId, promptName, version);
            versionIds.add(result.getXactId());
        }

        // Store version IDs in the description field so future runs can skip recreation
        var patch = new PatchPrompt();
        patch.description(VERSION_IDS_PREFIX + String.join(",", versionIds));
        promptsApi.patchPromptId(result.getId(), patch);

        return new PromptInfo(result.getSlug(), List.copyOf(versionIds));
    }

    /** Look up a prompt by project name + slug, returning null if not found. */
    private static dev.braintrust.openapi.model.Prompt lookupPrompt(
            PromptsApi promptsApi, String projectName, String slug) {
        try {
            var response =
                    promptsApi.getPrompt(
                            1, null, null, null, null, projectName, null, slug, null, null, null);
            var objects = response.getObjects();
            return (objects != null && !objects.isEmpty()) ? objects.get(0) : null;
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Extract the content of the first message in a prompt's chat block, for comparison. Returns
     * the content of whatever message role appears first (system or user).
     */
    private static String extractPromptUserContent(dev.braintrust.openapi.model.Prompt prompt) {
        try {
            var promptData = prompt.getPromptData();
            if (promptData == null) return null;
            var chat = (Chat) promptData.getPrompt().getActualInstance();
            var messages = chat.getMessages();
            if (messages == null || messages.isEmpty()) return null;
            // Try to extract content from the first message (could be system or user)
            var firstMsg = messages.get(messages.size() - 1).getActualInstance();
            if (firstMsg instanceof ChatMessageUser user) {
                return (String) user.getContent().getActualInstance();
            }
            if (firstMsg instanceof dev.braintrust.openapi.model.System system) {
                return (String) system.getContent().getActualInstance();
            }
            return null;
        } catch (Exception e) {
            return null;
        }
    }

    /** Get the content of the last message in a version def (for comparison purposes). */
    private static String latestMessageContent(PromptVersionDef version) {
        if (version.messages().isEmpty()) return null;
        var lastMsg = version.messages().get(version.messages().size() - 1);
        return lastMsg.get("content");
    }

    /** PUT a single prompt version and return the created/replaced Prompt. */
    private static dev.braintrust.openapi.model.Prompt putPrompt(
            PromptsApi promptsApi, UUID projectId, String promptName, PromptVersionDef version) {
        // Build chat messages
        var chatMessages = new ArrayList<ChatCompletionMessageParam>();
        for (var msg : version.messages()) {
            String role = msg.get("role");
            String content = msg.get("content");
            switch (role) {
                case "system" -> {
                    var sysContent = new SystemContent(SystemContent.SchemaType.Text, content);
                    var sysMsg =
                            new dev.braintrust.openapi.model.System()
                                    .role(dev.braintrust.openapi.model.System.RoleEnum.SYSTEM)
                                    .content(sysContent);
                    chatMessages.add(
                            new ChatCompletionMessageParam(
                                    ChatCompletionMessageParam.SchemaType.System, sysMsg));
                }
                case "user" -> {
                    var userContent = new UserContent(UserContent.SchemaType.Text, content);
                    var userMsg =
                            new ChatMessageUser()
                                    .role(ChatMessageUser.RoleEnum.USER)
                                    .content(userContent);
                    chatMessages.add(
                            new ChatCompletionMessageParam(
                                    ChatCompletionMessageParam.SchemaType.ChatMessageUser,
                                    userMsg));
                }
                default -> throw new IllegalArgumentException("Unsupported role: " + role);
            }
        }

        var chat = new Chat().type(Chat.TypeEnum.CHAT).messages(chatMessages);
        var promptBlock = new PromptBlockDataNullish(PromptBlockDataNullish.SchemaType.Chat, chat);

        var promptDataBuilder = new PromptDataNullish().prompt(promptBlock);

        // Add model options if specified
        if (version.model() != null) {
            var modelParams =
                    new ModelParams(
                            ModelParams.SchemaType.OpenAIModelParams,
                            new OpenAIModelParams().temperature(java.math.BigDecimal.ZERO));
            var options = new PromptOptionsNullish().model(version.model()).params(modelParams);
            promptDataBuilder.options(options);
        }

        var createPrompt =
                new CreatePrompt()
                        .projectId(projectId)
                        .name(promptName)
                        .slug(promptName)
                        .tags(List.of(TEST_HARNESS_CREATED_TAG))
                        .promptData(promptDataBuilder);

        return promptsApi.putPrompt(createPrompt);
    }

    /**
     * flush all pending spans and return all spans which have been exported so far
     *
     * <p>repeat the process until the number of exported spans equals or exceeds `minSpanCount`
     */
    @SneakyThrows
    public List<SpanData> awaitExportedSpans(int minSpanCount) {
        return spanExporter.getFinishedSpanItems(minSpanCount);
    }

    public static VCR.VcrMode getVcrMode() {
        return vcr.getMode();
    }

    private static String getEnv(String envarName, String defaultValue) {
        var envar = System.getenv(envarName);
        return envar == null ? defaultValue : envar;
    }
}
