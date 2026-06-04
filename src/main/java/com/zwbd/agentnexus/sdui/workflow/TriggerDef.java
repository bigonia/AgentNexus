package com.zwbd.agentnexus.sdui.workflow;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;

import java.util.List;

@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "type")
@JsonSubTypes({
        @JsonSubTypes.Type(value = TriggerDef.ManualTrigger.class, name = "manual"),
        @JsonSubTypes.Type(value = TriggerDef.CronTrigger.class, name = "cron"),
        @JsonSubTypes.Type(value = TriggerDef.WebhookTrigger.class, name = "webhook"),
        @JsonSubTypes.Type(value = TriggerDef.DeviceEventTrigger.class, name = "device_event"),
        @JsonSubTypes.Type(value = TriggerDef.DeviceMessageTrigger.class, name = "device_message"),
        @JsonSubTypes.Type(value = TriggerDef.DeviceCommandTrigger.class, name = "device_command")
})
public sealed interface TriggerDef permits TriggerDef.ManualTrigger, TriggerDef.CronTrigger,
        TriggerDef.WebhookTrigger, TriggerDef.DeviceEventTrigger,
        TriggerDef.DeviceMessageTrigger, TriggerDef.DeviceCommandTrigger {

    String id();

    record ManualTrigger(String id) implements TriggerDef {
        public String type() { return "manual"; }
    }

    record CronTrigger(String id, Integer interval, String cron) implements TriggerDef {
        public String type() { return "cron"; }
    }

    record WebhookTrigger(String id, String path) implements TriggerDef {
        public String type() { return "webhook"; }
    }

    record DeviceEventTrigger(String id, String event, String sectionId, String nodeId,
                              boolean optional) implements TriggerDef {
        public String type() { return "device_event"; }

        /** Backward-compatible constructor: optional defaults to false. */
        public DeviceEventTrigger(String id, String event, String sectionId, String nodeId) {
            this(id, event, sectionId, nodeId, false);
        }
    }

    record DeviceMessageTrigger(String id, String messageType, String sourceType, String sourceId) implements TriggerDef {
        public String type() { return "device_message"; }
    }

    record DeviceCommandTrigger(String id, String command, List<CommandParam> params,
                                boolean optional) implements TriggerDef {
        public String type() { return "device_command"; }

        /** Backward-compatible constructor: optional defaults to false. */
        public DeviceCommandTrigger(String id, String command, List<CommandParam> params) {
            this(id, command, params, false);
        }
    }
}
