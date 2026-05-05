package dev.braintrust.trace;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

public class BrainstoreTraceTest {

    // -------------------------------------------------------------------------
    // getSpans() — lazy fetch and caching
    // -------------------------------------------------------------------------

    @Test
    void getSpansReturnsFetchedSpans() {
        var span = Map.<String, Object>of("id", "span-1", "span_attributes", Map.of("type", "llm"));
        var trace = traceWithSpans(List.of(span));

        var result = trace.getSpans();

        assertEquals(1, result.size());
        assertEquals("span-1", result.get(0).get("id"));
    }

    @Test
    void getSpansIsCached() {
        var callCount = new AtomicInteger(0);
        var trace =
                new BrainstoreTrace(
                        () -> {
                            callCount.incrementAndGet();
                            return List.of(
                                    Map.of(
                                            "id",
                                            "span-1",
                                            "span_attributes",
                                            Map.of("type", "llm")));
                        });

        trace.getSpans();
        trace.getSpans();
        trace.getSpans();

        assertEquals(1, callCount.get(), "supplier should only be called once");
    }

    @Test
    void getSpansReturnsEmptyListWhenNoSpans() {
        var trace = traceWithSpans(List.of());
        assertTrue(trace.getSpans().isEmpty());
    }

    // -------------------------------------------------------------------------
    // getSpans(String spanType) — type filtering
    // -------------------------------------------------------------------------

    @Test
    void getSpansByTypeFiltersCorrectly() {
        var llmSpan =
                Map.<String, Object>of("id", "llm-1", "span_attributes", Map.of("type", "llm"));
        var taskSpan =
                Map.<String, Object>of("id", "task-1", "span_attributes", Map.of("type", "task"));
        var evalSpan =
                Map.<String, Object>of("id", "eval-1", "span_attributes", Map.of("type", "eval"));
        var trace = traceWithSpans(List.of(llmSpan, taskSpan, evalSpan));

        var llmSpans = trace.getSpans("llm");

        assertEquals(1, llmSpans.size());
        assertEquals("llm-1", llmSpans.get(0).get("id"));
    }

    @Test
    void getSpansByTypeReturnsEmptyWhenNoMatch() {
        var taskSpan =
                Map.<String, Object>of("id", "task-1", "span_attributes", Map.of("type", "task"));
        var trace = traceWithSpans(List.of(taskSpan));

        assertTrue(trace.getSpans("llm").isEmpty());
    }

    @Test
    void getSpansByTypeHandlesSpanWithNoSpanAttributes() {
        var spanWithNoAttrs = Map.<String, Object>of("id", "bare-span");
        var trace = traceWithSpans(List.of(spanWithNoAttrs));

        assertTrue(trace.getSpans("llm").isEmpty());
    }

    @Test
    void getSpansByTypeHandlesSpanWithNoType() {
        var spanWithNoType =
                Map.<String, Object>of("id", "no-type", "span_attributes", Map.of("name", "foo"));
        var trace = traceWithSpans(List.of(spanWithNoType));

        assertTrue(trace.getSpans("llm").isEmpty());
    }

    // -------------------------------------------------------------------------
    // getLLMConversationThread()
    //
    // Real brainstore span shape:
    //   span_id:      String
    //   span_parents: List of parent span_id strings (null/absent for root)
    //   span_attributes: {type: "llm"|"task"|...}
    //   metrics:      {start: <unix timestamp>, end: <unix timestamp>, ...}
    //   input:        List of message maps OR JSON-encoded string of same
    //   output:       List of choice maps  [{finish_reason, message: {role, content}}, ...]
    // -------------------------------------------------------------------------

    @Test
    void getLLMConversationThreadReturnsEmptyForNoLLMSpans() {
        // A trace with only a task span — no LLM spans
        var rootTask = taskSpan("root", null, 1.0);
        var trace = traceWithSpans(List.of(rootTask));

        assertTrue(trace.getLLMConversationThread().isEmpty());
    }

