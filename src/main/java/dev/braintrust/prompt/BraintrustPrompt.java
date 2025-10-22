package dev.braintrust.prompt;

import dev.braintrust.api.BraintrustApiClient;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class BraintrustPrompt {
    private static final Pattern MUSTACHE_PATTERN = Pattern.compile("\\{\\{([^}]+)\\}\\}");

    private final BraintrustApiClient.Prompt apiPrompt;
    private final Map<String, String> defaults;

    public BraintrustPrompt(BraintrustApiClient.Prompt apiPrompt) {
        this(apiPrompt, Map.of());
    }

    public BraintrustPrompt(BraintrustApiClient.Prompt apiPrompt, Map<String, String> defaults) {
        this.apiPrompt = apiPrompt;
        this.defaults = defaults;
    }

    public List<Map<String, Object>> renderMessages(Map<String, String> parameters) {
        // get promptData->prompt->messages
        Map<String, Object> promptData = (Map<String, Object>) apiPrompt.promptData().prompt();
        List<Map<String, Object>> messages = (List<Map<String, Object>>) promptData.get("messages");

        if (messages == null) {
            throw new RuntimeException("No messages found in prompt data");
        }

        Set<String> usedParameters = new HashSet<>();
        List<Map<String, Object>> renderedMessages = new ArrayList<>();

        for (Map<String, Object> message : messages) {
            Map<String, Object> renderedMessage = new HashMap<>(message);
            String content = (String) message.get("content");

            if (content != null) {
                String renderedContent = renderTemplate(content, parameters, usedParameters);
                renderedMessage.put("content", renderedContent);
            }

            renderedMessages.add(renderedMessage);
        }

        // Check if all parameters were used
        Set<String> unusedParameters = new HashSet<>(parameters.keySet());
        unusedParameters.removeAll(usedParameters);
        if (!unusedParameters.isEmpty()) {
            throw new RuntimeException("Unused parameters: " + unusedParameters);
        }

        return renderedMessages;
    }

    public Map<String, Object> getOptions() {
        // get map in promptData->options and merge with promptData->options->params
        Map<String, Object> options = (Map<String, Object>) apiPrompt.promptData().options();

        if (options == null) {
            return Map.of();
        }

        Map<String, Object> result = new HashMap<>();

        // Add all top-level options except "params"
        for (Map.Entry<String, Object> entry : options.entrySet()) {
            if (!"params".equals(entry.getKey())) {
                result.put(entry.getKey(), entry.getValue());
            }
        }

        // Merge in the params
        Map<String, Object> params = (Map<String, Object>) options.get("params");
        if (params != null) {
            result.putAll(params);
        }

        // Apply defaults for any values not already set
        for (Map.Entry<String, String> defaultEntry : this.defaults.entrySet()) {
            if (!result.containsKey(defaultEntry.getKey())) {
                result.put(defaultEntry.getKey(), defaultEntry.getValue());
            }
        }

        return result;
    }

    private String renderTemplate(
            String template, Map<String, String> parameters, Set<String> usedParameters) {
        Matcher matcher = MUSTACHE_PATTERN.matcher(template);
        StringBuffer result = new StringBuffer();

        while (matcher.find()) {
            String paramName = matcher.group(1);
            String paramValue = parameters.get(paramName);

            if (paramValue == null) {
                throw new RuntimeException("Missing parameter: " + paramName);
            }

            usedParameters.add(paramName);
            matcher.appendReplacement(result, Matcher.quoteReplacement(paramValue));
        }

        matcher.appendTail(result);
        return result.toString();
    }
}
