package dev.braintrust.sdkspecimpl;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
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
 *   <li>{@code !gen <name>} - a named generator for variable values (e.g. {@code vcr_nonce})
 *   <li>{@code !starts_with "prefix"} - asserts the value starts with a string
 *   <li>{@code !or [...]} - asserts the value matches at least one of a list of structures
 * </ul>
 */
public class SpecLoader {

    /**
     * The spec root directory. Resolved in priority order:
     *
     * <ol>
     *   <li>The {@code btx.spec.root} system property — set by the Gradle {@code test} task to the
     *       output of {@code fetchSpec}, or to {@code $BTX_SPEC_ROOT} when that env var is set.
     *   <li>The fallback {@code btx/spec/llm_span} in-tree path for ad-hoc local runs.
     * </ol>
     *
     * <p>To use a local checkout of braintrust-spec: {@code
     * BTX_SPEC_ROOT=/path/to/spec/test/llm_span ./gradlew btx:test}
     */
    private static final String SPEC_ROOT =
            System.getProperty("btx.spec.root", "btx/spec/llm_span");

    /**
     * The clients supported by this Java runner, keyed by provider name. Each entry is the list of
     * client identifiers that will be tested for that provider. When a provider is not listed here,
     * it defaults to a single client whose name matches the provider (e.g. {@code "anthropic"}).
     */
    static final Map<String, List<String>> CLIENTS_BY_PROVIDER =
            Map.of(
                    "openai", List.of("openai", "langchain-openai", "springai-openai"),
                    "anthropic", List.of("anthropic", "springai-anthropic"),
                    "bedrock", List.of("bedrock"));

    /**
     * Returns the clients to test for the given provider. Defaults to {@code [providerName]} if the
     * provider is not explicitly listed in {@link #CLIENTS_BY_PROVIDER}.
     */
    public static List<String> clientsForProvider(String provider) {
        return CLIENTS_BY_PROVIDER.getOrDefault(provider, List.of(provider));
    }

    /**
     * Load all LLM span specs, expanded into one {@link LlmSpanSpec} per supported client for the
     * spec's provider.
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

    /** Load a YAML file and expand it into one {@link LlmSpanSpec} per supported client. */
    static List<LlmSpanSpec> load(Path path) throws IOException {
        Yaml yaml = buildYaml();
        try (InputStream is = Files.newInputStream(path)) {
            @SuppressWarnings("unchecked")
            Map<String, Object> raw = yaml.load(is);

            // Extract unresolved variables (may contain SpecGenerator markers).
            @SuppressWarnings("unchecked")
            Map<String, Object> unresolvedVars =
                    (Map<String, Object>) raw.getOrDefault("variables", Collections.emptyMap());
            raw.remove("variables");

            String provider = (String) raw.get("provider");
            return clientsForProvider(provider).stream()
                    .map(
                            client -> {
                                // Deep-copy so each client gets its own substituted tree.
                                @SuppressWarnings("unchecked")
                                Map<String, Object> copy = (Map<String, Object>) deepCopy(raw);

                                if (!unresolvedVars.isEmpty()) {
                                    Map<String, Object> resolved =
                                            resolveVariables(unresolvedVars, client);
                                    substituteVariables(copy, resolved);
                                }

                                return LlmSpanSpec.fromMap(copy, path.toString(), client);
                            })
                    .toList();
        }
    }

    /**
     * Recursively deep-copy a parsed YAML structure (Maps, Lists, and leaf values). Leaf values
     * (Strings, Numbers, SpecMatchers, etc.) are shared since they are effectively immutable.
     */
    @SuppressWarnings("unchecked")
    private static Object deepCopy(Object node) {
        if (node instanceof Map) {
            Map<String, Object> map = (Map<String, Object>) node;
            Map<String, Object> copy = new LinkedHashMap<>(map.size());
            for (Map.Entry<String, Object> entry : map.entrySet()) {
                copy.put(entry.getKey(), deepCopy(entry.getValue()));
            }
            return copy;
        } else if (node instanceof List) {
            List<Object> list = (List<Object>) node;
            List<Object> copy = new ArrayList<>(list.size());
            for (Object item : list) {
                copy.add(deepCopy(item));
            }
            return copy;
        }
        return node; // immutable leaf (String, Number, Boolean, SpecMatcher, etc.)
    }

    /**
     * Resolve generator markers in the variables map to concrete string values.
     *
     * <p>Supported generators (via {@code !gen <name>}):
     *
     * <ul>
     *   <li>{@code test_runner_client} — the client name being tested (e.g. {@code "anthropic"})
     *   <li>{@code vcr_nonce} — a random UUID when {@code VCR_MODE=off}, otherwise a fixed string
     *       so that VCR cassette matching still works
     * </ul>
     */
    private static Map<String, Object> resolveVariables(
            Map<String, Object> unresolvedVars, String client) {
        Map<String, Object> resolved = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : unresolvedVars.entrySet()) {
            Object value = entry.getValue();
            if (value instanceof SpecGenerator gen) {
                resolved.put(entry.getKey(), gen.generate(client));
            } else {
                resolved.put(entry.getKey(), value);
            }
        }
        return resolved;
    }

    /**
     * Recursively walk a parsed YAML structure and replace {@code {{key}}} placeholders in every
     * string value with the corresponding entry from {@code variables}.
     */
    @SuppressWarnings("unchecked")
    private static void substituteVariables(Object node, Map<String, Object> variables) {
        if (node instanceof Map) {
            Map<String, Object> map = (Map<String, Object>) node;
            for (Map.Entry<String, Object> entry : map.entrySet()) {
                Object value = entry.getValue();
                if (value instanceof String s) {
                    entry.setValue(replaceVariables(s, variables));
                } else {
                    substituteVariables(value, variables);
                }
            }
        } else if (node instanceof List) {
            List<Object> list = (List<Object>) node;
            for (int i = 0; i < list.size(); i++) {
                Object value = list.get(i);
                if (value instanceof String s) {
                    list.set(i, replaceVariables(s, variables));
                } else {
                    substituteVariables(value, variables);
                }
            }
        }
    }

    /** Replace all {@code {{key}}} occurrences in {@code text} with variable values. */
    private static String replaceVariables(String text, Map<String, Object> variables) {
        for (Map.Entry<String, Object> entry : variables.entrySet()) {
            text = text.replace("{{" + entry.getKey() + "}}", String.valueOf(entry.getValue()));
        }
        return text;
    }

    /**
     * Marker object produced by the {@code !gen} YAML tag. Holds the generator name and resolves to
     * a concrete value when {@link #generate(String)} is called.
     */
    record SpecGenerator(String name) {
        String generate(String client) {
            return switch (name) {
                case "test_runner_client" -> client;
                case "vcr_nonce" -> {
                    String vcrMode =
                            System.getenv().getOrDefault("VCR_MODE", "replay").toUpperCase();
                    yield "OFF".equals(vcrMode) ? UUID.randomUUID().toString() : "vcr-mode";
                }
                default -> throw new IllegalArgumentException("Unknown generator: " + name);
            };
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
            // !gen <name>  →  SpecGenerator(name)
            yamlConstructors.put(
                    new Tag("!gen"),
                    new AbstractConstruct() {
                        @Override
                        public Object construct(Node node) {
                            String name = ((ScalarNode) node).getValue().trim();
                            return new SpecGenerator(name);
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
