package com.zwbd.agentnexus.utils;
import java.util.*;

/**
 * @Author: wnli
 * @Date: 2025/12/10 15:53
 * @Desc:
 */
public class MapUtil {
    /**
     * 将嵌套 Map 扁平化
     * 输入: { "映射后实体": { "entity": "富康家园" } }
     * 输出: { "映射后实体.entity": "富康家园" }
     */
    public static Map<String, Object> flatten(Map<String, Object> source) {
        Map<String, Object> result = new LinkedHashMap<>();
        flattenRecursive(source, "", result);
        return result;
    }

    private static void flattenRecursive(Map<String, Object> source, String prefix, Map<String, Object> result) {
        if (source == null) return;

        for (Map.Entry<String, Object> entry : source.entrySet()) {
            String key = prefix.isEmpty() ? entry.getKey() : prefix + "." + entry.getKey();
            Object value = entry.getValue();

            if (value instanceof Map) {
                // 递归处理嵌套 Map
                flattenRecursive((Map<String, Object>) value, key, result);
            } else if (value instanceof List) {
                // List 转为字符串，避免 Excel 无法显示
                result.put(key, value.toString());
            } else {
                result.put(key, value);
            }
        }
    }
}
