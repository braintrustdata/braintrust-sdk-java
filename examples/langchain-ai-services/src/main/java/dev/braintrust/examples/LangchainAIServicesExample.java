package dev.braintrust.examples;

import dev.braintrust.Braintrust;
import dev.braintrust.instrumentation.langchain.v1_14_0.BraintrustLangchain;
import dev.langchain4j.agent.tool.Tool;
import dev.langchain4j.model.openai.OpenAiChatModel;
import dev.langchain4j.model.openai.OpenAiResponsesChatModel;
import dev.langchain4j.service.AiServices;
import io.opentelemetry.api.OpenTelemetry;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Two LangChain4j AI Services agents — one on the OpenAI chat completions API, one on the OpenAI
 * Responses API — traced under a single root span so their subtrees can be compared side by side in
 * Braintrust. {@link OpenAiResponsesChatModel} requires langchain4j >= 1.14.0.
 *
 * <p>The responses agent additionally enables openai's hosted web search tool, which the chat
 * completions API cannot do: it runs server side, so it shows up as a {@code web_search_call} tool
 * span that braintrust derives from the response payload, alongside the {@code @Tool} spans for
 * functions this process executes itself.
 */
public class LangchainAIServicesExample {

    private static final String WEATHER_PROMPT = "is it hotter in Paris or New York right now?";
    private static final String WEB_SEARCH_PROMPT =
            "Do a web search for news about Moderna. What are they up to lately?";

    public static void main(String[] args) throws Exception {
        if (null == System.getenv("OPENAI_API_KEY")) {
            System.err.println(
                    "\nWARNING envar OPENAI_API_KEY not found. This example will likely fail.\n");
        }
        var braintrust = Braintrust.get();
        var openTelemetry = braintrust.openTelemetryCreate();

        var chatCompletionsAgent = chatCompletionsAgent(openTelemetry);
        var responsesAgent = responsesAgent(openTelemetry);

        var rootSpan =
                openTelemetry
                        .getTracer("my-instrumentation")
                        .spanBuilder("langchain4j-ai-services-example")
                        .startSpan();
        try (var ignored = rootSpan.makeCurrent()) {
            // should do a concurrent tool call
            System.out.println(
                    "chat completions agent: " + chatCompletionsAgent.chatExample(WEATHER_PROMPT));
            // should do a server-side web search
            System.out.println("responses agent: " + responsesAgent.chatExample(WEB_SEARCH_PROMPT));
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
                "\n\n  Example complete! View your data in Braintrust: %s\n".formatted(url));
    }

    /** An agent calling openai's chat completions API (/v1/chat/completions). */
    private static MyAssistant chatCompletionsAgent(OpenTelemetry openTelemetry) {
        return BraintrustLangchain.wrap(
                openTelemetry,
                AiServices.builder(MyAssistant.class)
                        .chatModel(
                                OpenAiChatModel.builder()
                                        .apiKey(System.getenv("OPENAI_API_KEY"))
                                        .modelName("gpt-4o-mini")
                                        .temperature(0.0)
                                        .build())
                        .tools(new WeatherTools())
                        .executeToolsConcurrently());
    }

    /** An agent calling openai's responses API (/v1/responses), with hosted web search enabled. */
    private static MyAssistant responsesAgent(OpenTelemetry openTelemetry) {
        return BraintrustLangchain.wrap(
                openTelemetry,
                AiServices.builder(MyAssistant.class)
                        .chatModel(
                                OpenAiResponsesChatModel.builder()
                                        .apiKey(System.getenv("OPENAI_API_KEY"))
                                        // NOTE: the hosted web search tool needs a model that
                                        // supports it. gpt-4o-mini accepts the request but never
                                        // searches: it answers that it can't browse, or falls back
                                        // to the @Tool functions below.
                                        .modelName("gpt-4o")
                                        .temperature(0.0)
                                        // langchain4j has no typed API for the responses API's
                                        // server-side tools, so pass the raw tool object. It gets
                                        // appended to the same request `tools` array as the @Tool
                                        // functions, so the two kinds coexist.
                                        .serverTools(List.of(Map.of("type", "web_search_preview")))
                                        .build())
                        .tools(new WeatherTools())
                        .executeToolsConcurrently());
    }

    /** AI Service interface for the assistant */
    interface MyAssistant {
        String chatExample(String userMessage);
    }

    /** Example tool class with weather-related methods */
    public static class WeatherTools {
        @Tool("Get current weather for a location")
        public String getWeather(String location) {
            randomDelay(10, 200);
            return String.format("The weather in %s is sunny with 72°F temperature.", location);
        }

        @Tool("Get weather forecast for next N days")
        public String getForecast(String location, int days) {
            randomDelay(10, 200);
            return String.format(
                    "The %d-day forecast for %s: Mostly sunny with temperatures between 65-75°F.",
                    days, location);
        }

        /** Fake some work so concurrent tool spans have a visible, staggered duration. */
        private static void randomDelay(int lowerBoundInclusiveMS, int upperBoundInclusiveMS) {
            // ThreadLocalRandom because tools run concurrently (executeToolsConcurrently)
            int millis =
                    ThreadLocalRandom.current()
                            .nextInt(lowerBoundInclusiveMS, upperBoundInclusiveMS + 1);
            try {
                Thread.sleep(millis);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new RuntimeException("interrupted while faking work", e);
            }
        }
    }
}
