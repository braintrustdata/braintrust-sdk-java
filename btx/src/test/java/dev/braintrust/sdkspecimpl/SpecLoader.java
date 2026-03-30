package dev.braintrust.sdkspecimpl;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.AbstractConstruct;
import org.yaml.snakeyaml.constructor.Constructor;
import org.yaml.snakeyaml.nodes.Node;
import org.yaml.snakeyaml.nodes.ScalarNode;
import org.yaml.snakeyaml.nodes.SequenceNode;
import org.yaml.snakeyaml.nodes.Tag;

/**
 * Loads LLM span spec YAML files from btx/spec/llm_span/ and parses them into {@link LlmSpanSpec}
 * objects.
 *
 * <p>Handles the custom YAML tags used in the spec files:
 *
 * <ul>
 *   <li>{@code !fn <name>} - a named predicate (e.g. {@code is_non_negative_number})
 *   <li>{@code !starts_with "prefix"} - asserts the value starts with a string
 *   <li>{@code !or [...]} - asserts the value matches at least one of a list of structures
 * </ul>
 */
public class SpecLoader {

    private static final String SPEC_ROOT = "btx/spec/llm_span";

    /**
     * The clients supported by this Java runner, keyed by provider name. Each entry is the list of
     * client identifiers that will be tested for that provider. When a provider is not listed here,
     * it defaults to a single client whose name matches the provider (e.g. {@code "anthropic"}).
     */
    static final Map<String, List<String>> CLIENTS_BY_PROVIDER =
            Map.of(
                    "openai", List.of("openai", "langchain-openai", "springai-openai"),
                    "anthropic", List.of("anthropic", "springai-anthropic"));

    /**
     * Returns the clients to test for the given provider. Defaults to {@code [providerName]} if the
     * provider is not explicitly listed in {@link #CLIENTS_BY_PROVIDER}.
     */
    public static List<String> clientsForProvider(String provider) {
        return CLIENTS_BY_PROVIDER.getOrDefault(provider, List.of(provider));
    }

    /**
     * Load all LLM span specs that include "java" in their enabled_runners list, expanded into one
     * {@link LlmSpanSpec} per supported client for the spec's provider.
     */
    public static List<LlmSpanSpec> loadAll() throws IOException {
        Path root = Paths.get(SPEC_ROOT);
        List<LlmSpanSpec> specs = new ArrayList<>();
        try (Stream<Path> paths = Files.walk(root)) {
            paths.filter(p -> p.toString().endsWith(".yaml"))
                    .sorted()
                    .forEach(
                            path -> {
                                try {
                                    specs.addAll(load(path));
                                } catch (IOException e) {
                                    throw new RuntimeException("Failed to load spec: " + path, e);
                                }
                            });
        }
        return specs;
    }

    private static boolean isEnabledForJava(LlmSpanSpec spec) {
        if (spec.enabledRunners() == null) {
            return true; // no filter means all runners
        }
        return spec.enabledRunners().contains("java");
    }

    /** Load a YAML file and expand it into one {@link LlmSpanSpec} per supported client. */
    static List<LlmSpanSpec> load(Path path) throws IOException {
        Yaml yaml = buildYaml();
        try (InputStream is = Files.newInputStream(path)) {
            @SuppressWarnings("unchecked")
            Map<String, Object> raw = yaml.load(is);
            // Build a representative spec first to check enabled_runners
            String provider = (String) raw.get("provider");
            LlmSpanSpec representative = LlmSpanSpec.fromMap(raw, path.toString(), provider);
            if (!isEnabledForJava(representative)) {
                return List.of();
            }
            return clientsForProvider(provider).stream()
                    .map(client -> LlmSpanSpec.fromMap(raw, path.toString(), client))
                    .toList();
        }
    }

    /** Returns true if any message in the request has non-string (e.g. multipart/image) content. */
    @SuppressWarnings("unchecked")
    private static boolean hasNonStringMessageContent(Map<String, Object> req) {
        Object messages = req.get("messages");
        if (!(messages instanceof List)) return false;
        for (Map<String, Object> msg : (List<Map<String, Object>>) messages) {
            Object content = msg.get("content");
            if (content != null && !(content instanceof String)) return true;
        }
        return false;
    }

    private static Yaml buildYaml() {
        LoaderOptions opts = new LoaderOptions();
        opts.setTagInspector(tag -> true); // allow all custom tags
        return new Yaml(new SpecConstructor(opts));
    }

    /** SnakeYAML Constructor that handles the custom spec tags. */
    private static class SpecConstructor extends Constructor {
        SpecConstructor(LoaderOptions opts) {
            super(Object.class, opts);
            // !fn <name>  →  FnMatcher(name)
            yamlConstructors.put(
                    new Tag("!fn"),
                    new AbstractConstruct() {
                        @Override
                        public Object construct(Node node) {
                            String name = ((ScalarNode) node).getValue().trim();
                            return new SpecMatcher.FnMatcher(name);
                        }
                    });
            // !starts_with "prefix"  →  StartsWithMatcher(prefix)
            yamlConstructors.put(
                    new Tag("!starts_with"),
                    new AbstractConstruct() {
                        @Override
                        public Object construct(Node node) {
                            String prefix = ((ScalarNode) node).getValue().trim();
                            // strip surrounding quotes if present
                            if (prefix.startsWith("\"") && prefix.endsWith("\"")) {
                                prefix = prefix.substring(1, prefix.length() - 1);
                            }
                            return new SpecMatcher.StartsWithMatcher(prefix);
                        }
                    });
            // !or [...]  →  OrMatcher(alternatives)
            yamlConstructors.put(
                    new Tag("!or"),
                    new AbstractConstruct() {
                        @Override
                        public Object construct(Node node) {
                            SequenceNode seq = (SequenceNode) node;
                            List<Object> alternatives = new ArrayList<>();
                            for (Node item : seq.getValue()) {
                                alternatives.add(constructObject(item));
                            }
                            return new SpecMatcher.OrMatcher(alternatives);
                        }
                    });
        }
    }
}
