package dev.braintrust.examples;

import com.google.genai.Client;
import dev.braintrust.Braintrust;
import dev.braintrust.config.BraintrustConfig;
import dev.braintrust.instrumentation.awsbedrock.v2_30_0.BraintrustAWSBedrock;
import dev.braintrust.instrumentation.genai.BraintrustGenAI;
import dev.braintrust.instrumentation.springai.v1_0_0.BraintrustSpringAI;
import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Scope;
import java.util.ArrayList;
import java.util.List;
import org.springframework.ai.anthropic.AnthropicChatModel;
import org.springframework.ai.anthropic.AnthropicChatOptions;
import org.springframework.ai.anthropic.api.AnthropicApi;
import org.springframework.ai.bedrock.converse.BedrockChatOptions;
import org.springframework.ai.bedrock.converse.BedrockProxyChatModel;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.google.genai.GoogleGenAiChatModel;
import org.springframework.ai.google.genai.GoogleGenAiChatOptions;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.http.client.HttpClientAutoConfiguration;
import org.springframework.boot.autoconfigure.web.client.RestClientAutoConfiguration;
import org.springframework.context.annotation.Bean;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.bedrockruntime.BedrockRuntimeClient;

/** Spring Boot application demonstrating Braintrust + Spring AI 1.x integration */
@SpringBootApplication(
        // NOTE: these excludes are specific to the Braintrust examples project to play nice with
        // other examples' classpaths. Excludes are not required for production spring apps
        exclude = {HttpClientAutoConfiguration.class, RestClientAutoConfiguration.class})
public class SpringAI1Example {

    public static void main(String[] args) {
        var app = new SpringApplication(SpringAI1Example.class);
        app.setWebApplicationType(org.springframework.boot.WebApplicationType.NONE);
        app.run(args);
    }

    @Bean
    public CommandLineRunner run(List<ChatModel> chatModels, Tracer tracer, Braintrust braintrust) {
        return args -> {
            Span rootSpan = tracer.spanBuilder("spring-ai1-example").startSpan();
            try (Scope scope = rootSpan.makeCurrent()) {
                System.out.println("\n=== Running Spring Boot Example ===\n");

                var prompt = new Prompt("what's the name of the most popular java DI framework?");

                System.out.println("~~~ SPRING AI CHAT RESPONSES:");
                for (var model : chatModels) {
                    // Catch per-model so one provider failing (e.g. a bad API key) prints a full
                    // stack trace and lets the remaining models still run — otherwise Spring Boot
                    // swallows the exception behind its generic startup-failure message.
                    try {
                        var response = model.call(prompt);
                        System.out.println(
                                model.getClass().getSimpleName()
                                        + ": "
                                        + response.getResult().getOutput().getText());
                    } catch (Exception e) {
                        System.err.println(model.getClass().getSimpleName() + " call failed:");
                        e.printStackTrace();
                    }
                }
                System.out.println();
            } finally {
                rootSpan.end();
            }

            var url =
                    braintrust.projectUri()
                            + "/logs?r=%s&s=%s"
                                    .formatted(
                                            rootSpan.getSpanContext().getTraceId(),
                                            rootSpan.getSpanContext().getSpanId());

            System.out.println(
                    "\n  Example complete! View your data in Braintrust: %s\n".formatted(url));
        };
    }

    @Bean
    public List<ChatModel> chatModels(OpenTelemetry openTelemetry) {
        var models = new ArrayList<ChatModel>();

        if (System.getenv("OPENAI_API_KEY") != null) {
            models.add(
                    BraintrustSpringAI.wrap(
                                    openTelemetry,
                                    OpenAiChatModel.builder()
                                            .openAiApi(
                                                    OpenAiApi.builder()
                                                            .apiKey(System.getenv("OPENAI_API_KEY"))
                                                            .build())
                                            .defaultOptions(
                                                    OpenAiChatOptions.builder()
                                                            .model("gpt-4o-mini")
                                                            .temperature(0.0)
                                                            .maxTokens(50)
                                                            .build()))
                            .build());
        }

        if (System.getenv("ANTHROPIC_API_KEY") != null) {
            models.add(
                    BraintrustSpringAI.wrap(
                                    openTelemetry,
                                    AnthropicChatModel.builder()
                                            .anthropicApi(
                                                    AnthropicApi.builder()
                                                            .apiKey(
                                                                    System.getenv(
                                                                            "ANTHROPIC_API_KEY"))
                                                            .build())
                                            .defaultOptions(
                                                    AnthropicChatOptions.builder()
                                                            .model("claude-haiku-4-5")
                                                            .temperature(0.0)
                                                            .maxTokens(50)
                                                            .build()))
                            .build());
        }

        if (System.getenv("GOOGLE_API_KEY") != null || System.getenv("GEMINI_API_KEY") != null) {
            models.add(
                    GoogleGenAiChatModel.builder()
                            .genAiClient(BraintrustGenAI.wrap(openTelemetry, new Client.Builder()))
                            .defaultOptions(
                                    GoogleGenAiChatOptions.builder()
                                            .model("gemini-3.1-flash-lite")
                                            .temperature(0.0)
                                            .maxOutputTokens(50)
                                            .build())
                            .build());
        }

        if (System.getenv("AWS_ACCESS_KEY_ID") != null
                && System.getenv("AWS_SECRET_ACCESS_KEY") != null) {
            var bedrockClient =
                    BraintrustAWSBedrock.wrap(openTelemetry, BedrockRuntimeClient.builder())
                            .build();
            models.add(
                    BedrockProxyChatModel.builder()
                            .bedrockRuntimeClient(bedrockClient)
                            .region(Region.US_EAST_1)
                            .defaultOptions(
                                    BedrockChatOptions.builder()
                                            // .model("us.anthropic.claude-haiku-4-5-20251001-v1:0")
                                            .model("us.amazon.nova-lite-v1:0")
                                            .temperature(0.0)
                                            .maxTokens(50)
                                            .build())
                            .build());
        }

        if (models.isEmpty()) {
            System.err.println(
                    "\nWARNING: No API keys found. Set at least one of: OPENAI_API_KEY,"
                            + " ANTHROPIC_API_KEY, GOOGLE_API_KEY/GEMINI_API_KEY, or"
                            + " AWS_ACCESS_KEY_ID+AWS_SECRET_ACCESS_KEY\n");
        }

        return models;
    }

    @Bean
    public Braintrust braintrust() {
        return Braintrust.get(BraintrustConfig.fromEnvironment());
    }

    @Bean
    public OpenTelemetry openTelemetry(Braintrust braintrust) {
        return braintrust.openTelemetryCreate();
    }

    @Bean
    public Tracer tracer(OpenTelemetry openTelemetry) {
        return openTelemetry.getTracer("spring-ai-instrumentation");
    }
}
