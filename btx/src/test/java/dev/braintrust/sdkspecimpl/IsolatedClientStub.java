package dev.braintrust.sdkspecimpl;

import java.util.Optional;
import java.util.Set;

/**
 * Registry-side declaration of a {@link SpecClient} whose implementation lives on an isolated
 * classpath. Carries identity and spec filtering; execution is provided by {@link
 * IsolatedClientDelegate} loading {@code isolation().implClassName()} in a child-first classloader.
 *
 * @param endpoints the spec endpoints this client can execute
 * @param skippedSpecs spec names the implementation cannot express yet (visible skip list)
 */
record IsolatedClientStub(
        String id,
        String provider,
        Set<String> endpoints,
        Set<String> skippedSpecs,
        SpecClient.Isolation iso)
        implements SpecClient {

    @Override
    public boolean supports(LlmSpanSpec spec) {
        return endpoints.contains(spec.endpoint()) && !skippedSpecs.contains(spec.name());
    }

    @Override
    public Optional<Isolation> isolation() {
        return Optional.of(iso);
    }

    @Override
    public void executeSpec(LlmSpanSpec spec, SpecClientContext ctx) {
        throw new IllegalStateException(
                "Stub for isolated client '" + id + "' should execute via IsolatedClientDelegate");
    }
}
