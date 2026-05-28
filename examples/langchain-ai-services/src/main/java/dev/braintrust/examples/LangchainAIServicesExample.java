package dev.braintrust.examples;

import dev.braintrust.Braintrust;
import dev.braintrust.instrumentation.langchain.BraintrustLangchain;
import dev.langchain4j.agent.tool.Tool;
import dev.langchain4j.model.openai.OpenAiChatModel;
import dev.langchain4j.service.AiServices;

public class LangchainAIServicesExample {

    public static void main(String[] args) throws Exception {
        var braintrust = Braintrust.get();
        var openTelemetry = braintrust.openTelemetryCreate();

        Assistant assistant =
                BraintrustLangchain.wrap(
                        openTelemetry,
                        AiServices.builder(Assistant.class)
                                .chatModel(
                                        OpenAiChatModel.builder()
                                                .apiKey(System.getenv("OPENAI_API_KEY"))
                                                .modelName("gpt-4o-mini")
                                                .temperature(0.0)
                                                .build())
                                .tools(new WeatherTools())
                                .executeToolsConcurrently());

        var rootSpan =
                openTelemetry
                        .getTracer("my-instrumentation")
                        .spanBuilder("langchain4j-ai-services-example")
                        .startSpan();
        try (var ignored = rootSpan.makeCurrent()) {
            // response 1 should do a concurrent tool call
            var response1 = assistant.chat("is it hotter in Paris or New York right now?");
            System.out.println("response1: " + response1);
            var response2 = assistant.chat("what's the five day forecast for San Francisco?");
            System.out.println("response2: " + response2);
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

    /** AI Service interface for the assistant */
    interface Assistant {
        String chat(String userMessage);
    }

    /** Example tool class with weather-related methods */
    public static class WeatherTools {
        @Tool("Get current weather for a location")
        public String getWeather(String location) {
            return String.format("The weather in %s is sunny with 72°F temperature.", location);
        }

        @Tool("Get weather forecast for next N days")
        public String getForecast(String location, int days) {
            return String.format(
                    "The %d-day forecast for %s: Mostly sunny with temperatures between 65-75°F.",
                    days, location);
        }
    }
}
