package dev.braintrust.examples;

import dev.braintrust.Braintrust;
import dev.braintrust.instrumentation.langchain.BraintrustLangchain;
import dev.langchain4j.agent.tool.Tool;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.openai.OpenAiChatModel;
import dev.langchain4j.service.AiServices;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.context.Scope;
import io.opentelemetry.sdk.OpenTelemetrySdk;

/**
 * Demonstrates how to use BraintrustLangchain.wrapTools() to automatically trace tool executions.
 *
 * <p>This example shows:
 *
 * <ul>
 *   <li>Wrapping LangChain4j tool objects to create OpenTelemetry spans for each tool call
 *   <li>Creating a conversation hierarchy with turns and tool executions
 *   <li>How tool calls appear in Braintrust traces with full context
 * </ul>
 */
public class LangchainToolWrappingExample {

    /** Example tool class with weather-related methods */
    public static class WeatherTools {
        @Tool("Get current weather for a location")
        public String getWeather(String location) {
            // Simulate a weather API call
            return String.format(
                    "The weather in %s is sunny with 72°F temperature.", location);
        }

        @Tool("Get weather forecast for next N days")
        public String getForecast(String location, int days) {
            // Simulate a forecast API call
            return String.format(
                    "The %d-day forecast for %s: Mostly sunny with temperatures between 65-75°F.",
                    days, location);
        }
    }

    /** AI Service interface for the assistant */
    interface Assistant {
        String chat(String userMessage);
    }

    public static void main(String[] args) throws Exception {
        if (null == System.getenv("OPENAI_API_KEY")) {
            System.err.println(
                    "\nWARNING envar OPENAI_API_KEY not found. This example will likely fail.\n");
        }

        var braintrust = Braintrust.get();
        var openTelemetry = braintrust.openTelemetryCreate();
        var tracer = openTelemetry.getTracer("langchain-tool-wrapping");

        System.out.println("\n=== LangChain4j Tool Wrapping Example ===\n");

        // Create root span for the conversation
        var conversationSpan = tracer.spanBuilder("weather-assistant-conversation").startSpan();

        try (var ignored = conversationSpan.makeCurrent()) {
            // Wrap the LLM with Braintrust instrumentation
            ChatModel model =
                    BraintrustLangchain.wrap(
                            openTelemetry,
                            OpenAiChatModel.builder()
                                    .apiKey(System.getenv("OPENAI_API_KEY"))
                                    .modelName("gpt-4o-mini")
                                    .temperature(0.0));

            // Create tools and wrap them with Braintrust instrumentation
            WeatherTools tools = new WeatherTools();
            WeatherTools instrumentedTools = BraintrustLangchain.wrapTools(openTelemetry, tools);

            System.out.println("Tools wrapped with Braintrust instrumentation");
            System.out.println("Each tool call will create a span in Braintrust\n");

            // Create AI service with the instrumented tools
            Assistant assistant =
                    AiServices.builder(Assistant.class)
                            .chatModel(model)
                            .tools(instrumentedTools)
                            .build();

            // Example 1: Single tool call
            conversationTurn(
                    tracer,
                    "turn_1",
                    () -> {
                        System.out.println("--- Turn 1: Single Tool Call ---");
                        String query = "What's the weather in San Francisco?";
                        System.out.println("User: " + query);
                        String response = assistant.chat(query);
                        System.out.println("Assistant: " + response);
                        System.out.println();
                        return response;
                    });

            // Example 2: Tool with multiple parameters
            conversationTurn(
                    tracer,
                    "turn_2",
                    () -> {
                        System.out.println("--- Turn 2: Multiple Parameters ---");
                        String query = "What's the 5-day forecast for Tokyo?";
                        System.out.println("User: " + query);
                        String response = assistant.chat(query);
                        System.out.println("Assistant: " + response);
                        System.out.println();
                        return response;
                    });

        } finally {
            conversationSpan.end();
        }

        // Flush traces before exit
        if (openTelemetry instanceof OpenTelemetrySdk) {
            ((OpenTelemetrySdk) openTelemetry).close();
        }

        var url =
                braintrust.projectUri()
                        + "/logs?r=%s&s=%s"
                                .formatted(
                                        conversationSpan.getSpanContext().getTraceId(),
                                        conversationSpan.getSpanContext().getSpanId());

        System.out.println("Example complete!");
        System.out.println("\nIn Braintrust, you'll see:");
        System.out.println("  • Root conversation span");
        System.out.println("  • Nested turn spans for each user interaction");
        System.out.println("  • LLM call spans (from BraintrustLangchain.wrap())");
        System.out.println("  • Tool execution spans (from BraintrustLangchain.wrapTools())");
        System.out.println("\n  View your traces: " + url + "\n");
    }

    /**
     * Helper method to create a conversation turn span
     *
     * @param tracer OpenTelemetry tracer
     * @param turnName Name of the conversation turn
     * @param action Lambda to execute within the turn span
     */
    private static void conversationTurn(
            io.opentelemetry.api.trace.Tracer tracer, String turnName, Runnable action) {
        Span turnSpan = tracer.spanBuilder(turnName).startSpan();
        try (Scope scope = turnSpan.makeCurrent()) {
            action.run();
        } finally {
            turnSpan.end();
        }
    }
}
