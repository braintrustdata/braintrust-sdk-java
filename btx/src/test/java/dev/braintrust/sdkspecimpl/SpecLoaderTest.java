package dev.braintrust.sdkspecimpl;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Covers spec expansion — in particular that uncovered specs are never silently dropped. */
class SpecLoaderTest {

    private static final String UNKNOWN_PROVIDER_SPEC =
            """
            name: chat
            type: llm_span_test
            provider: nova
            endpoint: /v1/nova/chat
            requests:
              - model: nova-1
                messages:
                  - role: user
                    content: hello
            expected_brainstore_spans:
              - metadata:
                  provider: nova
            """;

    @Test
    void uncoveredSpecBecomesUnsupportedSentinel(@TempDir Path dir) throws Exception {
        Path spec = dir.resolve("chat.yaml");
        Files.writeString(spec, UNKNOWN_PROVIDER_SPEC);

        List<LlmSpanSpec> loaded = SpecLoader.load(spec);

        assertEquals(1, loaded.size(), "uncovered spec must not be silently dropped");
        LlmSpanSpec sentinel = loaded.get(0);
        assertEquals(SpecClientRegistry.UNSUPPORTED_CLIENT_ID, sentinel.client());
        String message = SpecClientRegistry.unsupportedSpecMessage(sentinel);
        assertTrue(message.contains("provider=nova"), message);
        assertTrue(message.contains("nova/chat"), message);
    }

    @Test
    void knownUnsupportedSpecIsSkipped(@TempDir Path dir) throws Exception {
        Path spec = dir.resolve("chat.yaml");
        Files.writeString(spec, UNKNOWN_PROVIDER_SPEC);

        List<LlmSpanSpec> loaded =
                SpecLoader.load(spec, s -> SpecClientRegistry.specKey(s).equals("nova/chat"));

        assertTrue(loaded.isEmpty(), "explicitly skipped spec should load to nothing");
    }

    @Test
    void coveredSpecExpandsPerSupportingClient(@TempDir Path dir) throws Exception {
        Path spec = dir.resolve("completions.yaml");
        Files.writeString(
                spec,
                """
                name: completions
                type: llm_span_test
                provider: openai
                endpoint: /v1/chat/completions
                requests:
                  - model: gpt-4o-mini
                    messages:
                      - role: user
                        content: hello
                expected_brainstore_spans:
                  - metadata:
                      provider: openai
                """);

        List<LlmSpanSpec> loaded = SpecLoader.load(spec);

        List<String> clients = loaded.stream().map(LlmSpanSpec::client).toList();
        assertTrue(clients.contains("openai"), "raw client should cover chat completions");
        assertTrue(clients.contains("springai2-openai"), "springai2 should cover chat completions");
        assertFalse(
                clients.contains(SpecClientRegistry.UNSUPPORTED_CLIENT_ID),
                "covered specs must not produce sentinels");
    }
}
