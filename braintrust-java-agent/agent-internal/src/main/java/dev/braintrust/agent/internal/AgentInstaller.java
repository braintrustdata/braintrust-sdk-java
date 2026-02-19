package dev.braintrust.agent.internal;

import static net.bytebuddy.matcher.ElementMatchers.*;

import dev.braintrust.agent.internal.anthropic.MessageCreateAdvice;
import dev.braintrust.agent.internal.google.GenerateContentAdvice;
import dev.braintrust.agent.internal.openai.ChatCompletionAdvice;
import java.lang.instrument.Instrumentation;
import net.bytebuddy.agent.builder.AgentBuilder;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.dynamic.DynamicType;
import net.bytebuddy.utility.JavaModule;

/**
 * The real agent installation logic, loaded by {@code BraintrustClassLoader} in an isolated
 * classloader.
 *
 * <p>This class and its bundled dependencies (ByteBuddy, OTLP exporter, OkHttp, etc.) are
 * invisible to the application's classpath. They exist as {@code .classdata} entries inside the
 * agent JAR and are only accessible through the agent's custom classloader.
 *
 * <p>The OTel API and SDK classes are on the bootstrap classpath and shared with the application —
 * this is intentional so that traces from the agent and the app participate in the same context.
 *
 * <p>Sets up ByteBuddy instrumentation for:
 * <ul>
 *   <li>OpenAI Java SDK — {@code ChatCompletionService.create()}</li>
 *   <li>Anthropic Java SDK — {@code MessageService.create()}</li>
 *   <li>Google GenAI — {@code ApiClient.request()}</li>
 * </ul>
 */
public class AgentInstaller {

    // Fully qualified type names — matched by name so we don't need compile-time deps
    private static final String OPENAI_CHAT_SERVICE =
            "com.openai.services.blocking.chat.ChatCompletionService";
    private static final String OPENAI_CHAT_SERVICE_ASYNC =
            "com.openai.services.async.chat.ChatCompletionServiceAsync";
    private static final String ANTHROPIC_MESSAGE_SERVICE =
            "com.anthropic.services.blocking.MessageService";
    private static final String ANTHROPIC_MESSAGE_SERVICE_ASYNC =
            "com.anthropic.services.async.MessageServiceAsync";
    private static final String GOOGLE_API_CLIENT = "com.google.genai.ApiClient";