    @Test
    void getLLMConversationThreadExtractsSingleTurnConversation() {
        // root task → llm span with one user message, one assistant reply
        var sysMsg = Map.<String, Object>of("role", "system", "content", "be helpful");
        var userMsg = Map.<String, Object>of("role", "user", "content", "strawberry");
        var assistantMsg = Map.<String, Object>of("role", "assistant", "content", "fruit");
        var choice =
                Map.<String, Object>of(
                        "finish_reason", "stop", "index", 0, "message", assistantMsg);

        var root = taskSpan("root", null, 1.0);
        var llm = llmSpan("llm1", "root", 1.1, List.of(sysMsg, userMsg), List.of(choice));

        var trace = traceWithSpans(List.of(root, llm));
        var thread = trace.getLLMConversationThread();

        // thread: sysMsg, userMsg, then the choice object from output
        assertEquals(3, thread.size());
        assertEquals("system", thread.get(0).get("role"));
        assertEquals("be helpful", thread.get(0).get("content"));
        assertEquals("user", thread.get(1).get("role"));
        assertEquals("strawberry", thread.get(1).get("content"));
        assertEquals("stop", thread.get(2).get("finish_reason"));
        assertEquals(assistantMsg, thread.get(2).get("message"));
    }

    @Test
    void getLLMConversationThreadDeDuplicatesSequentialMultiTurn() {
        // Sequential multi-turn chat matching real trace topology (e.g. OpenCode turns):
        //   root (task)
        //   ├── turn1 (task)
        //   │   └── llm1 (llm)   input=[sys, user1]                    output=[choice1]
        //   └── turn2 (task)
        //       └── llm2 (llm)   input=[sys, user1, choice1, user2]    output=[choice2]
        //
        // llm2's input is the full conversation history — sys and user1/choice1 appeared in
        // llm1 already, so they must NOT be duplicated in the thread.
        // Expected thread: sys, user1, choice1, user2, choice2 — 5 items, no duplicates.
        var sysMsg = Map.<String, Object>of("role", "system", "content", "be helpful");
        var user1Msg = Map.<String, Object>of("role", "user", "content", "Q1");
        var asst1Msg = Map.<String, Object>of("role", "assistant", "content", "A1");
        var choice1 = Map.<String, Object>of("finish_reason", "stop", "message", asst1Msg);

        var user2Msg = Map.<String, Object>of("role", "user", "content", "Q2");
        var asst2Msg = Map.<String, Object>of("role", "assistant", "content", "A2");
        var choice2 = Map.<String, Object>of("finish_reason", "stop", "message", asst2Msg);

        var root = taskSpan("root", null, 1.0);
        var turn1 = taskSpan("turn1", "root", 1.1);
        var llm1 = llmSpan("llm1", "turn1", 1.15, List.of(sysMsg, user1Msg), List.of(choice1));
        var turn2 = taskSpan("turn2", "root", 1.2);
        var llm2 =
                llmSpan(
                        "llm2",
                        "turn2",
                        1.25,
                        List.of(sysMsg, user1Msg, choice1, user2Msg),
                        List.of(choice2));

        var trace = traceWithSpans(List.of(root, turn2, turn1, llm2, llm1)); // out of order
        var thread = trace.getLLMConversationThread();

        assertEquals(5, thread.size(), "system message and prior context must not be duplicated");
        assertEquals("be helpful", ((Map<?, ?>) thread.get(0)).get("content")); // sys (once)
        assertEquals("Q1", ((Map<?, ?>) thread.get(1)).get("content")); // user1
        assertEquals(asst1Msg, ((Map<?, ?>) thread.get(2)).get("message")); // choice1
        assertEquals("Q2", ((Map<?, ?>) thread.get(3)).get("content")); // user2
        assertEquals(asst2Msg, ((Map<?, ?>) thread.get(4)).get("message")); // choice2
    }

