package dev.braintrust.examples;

import dev.braintrust.Braintrust;
import dev.braintrust.instrumentation.langchain.BraintrustLangchain;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.openai.OpenAiChatModel;

/** Basic OTel + LangChain4j instrumentation example */
public class LangchainExample {

    public static void main(String[] args) throws Exception {
        if (null == System.getenv("OPENAI_API_KEY")) {
            System.err.println(
                    "\nWARNING envar OPENAI_API_KEY not found. This example will likely fail.\n");
        }
        var braintrust = Braintrust.get();
        var openTelemetry = braintrust.openTelemetryCreate();

        ChatModel model =
                BraintrustLangchain.wrap(
                        openTelemetry,
                        OpenAiChatModel.builder()
                                .apiKey(System.getenv("OPENAI_API_KEY"))
                                .modelName("gpt-4o-mini")
                                .temperature(0.0));

        var rootSpan =
                openTelemetry
                        .getTracer("my-instrumentation")
                        .spanBuilder("langchain4j-instrumentation-example")
                        .startSpan();
        try (var ignored = rootSpan.makeCurrent()) {
            chatExample(model);
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

    private static void chatExample(ChatModel model) {
        var message = UserMessage.from("What is the capital of France?");
        var response = model.chat(message);
        System.out.println(
                "\n~~~ LANGCHAIN4J CHAT RESPONSE: %s\n".formatted(response.aiMessage().text()));
    }
}
