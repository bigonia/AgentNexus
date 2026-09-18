package com.zwbd.agentnexus.sdui.v2.business;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 响应序列中的一个动作。
 *
 * <p>01_INTERACTION_MODEL.md §2：Response 序列"有限、有序、不可编程"，不支持条件表达式、
 * 分支循环、动态跳转、等待云端业务结果、动态调用外部接口。因此本结构刻意只保留动作名与参数，
 * 不提供任何控制流字段。</p>
 *
 * @param action 动作名，必须出现在能力 Schema 的 {@code actions} 中且可用于本地绑定
 * @param params 参数表；缺省为空表
 */
public record ResponseStep(String action, Map<String, Object> params) {

    public ResponseStep {
        params = params == null ? Map.of() : Map.copyOf(new LinkedHashMap<>(params));
    }

    public static ResponseStep of(String action) {
        return new ResponseStep(action, Map.of());
    }

    public static ResponseStep of(String action, Map<String, Object> params) {
        return new ResponseStep(action, params);
    }

    /** 替换参数表，用于在准备阶段注入平台生成的 token。 */
    public ResponseStep withParams(Map<String, Object> newParams) {
        return new ResponseStep(action, newParams);
    }

    /** 供终端使用的 wire 形式；参数为空时省略 {@code params}。 */
    public Map<String, Object> toWire() {
        Map<String, Object> wire = new LinkedHashMap<>();
        wire.put("action", action);
        if (!params.isEmpty()) {
            wire.put("params", params);
        }
        return wire;
    }
}