    @Test
    void getLLMConversationThreadHandlesConcurrentSubagents() {
        // Mirrors the real trace: orchestrator LLM dispatches 3 parallel tool calls,
        // each spawning an independent subagent with its own single LLM call.
        //
        // Hierarchy:
        //   root (task)
        //   └── turn (task)
        //       ├── orch-llm (llm)         ← orchestrator, fires tool_calls
        //       ├── agent-a (task)          ← subagent A
        //       │   └── llm-a (llm)
        //       ├── agent-b (task)          ← subagent B
        //       │   └── llm-b (llm)
        //       └── agent-c (task)          ← subagent C
        //           └── llm-c (llm)

        var orchInput =
                List.of(
                        Map.<String, Object>of(
                                "role", "system", "content", "you are an orchestrator"),
                        Map.<String, Object>of(
                                "role", "user", "content", "do three things in parallel"));
        var orchOutput =
                List.of(
                        Map.<String, Object>of(
                                "finish_reason",
                                "stop",
                                "message",
                                Map.of(
                                        "role",
                                        "assistant",
                                        "content",
                                        "",
                                        "tool_calls",
                                        List.of())));

        var inputA = List.of(Map.<String, Object>of("role", "user", "content", "task A"));
        var outputA =
                List.of(
                        Map.<String, Object>of(
                                "finish_reason",
                                "stop",
                                "message",
                                Map.<String, Object>of(
                                        "role", "assistant", "content", "result A")));

        var inputB = List.of(Map.<String, Object>of("role", "user", "content", "task B"));
        var outputB =
                List.of(
                        Map.<String, Object>of(
                                "finish_reason",
                                "stop",
                                "message",
                                Map.<String, Object>of(
                                        "role", "assistant", "content", "result B")));

        var inputC = List.of(Map.<String, Object>of("role", "user", "content", "task C"));
        var outputC =
                List.of(
                        Map.<String, Object>of(
                                "finish_reason",
                                "stop",
                                "message",
                                Map.<String, Object>of(
                                        "role", "assistant", "content", "result C")));

        var root = taskSpan("root", null, 1.0);
        var turn = taskSpan("turn", "root", 1.1);
        var orchLlm = llmSpan("orch-llm", "turn", 1.2, orchInput, orchOutput);
        var agentA = taskSpan("agent-a", "turn", 1.3);
        var llmA = llmSpan("llm-a", "agent-a", 1.4, inputA, outputA);
        var agentB = taskSpan("agent-b", "turn", 1.3); // same start as agent-a (concurrent)
        var llmB = llmSpan("llm-b", "agent-b", 1.4, inputB, outputB);
        var agentC = taskSpan("agent-c", "turn", 1.3);
        var llmC = llmSpan("llm-c", "agent-c", 1.4, inputC, outputC);

        var trace =
                traceWithSpans(
                        List.of(root, turn, orchLlm, agentA, llmA, agentB, llmB, agentC, llmC));
        var thread = trace.getLLMConversationThread();

        // The orchestrator's messages appear first, followed by each subagent's messages.
        // Each subagent is an independent branch so their messages are NOT de-duplicated.
        //
        // Expected: orchInput(2) + orchOutput(1) + inputA(1) + outputA(1)
        //                                        + inputB(1) + outputB(1)
        //                                        + inputC(1) + outputC(1) = 9 items
        assertEquals(9, thread.size());

        // Orchestrator turn
        assertEquals("you are an orchestrator", thread.get(0).get("content"));
        assertEquals("do three things in parallel", thread.get(1).get("content"));
        assertNotNull(thread.get(2).get("message")); // orch output choice

        // Subagent A
        assertEquals("task A", thread.get(3).get("content"));
        assertEquals("result A", ((Map<?, ?>) thread.get(4).get("message")).get("content"));

        // Subagent B
        assertEquals("task B", thread.get(5).get("content"));
        assertEquals("result B", ((Map<?, ?>) thread.get(6).get("message")).get("content"));

        // Subagent C
        assertEquals("task C", thread.get(7).get("content"));
        assertEquals("result C", ((Map<?, ?>) thread.get(8).get("message")).get("content"));
    }

    @Test
    void getLLMConversationThreadHandlesStringEncodedInput() {
        // Real experiment spans encode input as a JSON string, not a raw List
        var sysMsg = Map.<String, Object>of("role", "system", "content", "be helpful");
        var userMsg = Map.<String, Object>of("role", "user", "content", "hello");
        var assistantMsg = Map.<String, Object>of("role", "assistant", "content", "hi");
        var choice = Map.<String, Object>of("finish_reason", "stop", "message", assistantMsg);

        String jsonInput =
                dev.braintrust.json.BraintrustJsonMapper.toJson(List.of(sysMsg, userMsg));

        var root = taskSpan("root", null, 1.0);
        // Use a mutable map to allow String input
        var llmSpanMap = new java.util.LinkedHashMap<String, Object>();
        llmSpanMap.put("span_id", "llm1");
        llmSpanMap.put("span_parents", List.of("root"));
        llmSpanMap.put("span_attributes", Map.of("type", "llm"));
        llmSpanMap.put("metrics", Map.of("start", 1.1));
        llmSpanMap.put("input", jsonInput); // String, not List
        llmSpanMap.put("output", List.of(choice));

        var trace = traceWithSpans(List.of(root, llmSpanMap));
        var thread = trace.getLLMConversationThread();

        assertEquals(3, thread.size());
        assertEquals("system", thread.get(0).get("role"));
        assertEquals("user", thread.get(1).get("role"));
        assertEquals(assistantMsg, thread.get(2).get("message"));
    }

