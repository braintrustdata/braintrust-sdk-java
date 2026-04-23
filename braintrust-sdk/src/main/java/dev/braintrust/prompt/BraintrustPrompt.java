package dev.braintrust.prompt;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.mustachejava.DefaultMustacheFactory;
import com.github.mustachejava.Mustache;
import com.github.mustachejava.MustacheException;
import dev.braintrust.openapi.JSON;
import dev.braintrust.openapi.model.Chat;
import dev.braintrust.openapi.model.ChatCompletionMessageParam;
import dev.braintrust.openapi.model.ModelParams;
import dev.braintrust.openapi.model.PromptBlockDataNullish;
import dev.braintrust.openapi.model.PromptDataNullish;
import dev.braintrust.openapi.model.PromptOptionsNullish;
import java.io.StringReader;
import java.io.StringWriter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import javax.annotation.Nullable;

public class BraintrustPrompt {
    private static final ObjectMapper MAPPER = new JSON().getMapper();

    private final PromptDataNullish promptData;
    private final Map<String, String> defaults;

    public BraintrustPrompt(PromptDataNullish promptData) {
        this(promptData, Map.of());
    }

    public BraintrustPrompt(PromptDataNullish promptData, Map<String, String> defaults) {
        this.promptData = promptData;
        this.defaults = defaults;
    }

    public List<Map<String, Object>> renderMessages(Map<String, Object> parameters) {
        if (promptData.getPrompt() == null) {
            throw new RuntimeException("No prompt block found in prompt data");
        }
        PromptBlockDataNullish blockData = promptData.getPrompt();
        Object blockInstance = blockData.getActualInstance();
        if (!(blockInstance instanceof Chat chat)) {
            throw new RuntimeException("Only chat prompts are currently supported");
        }

        List<Map<String, Object>> renderedMessages = new ArrayList<>();
        for (ChatCompletionMessageParam param : chat.getMessages()) {
            final String role;
            final String content =
                    switch (param.getVariantType()) {
                        case System -> {
                            var sys = param.getSystemInstance();
                            role = "system";
                            yield sys.getContent() != null
                                    ? extractStringContent(sys.getContent().getActualInstance())
                                    : null;
                        }
                        case ChatMessageUser -> {
                            var user = param.getChatMessageUserInstance();
                            role = "user";
                            yield user.getContent() != null
                                    ? extractStringContent(user.getContent().getActualInstance())
                                    : null;
                        }
                        case Assistant -> {
                            var asst = param.getAssistantInstance();
                            role = "assistant";
                            yield asst.getContent() != null
                                    ? extractStringContent(asst.getContent().getActualInstance())
                                    : null;
                        }
                        case Tool -> {
                            var tool = param.getToolInstance();
                            role = "tool";
                            yield tool.getContent() != null
                                    ? extractStringContent(tool.getContent().getActualInstance())
                                    : null;
                        }
                        case InlineFunctionRef -> {
                            // function-role messages have an index reference, not text content
                            var fn = param.getInlineFunctionRefInstance();
                            role = "function";
                            yield null;
                        }
                        case Developer -> {
                            var dev = param.getDeveloperInstance();
                            role = "developer";
                            yield dev.getContent() != null
                                    ? extractStringContent(dev.getContent().getActualInstance())
                                    : null;
                        }
                        case Fallback -> {
                            var fb = param.getFallbackInstance();
                            role = fb.getRole() != null ? fb.getRole().getValue() : "unknown";
                            yield fb.getContent();
                        }
                    };

            Map<String, Object> rendered = new HashMap<>();
            rendered.put("role", role);
            if (content != null) {
                rendered.put("content", renderTemplate(content, parameters));
            }
            renderedMessages.add(rendered);
        }
        return renderedMessages;
    }

    public Map<String, Object> getOptions() {
        if (promptData.getOptions() == null) {
            return applyDefaults(Map.of());
        }

        PromptOptionsNullish opts = promptData.getOptions();
        Map<String, Object> result = new HashMap<>();

        if (opts.getModel() != null) {
            result.put("model", opts.getModel());
        }
        if (opts.getPosition() != null) {
            result.put("position", opts.getPosition());
        }

        // Flatten params (ModelParams anyOf: OpenAIModelParams | AnthropicModelParams | ...)
        // into the result map via Jackson, mirroring the old behaviour of merging
        // promptData.options.params into the top level.
        ModelParams modelParams = opts.getParams();
        if (modelParams != null && modelParams.getActualInstance() != null) {
            @SuppressWarnings("unchecked")
            Map<String, Object> paramsMap =
                    MAPPER.convertValue(modelParams.getActualInstance(), Map.class);
            result.putAll(paramsMap);
        }

        return applyDefaults(result);
    }

    private Map<String, Object> applyDefaults(Map<String, Object> base) {
        Map<String, Object> result = new HashMap<>(base);
        for (Map.Entry<String, String> entry : defaults.entrySet()) {
            result.putIfAbsent(entry.getKey(), entry.getValue());
        }
        return result;
    }

    /**
     * Extract a String from an anyOf [String, List&lt;ChatCompletionContentPart*&gt;] content
     * instance. Only the String variant is supported for Mustache rendering; list-form content
     * (e.g. vision messages) is returned as-is via toString.
     */
    private String extractStringContent(@Nullable Object contentInstance) {
        if (contentInstance instanceof String s) {
            return s;
        }
        return contentInstance != null ? contentInstance.toString() : null;
    }

    private String renderTemplate(String template, Map<String, Object> parameters) {
        try {
            DefaultMustacheFactory factory = new DefaultMustacheFactory();
            Mustache mustache = factory.compile(new StringReader(template), "template");
            StringWriter writer = new StringWriter();
            mustache.execute(writer, parameters);
            writer.flush();
            return writer.toString();
        } catch (MustacheException e) {
            return template;
        } catch (Exception e) {
            if (e instanceof RuntimeException re) {
                throw re;
            }
            throw new RuntimeException("Failed to render template", e);
        }
    }
}
