package com.zwbd.agentnexus.sdui.v2.business;

import java.util.Arrays;

/**
 * Trigger 来源。
 *
 * <p>01_INTERACTION_MODEL.md §3 首期只支持两类：</p>
 *
 * <pre>
 * button.*              物理按钮，由输入模块产生
 * platform.trigger.*    云端 Trigger，由平台携带不透明 token 请求触发
 * </pre>
 */
public enum TriggerSource {

    /** 物理输入。终端本地识别，不携带 token。 */
    PHYSICAL("physical", "button."),

    /** 云端 Trigger。必须携带平台生成的不透明 token。 */
    PLATFORM("platform", "platform.trigger");

    private final String wire;
    private final String idPrefix;

    TriggerSource(String wire, String idPrefix) {
        this.wire = wire;
        this.idPrefix = idPrefix;
    }

    public String wire() {
        return wire;
    }

    public String idPrefix() {
        return idPrefix;
    }

    /** 从绑定表声明的字符串解析；无法识别时返回 null，由校验器报错。 */
    public static TriggerSource fromWire(String value) {
        if (value == null) {
            return null;
        }
        return Arrays.stream(values())
                .filter(source -> source.wire.equalsIgnoreCase(value.trim()))
                .findFirst()
                .orElse(null);
    }

    /** 按 triggerId 前缀推断来源。 */
    public static TriggerSource infer(String triggerId) {
        if (triggerId == null) {
            return null;
        }
        return Arrays.stream(values())
                .filter(source -> triggerId.startsWith(source.idPrefix))
                .findFirst()
                .orElse(null);
    }
}