    @Test
    void getLLMConversationThreadPrunesAutomationSubtrees() {
        // Automation spans (e.g. Topics scorer) and all their descendants must be excluded.
        // Mirrors the real trace where a Topics automation span has Pipeline/Chat Completion/
        // Embedding children that should not appear in the conversation thread.
        var userMsg = Map.<String, Object>of("role", "user", "content", "hello");
        var assistantMsg = Map.<String, Object>of("role", "assistant", "content", "hi");
        var choice = Map.<String, Object>of("finish_reason", "stop", "message", assistantMsg);

        // Scorer LLM input — should NOT appear in thread
        var scorerSysMsg =
                Map.<String, Object>of("role", "system", "content", "you are an analyst");
        var scorerUserMsg = Map.<String, Object>of("role", "user", "content", "analyze this");
        var scorerChoice =
                Map.<String, Object>of(
                        "finish_reason",
                        "stop",
                        "message",
                        Map.<String, Object>of("role", "assistant", "content", "analysis"));

        var root = taskSpan("root", null, 1.0);
        var turn = taskSpan("turn", "root", 1.1);
        var llm = llmSpan("llm1", "turn", 1.2, List.of(userMsg), List.of(choice));

        // automation span at same level as turn — entire subtree should be pruned
        var automation = spanWithType("automation-span", "root", "automation", 2.0);
        var pipeline = spanWithType("pipeline", "automation-span", "facet", 2.1);
        var scorerLlm =
                llmSpan(
                        "scorer-llm",
                        "pipeline",
                        2.2,
                        List.of(scorerSysMsg, scorerUserMsg),
                        List.of(scorerChoice));

        var trace = traceWithSpans(List.of(root, turn, llm, automation, pipeline, scorerLlm));
        var thread = trace.getLLMConversationThread();

        assertEquals(2, thread.size(), "scorer subtree must be pruned");
        assertEquals(userMsg, thread.get(0));
        assertEquals(choice, thread.get(1));
    }

    // -------------------------------------------------------------------------
    // fetchWithRetry — retry logic (via package-private constructor with custom supplier)
    // -------------------------------------------------------------------------

    @Test
    void getSpansRetriesUntilFreshAndNonEmpty() {
        var callCount = new AtomicInteger(0);
        var results = new ArrayList<List<Map<String, Object>>>();
        results.add(List.of()); // call 1: empty
        results.add(List.of()); // call 2: empty
        results.add(List.of(Map.of("id", "span-1", "span_attributes", Map.of("type", "llm"))));

        var trace =
                new BrainstoreTrace(
                        () -> {
                            int idx = callCount.getAndIncrement();
                            return idx < results.size() ? results.get(idx) : List.of();
                        });

        var spans = trace.getSpans();
        trace.getSpans(); // second call — should NOT invoke supplier again
        assertEquals(1, callCount.get(), "supplier called exactly once (result is cached)");
        assertTrue(spans.isEmpty());
    }

    // -------------------------------------------------------------------------
    // Helpers — build span maps matching real brainstore shape
    // -------------------------------------------------------------------------

    /** A task/non-LLM span with the given id, optional parent, and start time. */
    private static Map<String, Object> taskSpan(String id, String parentId, double startTime) {
        var m = new java.util.LinkedHashMap<String, Object>();
        m.put("span_id", id);
        m.put("span_parents", parentId != null ? List.of(parentId) : null);
        m.put("span_attributes", Map.of("type", "task"));
        m.put("metrics", Map.of("start", startTime));
        return m;
    }

    /** An LLM span with the given id, parent, start time, input messages, and output choices. */
    private static Map<String, Object> llmSpan(
            String id,
            String parentId,
            double startTime,
            List<Map<String, Object>> input,
            List<Map<String, Object>> output) {
        var m = new java.util.LinkedHashMap<String, Object>();
        m.put("span_id", id);
        m.put("span_parents", parentId != null ? List.of(parentId) : null);
        m.put("span_attributes", Map.of("type", "llm"));
        m.put("metrics", Map.of("start", startTime));
        m.put("input", input);
        m.put("output", output);
        return m;
    }

    /** A span with an arbitrary type (not "llm" or "task"). */
    private static Map<String, Object> spanWithType(
            String id, String parentId, String type, double startTime) {
        var m = new java.util.LinkedHashMap<String, Object>();
        m.put("span_id", id);
        m.put("span_parents", parentId != null ? List.of(parentId) : null);
        m.put("span_attributes", Map.of("type", type));
        m.put("metrics", Map.of("start", startTime));
        return m;
    }

    private static BrainstoreTrace traceWithSpans(List<Map<String, Object>> spans) {
        return new BrainstoreTrace(() -> spans);
    }
}
