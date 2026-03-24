package com.zwbd.agentnexus.common.web;

import org.springframework.util.StringUtils;

import java.util.HashMap;
import java.util.Map;

/**
 * @Author: wnli
 * @Date: 2025/11/24 10:06
 * @Desc:
 */
public class GlobalContext {

    private static final ThreadLocal<Map<String, Object>> CONTEXT = ThreadLocal.withInitial(HashMap::new);

    // 标准 Key 定义
    public static final String KEY_SPACE_ID = "space_id";
    public static final String KEY_USER_ID = "user_id";
    public static final String KEY_TRACE_ID = "trace_id";

    // 默认值定义
    public static final String DEFAULT_SPACE_ID = "default";

    /**
     * 设置上下文参数
     */
    public static void set(String key, Object value) {
        CONTEXT.get().put(key, value);
    }

    /**
     * 获取上下文参数
     */
    public static Object get(String key) {
        return CONTEXT.get().get(key);
    }

    public static String getString(String key) {
        Object val = get(key);
        return val != null ? val.toString() : null;
    }

    /**
     * 辅助方法：获取当前 SpaceID，如果未设置则返回默认值
     */
    public static String getSpaceId() {
        String spaceId = getString(KEY_SPACE_ID);
        return StringUtils.hasText(spaceId) ? spaceId : DEFAULT_SPACE_ID;
    }

    public static void clear() {
        CONTEXT.remove();
    }
}
