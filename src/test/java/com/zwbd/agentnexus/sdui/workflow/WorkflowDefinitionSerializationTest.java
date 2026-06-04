package com.zwbd.agentnexus.sdui.workflow;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class WorkflowDefinitionSerializationTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void roundTripFullDefinition() throws Exception {
        WorkflowDefinition def = new WorkflowDefinition(
                "email_monitor",
                "邮件监控",
                "mail",
                List.of(new PageDef("main", "vertical_scroll", true, 3000, List.of(
                        new SectionBindDef("hero_1", "hero_section", Map.of(
                                "value", "$data.unread_count",
                                "label", "'未读邮件'",
                                "progress", "$data.unread_count"
                        )),
                        new SectionBindDef("list_1", "list_section", Map.of(
                                "items", "$data.recent_mails"
                        ))
                ))),
                List.of(
                        new TriggerDef.CronTrigger("t_refresh", 60, null),
                        new TriggerDef.WebhookTrigger("t_new_mail", "/new-email"),
                        new TriggerDef.ManualTrigger("t_debug"),
                        new TriggerDef.DeviceEventTrigger("t_btn", "action_click", null, null)
                ),
                Map.of(
                        "t_refresh", List.of(
                                new ActionDef.FetchAction("$env.MAIL_API/inbox", "GET", null, "data"),
                                new ActionDef.UpdatePageAction("main")
                        ),
                        "t_new_mail", List.of(
                                new ActionDef.FetchAction("$env.MAIL_API/inbox", "GET", null, "data"),
                                new ActionDef.UpdatePageAction("main"),
                                new ActionDef.PlayAudioAction("notification", null),
                                new ActionDef.TtsAction("$trigger.from 发来邮件: $trigger.subject")
                        ),
                        "t_debug", List.of(
                                new ActionDef.UpdatePageAction("main")
                        ),
                        "t_btn", List.of(
                                new ActionDef.ControlAction("audio.prompt.play", "notification")
                        )
                ),
                null, null, null
        );

        String json = mapper.writeValueAsString(def);
        WorkflowDefinition parsed = mapper.readValue(json, WorkflowDefinition.class);

        assertEquals(def.id(), parsed.id());
        assertEquals(def.name(), parsed.name());
        assertEquals(1, parsed.pages().size());
        assertEquals("main", parsed.pages().get(0).id());
        assertEquals(2, parsed.pages().get(0).sections().size());
        assertEquals(4, parsed.triggers().size());
        assertEquals(4, parsed.actions().size());

        // Verify trigger types
        TriggerDef t1 = parsed.triggers().get(0);
        assertInstanceOf(TriggerDef.CronTrigger.class, t1);
        assertEquals(60, ((TriggerDef.CronTrigger) t1).interval());

        TriggerDef t2 = parsed.triggers().get(1);
        assertInstanceOf(TriggerDef.WebhookTrigger.class, t2);
        assertEquals("/new-email", ((TriggerDef.WebhookTrigger) t2).path());

        TriggerDef t3 = parsed.triggers().get(2);
        assertInstanceOf(TriggerDef.ManualTrigger.class, t3);

        TriggerDef t4 = parsed.triggers().get(3);
        assertInstanceOf(TriggerDef.DeviceEventTrigger.class, t4);
        assertEquals("action_click", ((TriggerDef.DeviceEventTrigger) t4).event());

        // Verify actions
        List<ActionDef> newMailActions = parsed.actions().get("t_new_mail");
        assertEquals(4, newMailActions.size());
        assertInstanceOf(ActionDef.FetchAction.class, newMailActions.get(0));
        assertInstanceOf(ActionDef.UpdatePageAction.class, newMailActions.get(1));
        assertInstanceOf(ActionDef.PlayAudioAction.class, newMailActions.get(2));
        assertInstanceOf(ActionDef.TtsAction.class, newMailActions.get(3));
    }

    @Test
    void minimalDefinition() throws Exception {
        WorkflowDefinition def = new WorkflowDefinition(
                "minimal", "最小工作流", "",
                List.of(),
                List.of(new TriggerDef.ManualTrigger("t1")),
                Map.of("t1", List.of(new ActionDef.UpdatePageAction("main"))),
                null, null, null
        );

        String json = mapper.writeValueAsString(def);
        WorkflowDefinition parsed = mapper.readValue(json, WorkflowDefinition.class);

        assertEquals("minimal", parsed.id());
        assertEquals(1, parsed.triggers().size());
        assertEquals(1, parsed.actions().size());
    }

    @Test
    void nodeActionDefRoundTrip() throws Exception {
        WorkflowDefinition def = new WorkflowDefinition(
                "node_test", "统一节点测试", "",
                List.of(),
                List.of(new TriggerDef.ManualTrigger("t1")),
                Map.of("t1", List.of(
                        new ActionDef.NodeActionDef("device.control",
                                Map.of("command", "rgb.effect.set", "value", "#ff0000")),
                        new ActionDef.NodeActionDef("platform.tts",
                                Map.of("text", "$data.message"))
                )),
                null, null, null
        );

        String json = mapper.writeValueAsString(def);
        WorkflowDefinition parsed = mapper.readValue(json, WorkflowDefinition.class);

        assertEquals("node_test", parsed.id());
        List<ActionDef> actions = parsed.actions().get("t1");
        assertEquals(2, actions.size());

        ActionDef.NodeActionDef a1 = (ActionDef.NodeActionDef) actions.get(0);
        assertEquals("device.control", a1.nodeType());
        assertEquals("rgb.effect.set", a1.params().get("command"));

        ActionDef.NodeActionDef a2 = (ActionDef.NodeActionDef) actions.get(1);
        assertEquals("platform.tts", a2.nodeType());
        assertEquals("$data.message", a2.params().get("text"));
    }

    @Test
    void nodeActionDefJsonDeserialize() throws Exception {
        String json = """
        {
          "id": "test", "name": "Test", "icon": "",
          "pages": [],
          "triggers": [{"id": "t1", "type": "manual"}],
          "actions": {"t1": [{"type": "node", "nodeType": "device.audio.play", "params": {"preset": "notification"}}]}
        }""";
        WorkflowDefinition parsed = mapper.readValue(json, WorkflowDefinition.class);
        ActionDef action = parsed.actions().get("t1").get(0);
        assertInstanceOf(ActionDef.NodeActionDef.class, action);
        ActionDef.NodeActionDef na = (ActionDef.NodeActionDef) action;
        assertEquals("device.audio.play", na.nodeType());
        assertEquals("notification", na.params().get("preset"));
    }

    @Test
    void cronTriggerWithInterval() throws Exception {
        String json = """
        {
          "id": "test", "name": "Test", "icon": "",
          "pages": [],
          "triggers": [{"id": "t1", "type": "cron", "interval": 30}],
          "actions": {"t1": []}
        }""";
        WorkflowDefinition parsed = mapper.readValue(json, WorkflowDefinition.class);
        TriggerDef t = parsed.triggers().get(0);
        assertInstanceOf(TriggerDef.CronTrigger.class, t);
        assertEquals(30, ((TriggerDef.CronTrigger) t).interval());
    }
}
