package dev.braintrust.sdkspecimpl;

import dev.braintrust.sdkspecimpl.clients.AnthropicSpecClient;
import dev.braintrust.sdkspecimpl.clients.BedrockSpecClient;
import dev.braintrust.sdkspecimpl.clients.GoogleSpecClient;
import dev.braintrust.sdkspecimpl.clients.LangChainOpenAiSpecClient;
import dev.braintrust.sdkspecimpl.clients.OpenAiSpecClient;
import dev.braintrust.sdkspecimpl.clients.SpringAi1AnthropicSpecClient;
import dev.braintrust.sdkspecimpl.clients.SpringAi1OpenAiSpecClient;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.Tracer;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

/**
 * The registry of all {@link SpecClient} modules known to this runner. Clients that declare {@link
 * SpecClient#isolation()} are wrapped in an {@link IsolatedClientDelegate} at registration time.
 */
public final class SpecClientRegistry {

    /**
     * Sentinel client id for specs no registered client supports. The loader emits these instead of
     * silently dropping the spec, and the runner turns each into a failing test — so a new
     * provider/endpoint in a braintrust-spec bump can never lose coverage unnoticed.
     */
    public static final String UNSUPPORTED_CLIENT_ID = "unsupported";

    /**
     * Specs this runner deliberately does not cover, keyed {@code provider/name} (the base of
     * {@link LlmSpanSpec#displayName()}). Listing a spec here is the explicit opt-out: it loads to
     * nothing instead of producing a failing "unsupported" test. Prefer registering a client; reach
     * for this only when no Java client can express the spec at all.
     */
    private static final Set<String> KNOWN_UNSUPPORTED_SPECS =
            Set.of(
                    // Brand-new Google features added in braintrust-spec v0.0.10. The genai
                    // instrumentation / GoogleSpecClient don't implement them yet (thinking,
                    // grounding tools, response modalities, per-modality prompt-token extraction),
                    // so they're marked unsupported rather than half-implemented. GoogleSpecClient
                    // also filters these out of supports(); see its UNSUPPORTED_SPECS.
                    "google/thinking",
                    "google/grounding",
                    "google/streaming",
                    "google/generated_audio_usage",
                    "google/generated_image_usage",
                    "google/attachments",
                    // Google Interactions API (/v1/interactions) — no Java client exists.
                    "google/interactions",
                    "google/interactions_streaming");

    private static final List<SpecClient> CLIENTS =
            Stream.of(
                            (SpecClient) new OpenAiSpecClient(),
                            new LangChainOpenAiSpecClient(),
                            new SpringAi1OpenAiSpecClient(),
                            new AnthropicSpecClient(),
                            new SpringAi1AnthropicSpecClient(),
                            new BedrockSpecClient(),
                            new GoogleSpecClient(),
                            new IsolatedClientStub(
                                    "springai2-openai",
                                    "openai",
                                    Set.of("/v1/chat/completions"),
                                    // Spring AI 2.0's OpenAI media mapping supports image/audio
                                    // content parts only — the attachments spec's PDF `file` part
                                    // is not expressible through ChatModel.
                                    Set.of("attachments"),
                                    new SpecClient.Isolation(
                                            "btx.springai2.classpath",
                                            "dev.braintrust.sdkspecimpl.springai2.SpringAi2OpenAiSpecClient")),
                            new IsolatedClientStub(
                                    "springai2-anthropic",
                                    "anthropic",
                                    Set.of("/v1/messages"),
                                    // Spec-level cache_control block placement isn't expressible
                                    // via ChatModel messages (Spring AI 2.0 models caching through
                                    // AnthropicCacheOptions instead).
                                    Set.of("prompt_caching_5m", "prompt_caching_1h"),
                                    new SpecClient.Isolation(
                                            "btx.springai2.classpath",
                                            "dev.braintrust.sdkspecimpl.springai2.SpringAi2AnthropicSpecClient")))
                    .map(IsolatedClientDelegate::resolve)
                    .toList();

    /** All clients able to execute the given spec (provider match + {@code supports}). */
    public static List<SpecClient> clientsFor(LlmSpanSpec spec) {
        return CLIENTS.stream()
                .filter(c -> c.provider().equals(spec.provider()) && c.supports(spec))
                .toList();
    }

    /** Whether the spec is on the explicit {@link #KNOWN_UNSUPPORTED_SPECS} skip list. */
    static boolean isKnownUnsupported(LlmSpanSpec spec) {
        return KNOWN_UNSUPPORTED_SPECS.contains(specKey(spec));
    }

    /** The {@code provider/name} key used by {@link #KNOWN_UNSUPPORTED_SPECS}. */
    static String specKey(LlmSpanSpec spec) {
        return spec.provider() + "/" + spec.name();
    }

    /** The client a fully-expanded spec (with its {@code client} id set) belongs to. */
    public static SpecClient clientFor(LlmSpanSpec spec) {
        if (UNSUPPORTED_CLIENT_ID.equals(spec.client())) {
            throw new IllegalStateException(unsupportedSpecMessage(spec));
        }
        return CLIENTS.stream()
                .filter(c -> c.id().equals(spec.client()))
                .findFirst()
                .orElseThrow(
                        () ->
                                new IllegalStateException(
                                        "No registered SpecClient with id '"
                                                + spec.client()
                                                + "'"));
    }

    /** Actionable failure message for a spec no registered client supports. */
    public static String unsupportedSpecMessage(LlmSpanSpec spec) {
        return "No registered SpecClient supports provider="
                + spec.provider()
                + " endpoint="
                + spec.endpoint()
                + " (spec "
                + spec.sourcePath()
                + "). Register a client in SpecClientRegistry, or add '"
                + specKey(spec)
                + "' to SpecClientRegistry.KNOWN_UNSUPPORTED_SPECS to skip it deliberately.";
    }

    /**
     * Execute the spec through its client, wrapped in an OTel root span named after the spec.
     *
     * @return the OTel trace ID of the root span (hex string), which Braintrust stores as {@code
     *     root_span_id}
     */
    public static String execute(LlmSpanSpec spec, SpecClientContext ctx) throws Exception {
        Tracer tracer = ctx.otel().getTracer("btx");
        Span rootSpan = tracer.spanBuilder(spec.name()).startSpan();
        rootSpan.setAttribute("client", spec.client());
        try (var ignored = rootSpan.makeCurrent()) {
            clientFor(spec).executeSpec(spec, ctx);
        } finally {
            rootSpan.end();
        }
        return rootSpan.getSpanContext().getTraceId();
    }

    private SpecClientRegistry() {}
}