    /**
     * Called reflectively from {@code BraintrustAgent.premain()} via the isolated classloader.
     *
     * @param agentArgs the agent arguments from the {@code -javaagent} flag
     * @param inst the JVM instrumentation instance
     */
    public static void install(String agentArgs, Instrumentation inst) {
        log("AgentInstaller.install() called");
        log("AgentInstaller classloader: "
                + AgentInstaller.class.getClassLoader().getClass().getName());
        log("Agent args: " + agentArgs);
        log("Instrumentation: retransform=" + inst.isRetransformClassesSupported());

        var agentBuilder =
                new AgentBuilder.Default()
                        // Narrow the scope: only consider classes from AI SDK packages.
                        // This prevents ByteBuddy from walking the type hierarchy of OTel SDK,
                        // JDK, or other framework classes (which causes resolution errors when
                        // classes are split across bootstrap/app classloaders).
                        .ignore(
                                not(
                                        nameStartsWith("com.openai.")
                                                .or(nameStartsWith("com.anthropic."))
                                                .or(nameStartsWith("com.google.genai."))))
                        // Use retransformation so we can instrument classes already loaded
                        .with(AgentBuilder.RedefinitionStrategy.RETRANSFORMATION)
                        .with(new LoggingListener());

        // --- OpenAI: ChatCompletionService.create() ---
        agentBuilder =
                agentBuilder
                        .type(hasSuperType(named(OPENAI_CHAT_SERVICE)))
                        .transform(
                                new AgentBuilder.Transformer.ForAdvice()
                                        .include(AgentInstaller.class.getClassLoader())
                                        .advice(
                                                named("create")
                                                        .and(takesArgument(0, named(
                                                                "com.openai.models.chat.completions.ChatCompletionCreateParams"))),
                                                ChatCompletionAdvice.class.getName()));

        // --- OpenAI Async: ChatCompletionServiceAsync.create() ---
        agentBuilder =
                agentBuilder
                        .type(hasSuperType(named(OPENAI_CHAT_SERVICE_ASYNC)))
                        .transform(
                                new AgentBuilder.Transformer.ForAdvice()
                                        .include(AgentInstaller.class.getClassLoader())
                                        .advice(
                                                named("create")
                                                        .and(takesArgument(0, named(
                                                                "com.openai.models.chat.completions.ChatCompletionCreateParams"))),
                                                ChatCompletionAdvice.class.getName()));

        // --- Anthropic: MessageService.create() ---
        agentBuilder =
                agentBuilder
                        .type(hasSuperType(named(ANTHROPIC_MESSAGE_SERVICE)))
                        .transform(
                                new AgentBuilder.Transformer.ForAdvice()
                                        .include(AgentInstaller.class.getClassLoader())
                                        .advice(
                                                named("create")
                                                        .and(takesArgument(0, named(
                                                                "com.anthropic.models.messages.MessageCreateParams"))),
                                                MessageCreateAdvice.class.getName()));

        // --- Anthropic Async: MessageServiceAsync.create() ---
        agentBuilder =
                agentBuilder
                        .type(hasSuperType(named(ANTHROPIC_MESSAGE_SERVICE_ASYNC)))
                        .transform(
                                new AgentBuilder.Transformer.ForAdvice()
                                        .include(AgentInstaller.class.getClassLoader())
                                        .advice(
                                                named("create")
                                                        .and(takesArgument(0, named(
                                                                "com.anthropic.models.messages.MessageCreateParams"))),
                                                MessageCreateAdvice.class.getName()));

        // --- Google GenAI: ApiClient.request(String, String, String, Optional) ---
        agentBuilder =
                agentBuilder
                        .type(named(GOOGLE_API_CLIENT))
                        .transform(
                                new AgentBuilder.Transformer.ForAdvice()
                                        .include(AgentInstaller.class.getClassLoader())
                                        .advice(
                                                named("request")
                                                        .and(takesArgument(0, String.class))
                                                        .and(takesArgument(1, String.class)),
                                                GenerateContentAdvice.class.getName()));

        agentBuilder.installOn(inst);
        log("ByteBuddy instrumentation installed.");
    }

    private static void log(String msg) {
        System.out.println("[braintrust]   " + msg);
    }

    /** ByteBuddy listener that logs transformation events for debugging. */
    private static class LoggingListener implements AgentBuilder.Listener {

        @Override
        public void onDiscovery(
                String typeName, ClassLoader classLoader, JavaModule module, boolean loaded) {
            // Too noisy — only log in debug mode if needed
        }

        @Override
        public void onTransformation(
                TypeDescription typeDescription,
                ClassLoader classLoader,
                JavaModule module,
                boolean loaded,
                DynamicType dynamicType) {
            log("Transformed: " + typeDescription.getName());
        }

        @Override
        public void onIgnored(
                TypeDescription typeDescription,
                ClassLoader classLoader,
                JavaModule module,
                boolean loaded) {
            // Too noisy
        }

        @Override
        public void onError(
                String typeName,
                ClassLoader classLoader,
                JavaModule module,
                boolean loaded,
                Throwable throwable) {
            System.err.println(
                    "[braintrust] ERROR transforming " + typeName + ": " + throwable.getMessage());
            throwable.printStackTrace(System.err);
        }

        @Override
        public void onComplete(
                String typeName, ClassLoader classLoader, JavaModule module, boolean loaded) {
            // Too noisy
        }
    }
}
