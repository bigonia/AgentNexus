package com.zwbd.agentnexus.sdui.v2.capability;

import java.util.List;

/**
 * 终端上报的机器可校验能力 Schema。
 *
 * <p>替代旧的"能力名称列表"（{@code CapabilitySnapshotParser} + {@code device/capabilities} topic）。
 * 对应 04_PROTOCOL_MODEL.md §4：Schema 至少描述协议与 Schema 版本、支持的 Trigger、支持的动作及其
 * 参数约束、动作可用于本地绑定还是平台命令、UI 能力与资源限制、音频与二进制格式限制。</p>
 *
 * <p>文档明确"Schema 的准确语法、字段组织和是否采用标准 JSON Schema 子集留到实现阶段结合平台
 * 校验方式确定"，因此这里是平台侧的首期形态，非标准 JSON Schema 子集（缺口 G11）。
 * 动作的自然语言解释与业务速查表由平台另行维护，不进入本结构（§4）。</p>
 */
public record CapabilitySchemaV2(
        String protocolVersion,
        String schemaVersion,
        String board,
        List<TriggerSpec> triggers,
        List<ActionSpec> actions,
        Surface surface
) {

    /**
     * 一个可配置的 Trigger 源。
     *
     * @param id           例如 {@code button.ok}、{@code platform.trigger}
     * @param source       取值见 {@code TriggerSource}
     * @param configurable 是否允许平台在绑定表中使用
     * @param maxResponses 该 Trigger 允许绑定的 Response 数量上限
     */
    public record TriggerSpec(String id, String source, boolean configurable, Integer maxResponses) {}

    /**
     * 动作参数约束。
     *
     * @param type   取值如 {@code int} / {@code string} / {@code boolean} / {@code enum} / {@code object} / {@code array}
     * @param values {@code enum} 类型的取值域
     */
    public record ParamSpec(String name, String type, boolean required, Integer min, Integer max, List<String> values) {}

    /**
     * 一个动作及其可用范围。
     *
     * @param usableIn 取值域为 {@code binding}（可出现在本地响应序列）、{@code request}（可作为平台请求）、
     *                 或两者同时存在
     */
    public record ActionSpec(String name, List<ParamSpec> params, List<String> usableIn) {

        public boolean usableInBinding() {
            return usableIn != null && usableIn.contains("binding");
        }

        public boolean usableInRequest() {
            return usableIn != null && usableIn.contains("request");
        }

        public ParamSpec param(String paramName) {
            if (params == null) {
                return null;
            }
            return params.stream().filter(p -> p.name().equals(paramName)).findFirst().orElse(null);
        }
    }

    /** 能力面：屏幕、UI 主视图、图片、Canvas、音频。 */
    public record Surface(ScreenSpec screen, UiSpec ui, ImageSpec image, CanvasSpec canvas, AudioSpec audio) {}

    /** @param touch 固定为 false（02_SYSTEM_BOUNDARY.md §1） */
    public record ScreenSpec(int width, int height, boolean touch) {}

    /**
     * @param sectionTypes 允许的 Section 类型，收敛为 03_UI_MODEL.md §2.1 的五类
     * @param viewModes    允许的主视图模式：{@code section} / {@code image} / {@code canvas}
     */
    public record UiSpec(List<String> sectionTypes, List<String> viewModes) {}

    public record ImageSpec(Integer maxBytes, Integer maxPaletteColors) {}

    public record CanvasSpec(List<String> resolutions, List<Integer> colorBits, Integer maxFps) {}

    public record AudioSpec(List<String> formats, Integer sampleRate, Integer maxBufferBytes) {}

    /** 按动作名查找。 */
    public ActionSpec action(String name) {
        if (actions == null) {
            return null;
        }
        return actions.stream().filter(a -> a.name().equals(name)).findFirst().orElse(null);
    }

    /** 按 Trigger id 查找。 */
    public TriggerSpec trigger(String id) {
        if (triggers == null) {
            return null;
        }
        return triggers.stream().filter(t -> t.id().equals(id)).findFirst().orElse(null);
    }
}
