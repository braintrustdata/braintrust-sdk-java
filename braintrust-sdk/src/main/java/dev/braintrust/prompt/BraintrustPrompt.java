package dev.braintrust.prompt;

import com.github.mustachejava.DefaultMustacheFactory;
import com.github.mustachejava.Mustache;
import com.github.mustachejava.MustacheException;
import dev.braintrust.api.BraintrustApiClient;
import java.io.StringReader;
import java.io.StringWriter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class BraintrustPrompt {
    private final BraintrustApiClient.Prompt apiPrompt;
    private final Map<String, String> defaults;

    public BraintrustPrompt(BraintrustApiClient.Prompt apiPrompt) {
        this(apiPrompt, Map.of());
    }

    public BraintrustPrompt(BraintrustApiClient.Prompt apiPrompt, Map<String, String> defaults) {
        this.apiPrompt = apiPrompt;
        this.defaults = defaults;
    }

    public List<Map<String, Object>> renderMessages(Map<String, Object> parameters) {
        // get promptData->prompt->messages
        Map<String, Object> promptData = (Map<String, Object>) apiPrompt.promptData().prompt();
        List<Map<String, Object>> messages = (List<Map<String, Object>>) promptData.get("messages");

        if (messages == null) {
            throw new RuntimeException("No messages found in prompt data");
        }

        List<Map<String, Object>> renderedMessages = new ArrayList<>();

        for (Map<String, Object> message : messages) {
            Map<String, Object> renderedMessage = new HashMap<>(message);
            String content = (String) message.get("content");

            if (content != null) {
                String renderedContent = renderTemplate(content, parameters);
                renderedMessage.put("content", renderedContent);
            }

            renderedMessages.add(renderedMessage);
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

    private String renderTemplate(String template, Map<String, Object> parameters) {
        try {
            DefaultMustacheFactory factory = new DefaultMustacheFactory();
            Mustache mustache = factory.compile(new StringReader(template), "template");
            StringWriter writer = new StringWriter();
            mustache.execute(writer, parameters);
            writer.flush();
            return writer.toString();
        } catch (MustacheException e) {
            // If the template is malformed, just return it as-is
            return template;
        } catch (Exception e) {
            if (e instanceof RuntimeException) {
                throw (RuntimeException) e;
            }
            throw new RuntimeException("Failed to render template", e);
        }
    }
}
