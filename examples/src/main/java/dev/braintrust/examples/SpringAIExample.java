package dev.braintrust.examples;

import com.google.genai.Client;
import dev.braintrust.Braintrust;
import dev.braintrust.config.BraintrustConfig;
import dev.braintrust.instrumentation.genai.BraintrustGenAI;
import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Scope;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.google.genai.GoogleGenAiChatModel;
import org.springframework.ai.google.genai.GoogleGenAiChatOptions;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.http.client.HttpClientAutoConfiguration;
import org.springframework.boot.autoconfigure.web.client.RestClientAutoConfiguration;
import org.springframework.context.annotation.Bean;

/** Spring Boot application demonstrating Braintrust + Spring AI integration */
@SpringBootApplication(
        // NOTE: these excludes are specific to the Braintrust examples project to play nice with
        // other examples' classpaths. Excludes are not required for production spring apps
        exclude = {HttpClientAutoConfiguration.class, RestClientAutoConfiguration.class})
public class SpringAIExample {

    public static void main(String[] args) {
        SpringApplication.run(SpringAIExample.class, args);
    }

    @Bean
    public CommandLineRunner run(ChatModel chatModel, Tracer tracer, Braintrust braintrust) {
        return args -> {
            Span rootSpan = tracer.spanBuilder("spring-ai-example").startSpan();
            try (Scope scope = rootSpan.makeCurrent()) {
                System.out.println("\n=== Running Spring Boot Example ===\n");

                // Make a simple chat call
                var prompt = new Prompt("what's the name of the most popular java DI framework?");
                var response = chatModel.call(prompt);

                System.out.println(
                        "~~~ SPRING AI CHAT RESPONSE: %s\n"
                                .formatted(response.getResult().getOutput().getText()));
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

    @Bean
    public String aiProvider() {
        // return "openai";
        // return "anthropic";
        return "google";
    }

    @Bean
    public ChatModel chatModel(String aiProvider, OpenTelemetry openTelemetry) {
        return switch (aiProvider) {
            case "openai", "anthropic" -> {
                throw new RuntimeException("TODO: " + aiProvider);
            }
            case "google" -> {
                if (null == System.getenv("GOOGLE_API_KEY")
                        && null == System.getenv("GEMINI_API_KEY")) {
                    System.err.println(
                            "\n"
                                + "WARNING: Neither GOOGLE_API_KEY nor GEMINI_API_KEY found. This"
                                + " example will likely fail.\n"
                                + "Set either: export GOOGLE_API_KEY='your-key' (recommended) or"
                                + " export GEMINI_API_KEY='your-key'\n");
                }
                Client genAIClient = BraintrustGenAI.wrap(openTelemetry, new Client.Builder());
                yield GoogleGenAiChatModel.builder()
                        .genAiClient(genAIClient)
                        .defaultOptions(
                                GoogleGenAiChatOptions.builder()
                                        .model("gemini-2.0-flash-lite")
                                        .temperature(0.0)
                                        .maxOutputTokens(50)
                                        .build())
                        .build();
            }
            default -> throw new RuntimeException("unsupported provider: " + aiProvider);
        };
    }
}
