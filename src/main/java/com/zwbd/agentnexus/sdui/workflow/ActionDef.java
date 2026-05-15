package com.zwbd.agentnexus.sdui.workflow;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;

import java.util.List;

@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "type")
@JsonSubTypes({
        @JsonSubTypes.Type(value = ActionDef.FetchAction.class, name = "fetch"),
        @JsonSubTypes.Type(value = ActionDef.UpdatePageAction.class, name = "update_page"),
        @JsonSubTypes.Type(value = ActionDef.PatchSectionAction.class, name = "patch_section"),
        @JsonSubTypes.Type(value = ActionDef.PlayAudioAction.class, name = "play_audio"),
        @JsonSubTypes.Type(value = ActionDef.TtsAction.class, name = "tts"),
        @JsonSubTypes.Type(value = ActionDef.SwitchPageAction.class, name = "switch_page"),
        @JsonSubTypes.Type(value = ActionDef.ControlAction.class, name = "control"),
        @JsonSubTypes.Type(value = ActionDef.ConditionAction.class, name = "condition"),
        @JsonSubTypes.Type(value = ActionDef.SequenceAction.class, name = "sequence"),
        @JsonSubTypes.Type(value = ActionDef.SetVariableAction.class, name = "set_variable")
})
public sealed interface ActionDef permits ActionDef.FetchAction, ActionDef.UpdatePageAction,
        ActionDef.PatchSectionAction, ActionDef.PlayAudioAction, ActionDef.TtsAction,
        ActionDef.SwitchPageAction, ActionDef.ControlAction,
        ActionDef.ConditionAction, ActionDef.SequenceAction, ActionDef.SetVariableAction {

    record FetchAction(String url, String method, String body, String save) implements ActionDef {}
    record UpdatePageAction(String page) implements ActionDef {}
    record PatchSectionAction(String page, String sectionId, String bind) implements ActionDef {}
    record PlayAudioAction(String preset, String text) implements ActionDef {}
    record TtsAction(String text) implements ActionDef {}
    record SwitchPageAction(String page) implements ActionDef {}
    record ControlAction(String command, String value) implements ActionDef {}

    record ConditionAction(
            String variable,
            String operator,
            String value,
            List<ActionDef> thenActions,
            List<ActionDef> elseActions
    ) implements ActionDef {}

    record SequenceAction(List<ActionDef> steps) implements ActionDef {}

    record SetVariableAction(String variable, String value) implements ActionDef {}
}
