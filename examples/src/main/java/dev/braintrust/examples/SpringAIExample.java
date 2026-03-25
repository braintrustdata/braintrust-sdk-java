package dev.braintrust.examples;

import com.google.genai.Client;
import dev.braintrust.Braintrust;
import dev.braintrust.config.BraintrustConfig;
import dev.braintrust.instrumentation.genai.BraintrustGenAI;
import dev.braintrust.instrumentation.springai.v1_0_0.BraintrustSpringAI;
import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Scope;
import org.springframework.ai.anthropic.AnthropicChatModel;
import org.springframework.ai.anthropic.AnthropicChatOptions;
import org.springframework.ai.anthropic.api.AnthropicApi;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.google.genai.GoogleGenAiChatModel;
import org.springframework.ai.google.genai.GoogleGenAiChatOptions;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.beans.factory.annotation.Qualifier;
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
        var app = new SpringApplication(SpringAIExample.class);
        app.setWebApplicationType(org.springframework.boot.WebApplicationType.NONE);
        app.run(args);
    }

    @Bean
    public CommandLineRunner run(
            @Qualifier("openAIChatModel") ChatModel openAIChatModel,
            @Qualifier("anthropicChatModel") ChatModel anthropicChatModel,
            @Qualifier("googleChatModel") ChatModel googleChatModel,
            Tracer tracer,
            Braintrust braintrust) {
        return args -> {
            Span rootSpan = tracer.spanBuilder("spring-ai-example").startSpan();
            try (Scope scope = rootSpan.makeCurrent()) {
                System.out.println("\n=== Running Spring Boot Example ===\n");

                // Make a simple chat call
                var prompt = new Prompt("what's the name of the most popular java DI framework?");
                var oaiResponse = openAIChatModel.call(prompt);
                var anthropicResponse = anthropicChatModel.call(prompt);
                var googleResponse = googleChatModel.call(prompt);

                System.out.println(
                        "~~~ SPRING AI CHAT RESPONSES: \noat: %s\nanthropic: %s\ngoogle: %s\n"
                                .formatted(
                                        oaiResponse.getResult().getOutput().getText(),
                                        anthropicResponse.getResult().getOutput().getText(),
                                        googleResponse.getResult().getOutput().getText()));
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
    public ChatModel openAIChatModel(OpenTelemetry openTelemetry) {
        if (null == System.getenv("OPENAI_API_KEY")) {
            System.err.println(
                    "\n"
                            + "WARNING: OPENAI_API_KEY not found. This example will likely"
                            + " fail.\n"
                            + "Set it with: export OPENAI_API_KEY='your-key'\n");
        }
        return BraintrustSpringAI.wrap(
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
                .build();
    }

    @Bean
    public ChatModel anthropicChatModel(OpenTelemetry openTelemetry) {
        if (null == System.getenv("ANTHROPIC_API_KEY")) {
            System.err.println(
                    "\n"
                            + "WARNING: ANTHROPIC_API_KEY not found. This example will"
                            + " likely fail.\n"
                            + "Set it with: export ANTHROPIC_API_KEY='your-key'\n");
        }
        return BraintrustSpringAI.wrap(
                        openTelemetry,
                        AnthropicChatModel.builder()
                                .anthropicApi(
                                        AnthropicApi.builder()
                                                .apiKey(System.getenv("ANTHROPIC_API_KEY"))
                                                .build())
                                .defaultOptions(
                                        AnthropicChatOptions.builder()
                                                .model("claude-3-haiku-20240307")
                                                .temperature(0.0)
                                                .maxTokens(50)
                                                .build()))
                .build();
    }

    @Bean
    public ChatModel googleChatModel(OpenTelemetry openTelemetry) {
        if (null == System.getenv("GOOGLE_API_KEY") && null == System.getenv("GEMINI_API_KEY")) {
            System.err.println(
                    "\n"
                            + "WARNING: Neither GOOGLE_API_KEY nor GEMINI_API_KEY found. This"
                            + " example will likely fail.\n"
                            + "Set either: export GOOGLE_API_KEY='your-key' (recommended) or"
                            + " export GEMINI_API_KEY='your-key'\n");
        }
        return GoogleGenAiChatModel.builder()
                .genAiClient(BraintrustGenAI.wrap(openTelemetry, new Client.Builder()))
                .defaultOptions(
                        GoogleGenAiChatOptions.builder()
                                .model("gemini-2.0-flash-lite")
                                .temperature(0.0)
                                .maxOutputTokens(50)
                                .build())
                .build();
    }
}
