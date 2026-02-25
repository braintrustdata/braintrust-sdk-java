package dev.braintrust.examples;

import com.openai.client.OpenAIClient;
import com.openai.client.okhttp.OpenAIOkHttpClient;
import com.openai.models.ChatModel;
import com.openai.models.chat.completions.ChatCompletionCreateParams;
import dev.braintrust.Braintrust;
import dev.braintrust.instrumentation.openai.BraintrustOpenAI;
import io.opentelemetry.api.GlobalOpenTelemetry;

public class NonGlobalOpenTelemetryExample {
    public static void main(String[] args) throws Exception {
        // make a no-op global otel. Braintrust will not use this
        var globalOtel = GlobalOpenTelemetry.get();
        var noopSpan =
                globalOtel.getTracer("my-instrumentation").spanBuilder("no-op-span").startSpan();
        try (var ignored = noopSpan.makeCurrent()) {
            // this span will not be reported to braintrust or anywhere else. It uses the default
            // otel global tracer, which is a no-op
        } finally {
            noopSpan.end();
        }

        if (null == System.getenv("OPENAI_API_KEY")) {
            System.err.println(
                    "\nWARNING envar OPEN_AI_API_KEY not found. This example will likely fail.\n");
        }
        var braintrust = Braintrust.get();
        var openTelemetry = braintrust.openTelemetryCreate(false);
        OpenAIClient openAIClient =
                BraintrustOpenAI.wrapOpenAI(openTelemetry, OpenAIOkHttpClient.fromEnv());
        var rootSpan =
                openTelemetry
                        .getTracer("my-instrumentation")
                        .spanBuilder("openai-java-instrumentation-example")
                        .startSpan();
        try (var ignored = rootSpan.makeCurrent()) {
            var request =
                    ChatCompletionCreateParams.builder()
                            .model(ChatModel.GPT_4O_MINI)
                            .addSystemMessage("You are a helpful assistant")
                            .addUserMessage("What is the capital of France?")
                            .temperature(0.0)
                            .build();
            var response = openAIClient.chat().completions().create(request);
            System.out.println("\n~~~ CHAT COMPLETIONS RESPONSE: %s\n".formatted(response));
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
}
