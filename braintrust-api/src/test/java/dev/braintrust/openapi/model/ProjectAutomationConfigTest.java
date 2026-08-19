package dev.braintrust.openapi.model;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.braintrust.openapi.JSON;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * Deserialization tests for {@link ProjectAutomationConfig}, the oneOf covering the automation
 * kinds returned by {@code GET /v1/project_automation}.
 *
 * <p>A oneOf that fails to match a variant throws, so a single unmatched automation anywhere in a
 * listing page breaks the whole call with {@code 0 classes match result, expected 1}.
 */
public class ProjectAutomationConfigTest {

    private static ObjectMapper mapper;

    @BeforeAll
    static void setUp() {
        mapper = new JSON().getMapper();
    }

    /** A {@code logs} automation, the {@link ProjectAutomationConfigOneOf} variant. */
    private static final String LOGS_JSON =
            """
            {
              "event_type": "logs",
              "btql_filter": "scores.Factuality < 0.5",
              "interval_seconds": 300,
              "action": { "type": "webhook", "url": "https://example.com/hook" }
            }
            """;

    /** A {@code topic} automation, the {@link TopicAutomationConfig} variant. */
    private static final String TOPIC_JSON =
            """
            {
              "event_type": "topic",
              "sampling_rate": 1,
              "facet_functions": [
                { "type": "global", "name": "Task", "function_type": "facet" },
                { "type": "global", "name": "Sentiment", "function_type": "facet" },
                { "type": "global", "name": "Issues", "function_type": "facet" }
              ],
              "topic_map_functions": [
                { "function": { "type": "function", "id": "36363b1a-b126-41da-9d28-a91a648b0b4c" } },
                { "function": { "type": "function", "id": "19fe31d1-3b15-4a78-9711-a79d2a40c0df" } },
                { "function": { "type": "function", "id": "dfe599b5-9e36-479a-9d37-42efe516fa84" } }
              ],
              "scope": { "type": "trace", "idle_seconds": 600 },
              "rerun_seconds": 86400,
              "relabel_overlap_seconds": 3600,
              "backfill_time_range": "86400s"
            }
            """;

    /**
     * A {@code windowed} automation, the {@link WindowedAutomationConfig} variant. This is the type
     * behind scheduled Loop jobs; it was absent from the spec entirely until the pinned ref was
     * moved forward, and its absence broke every automation listing page.
     */
    private static final String WINDOWED_JSON =
            """
            {
              "event_type": "windowed",
              "status": "active",
              "window": {
                "window_seconds": 86400,
                "schedule": {
                  "type": "cron",
                  "cron_expression": "0 9 * * 1",
                  "timezone": "America/Los_Angeles"
                },
                "evaluation_delay_seconds": 0
              },
              "loop": {
                "prompt": "say hi to my friends",
                "include_trigger_input": false,
                "agent_slug": "loop-chat",
                "auto_approve_tools": [],
                "harness": "codex",
                "model": "gpt-5.6-sol"
              },
              "actions": []
            }
            """;

    @Test
    void deserializes_logsVariant() throws Exception {
        var config = mapper.readValue(LOGS_JSON, ProjectAutomationConfig.class);

        var logs = assertInstanceOf(ProjectAutomationConfigOneOf.class, config.getActualInstance());
        assertEquals(ProjectAutomationConfigOneOf.EventTypeEnum.LOGS, logs.getEventType());
        assertEquals("scores.Factuality < 0.5", logs.getBtqlFilter());
    }

    /** Pinpoints the failing layer: the variant itself, before any oneOf matching. */
    @Test
    void deserializes_topicVariantDirectly() throws Exception {
        var topic = mapper.readValue(TOPIC_JSON, TopicAutomationConfig.class);
        assertEquals(TopicAutomationConfig.EventTypeEnum.TOPIC, topic.getEventType());
        assertEquals(3, topic.getTopicMapFunctions().size());

        assertNotNull(topic.getTopicMapFunctions().get(0).getFunction());
    }

    /**
     * A topic map function's saved-function reference keeps the id that identifies it.
     *
     * <p>Asserted through a round trip of the whole config, since that is what a caller reading an
     * automation gets back.
     */
    @Test
    void topicVariant_preservesTopicMapFunctionIds() throws Exception {
        var config = mapper.readValue(TOPIC_JSON, ProjectAutomationConfig.class);

        assertEquals(
                mapper.readTree(TOPIC_JSON), mapper.readTree(mapper.writeValueAsString(config)));
    }

    @Test
    void deserializes_topicVariant() throws Exception {
        var config = mapper.readValue(TOPIC_JSON, ProjectAutomationConfig.class);

        var topic = assertInstanceOf(TopicAutomationConfig.class, config.getActualInstance());
        assertEquals(TopicAutomationConfig.EventTypeEnum.TOPIC, topic.getEventType());
        assertEquals(3, topic.getFacetFunctions().size());
        assertEquals(3, topic.getTopicMapFunctions().size());
    }

    @Test
    void deserializes_windowedVariant() throws Exception {
        var config = mapper.readValue(WINDOWED_JSON, ProjectAutomationConfig.class);

        var windowed = assertInstanceOf(WindowedAutomationConfig.class, config.getActualInstance());
        assertEquals(WindowedAutomationConfig.EventTypeEnum.WINDOWED, windowed.getEventType());
        assertEquals("say hi to my friends", windowed.getLoop().getPrompt());
    }

    /** The whole point: one unmatched automation must not break a page of good ones. */
    @Test
    void deserializes_mixedListingPage() throws Exception {
        var page =
                mapper.readValue(
                        """
                        { "objects": [
                          { "id": "11111111-1111-1111-1111-111111111111",
                            "project_id": "22222222-2222-2222-2222-222222222222",
                            "user_id": "33333333-3333-3333-3333-333333333333",
                            "created": "2026-08-19T01:57:15.013Z",
                            "name": "logs-alert",
                            "config": %s },
                          { "id": "44444444-4444-4444-4444-444444444444",
                            "project_id": "22222222-2222-2222-2222-222222222222",
                            "user_id": "33333333-3333-3333-3333-333333333333",
                            "created": "2026-08-19T01:57:15.013Z",
                            "name": "topic-discovery",
                            "config": %s },
                          { "id": "55555555-5555-5555-5555-555555555555",
                            "project_id": "22222222-2222-2222-2222-222222222222",
                            "user_id": "33333333-3333-3333-3333-333333333333",
                            "created": "2026-08-19T01:57:15.013Z",
                            "name": "pattern-discovery",
                            "config": %s }
                        ] }
                        """
                                .formatted(LOGS_JSON, TOPIC_JSON, WINDOWED_JSON),
                        GetProjectAutomation200Response.class);

        assertEquals(3, page.getObjects().size());
        assertInstanceOf(
                ProjectAutomationConfigOneOf.class,
                page.getObjects().get(0).getConfig().getActualInstance());
        assertInstanceOf(
                TopicAutomationConfig.class,
                page.getObjects().get(1).getConfig().getActualInstance());
        assertInstanceOf(
                WindowedAutomationConfig.class,
                page.getObjects().get(2).getConfig().getActualInstance());
    }
}
