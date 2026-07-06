package com.zwbd.agentnexus.sdui.workflow;

import org.springframework.stereotype.Component;

import java.util.*;

/**
 * Central registry of node type definitions: output types and parameter types.
 *
 * <p>This is the single source of truth for type-driven {@code $ref} resolution.
 * When a parameter value is {@code {"$ref": "nodeId"}}, the resolver matches
 * the parameter's declared type against the referenced node's output types,
 * then extracts the value using the output's {@code extractPath}.</p>
 *
 * <p>To add a new node type with bindable outputs:
 * <ol>
 *   <li>Add its output definitions to {@link #OUTPUTS}</li>
 *   <li>Add its parameter type definitions to {@link #PARAMS}</li>
 * </ol>
 * </p>
 */
@Component
public class NodeTypeRegistry {

    /**
     * A single output a node can produce, available for downstream {@code $ref} binding.
     *
     * @param name        human-readable name for the editor UI
     * @param type        business type, e.g. {@code audio/recording}, {@code button/state}
     * @param displayName Chinese display name for the frontend
     * @param extractPath JSON path segments to navigate from the raw executor result
     *                    to the bindable value. E.g. {@code ["artifact", "artifactId"]}.
     */
    public record OutputDef(String name, String type, String displayName, List<String> extractPath) {}

    /**
     * A single parameter a node accepts.
     *
     * @param name        parameter key in the params map
     * @param type        expected business type for {@code $ref} binding;
     *                    {@code string}, {@code number}, {@code boolean} for untyped variants
     * @param displayName Chinese display name for the frontend
     * @param required    whether this parameter must be provided
     */
    public record ParamDef(String name, String type, String displayName, boolean required) {}

    // ── Output definitions ──────────────────────────────────────────

    private static final Map<String, List<OutputDef>> OUTPUTS = Map.of(
            "audio.record", List.of(
                    new OutputDef("recording", "audio/recording", "录音产物", List.of("artifact", "artifactId"))
            )
    );

    // ── Parameter definitions ───────────────────────────────────────

    private static final Map<String, List<ParamDef>> PARAMS = Map.of(
            "audio.play", List.of(
                    new ParamDef("artifact_id", "audio/recording", "音频产物", false),
                    new ParamDef("audio_file", "audio/recording", "音频文件", false),
                    new ParamDef("text", "string", "TTS文本", false),
                    new ParamDef("preset", "string", "预置音效", false)
            ),
            "audio.record", List.of(
                    new ParamDef("control", "string", "控制方式", false)
            ),
            "rgb.effect", List.of(
                    new ParamDef("mode", "string", "灯光模式", false),
                    new ParamDef("off", "boolean", "关闭灯光", false)
            ),
            "ui.update", List.of(
                    new ParamDef("slotId", "string", "槽位ID", false),
                    new ParamDef("templateKey", "string", "UI模板Key", false),
                    new ParamDef("variableKey", "string", "变量Key", true),
                    new ParamDef("value", "string", "变量值", false)
            ),
            "display.section", List.of(),
            "button.trigger", List.of(
                    new ParamDef("eventId", "string", "事件ID", true),
                    new ParamDef("nodeId", "string", "物理节点ID", false),
                    new ParamDef("sectionId", "string", "Section ID", false)
            ),
            "section.trigger", List.of(
                    new ParamDef("eventId", "string", "事件ID", true),
                    new ParamDef("nodeId", "string", "节点ID", false),
                    new ParamDef("sectionId", "string", "Section ID", false)
            )
    );

    private final Map<String, List<OutputDef>> outputsByType;
    private final Map<String, List<ParamDef>> paramsByType;
    private final Map<String, Map<String, ParamDef>> paramsByTypeAndName;

    public NodeTypeRegistry() {
        this.outputsByType = Collections.unmodifiableMap(new LinkedHashMap<>(OUTPUTS));
        this.paramsByType = Collections.unmodifiableMap(new LinkedHashMap<>(PARAMS));

        Map<String, Map<String, ParamDef>> byTypeAndName = new LinkedHashMap<>();
        for (var entry : PARAMS.entrySet()) {
            Map<String, ParamDef> byName = new LinkedHashMap<>();
            for (ParamDef param : entry.getValue()) {
                byName.put(param.name(), param);
            }
            byTypeAndName.put(entry.getKey(), Collections.unmodifiableMap(byName));
        }
        this.paramsByTypeAndName = Collections.unmodifiableMap(byTypeAndName);
    }

    /** All output definitions for a node type (empty list if none). */
    public List<OutputDef> getOutputs(String nodeType) {
        return outputsByType.getOrDefault(nodeType, List.of());
    }

    /** Find the output whose type matches the given parameter type, or null. */
    public OutputDef findOutput(String nodeType, String paramType) {
        return getOutputs(nodeType).stream()
                .filter(o -> o.type().equals(paramType))
                .findFirst()
                .orElse(null);
    }

    /** All parameter definitions for a node type (empty list if none). */
    public List<ParamDef> getParams(String nodeType) {
        return paramsByType.getOrDefault(nodeType, List.of());
    }

    /** Look up a single parameter definition by node type and param name, or null. */
    public ParamDef getParam(String nodeType, String paramName) {
        Map<String, ParamDef> byName = paramsByTypeAndName.get(nodeType);
        return byName != null ? byName.get(paramName) : null;
    }

    /** Whether the given node type has any output that can be {@code $ref}-bound. */
    public boolean hasOutputs(String nodeType) {
        return !getOutputs(nodeType).isEmpty();
    }

    /** All node types that have at least one bindable output. */
    public Set<String> bindableNodeTypes() {
        return outputsByType.keySet();
    }
}
