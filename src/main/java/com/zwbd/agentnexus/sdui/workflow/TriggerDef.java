package com.zwbd.agentnexus.sdui.workflow;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;

@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "type")
@JsonSubTypes({
        @JsonSubTypes.Type(value = TriggerDef.ManualTrigger.class, name = "manual"),
        @JsonSubTypes.Type(value = TriggerDef.CronTrigger.class, name = "cron"),
        @JsonSubTypes.Type(value = TriggerDef.WebhookTrigger.class, name = "webhook"),
        @JsonSubTypes.Type(value = TriggerDef.DeviceEventTrigger.class, name = "device_event")
})
public sealed interface TriggerDef permits TriggerDef.ManualTrigger, TriggerDef.CronTrigger,
        TriggerDef.WebhookTrigger, TriggerDef.DeviceEventTrigger {

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

    record DeviceEventTrigger(String id, String event) implements TriggerDef {
        public String type() { return "device_event"; }
    }
}
