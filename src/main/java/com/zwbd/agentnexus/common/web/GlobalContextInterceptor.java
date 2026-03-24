package com.zwbd.agentnexus.common.web;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.servlet.HandlerInterceptor;

import java.util.Map;

/**
 * @Author: wnli
 * @Date: 2025/11/24 10:08
 * @Desc:
 * 通用拦截器：自动提取 Header 并注入 GlobalContext
 * 支持配置 Header 到 Context Key 的映射，实现非侵入式参数传递
 */
@Component
public class GlobalContextInterceptor implements HandlerInterceptor {

    // 定义 Header -> ContextKey 的映射关系
    // 实际项目中可放入 application.yml 配置
    private static final Map<String, String> HEADER_MAPPING = Map.of(
            "X-Space-Id", GlobalContext.KEY_SPACE_ID
//            "X-User-Id", GlobalContext.KEY_USER_ID,
//            "X-Trace-Id", GlobalContext.KEY_TRACE_ID
    );

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        // 1. 遍历映射，提取 HTTP Header 存入 Context
        HEADER_MAPPING.forEach((header, key) -> {
            String value = request.getHeader(header);
            if (StringUtils.hasText(value)) {
                GlobalContext.set(key, value);
            }
        });

        // 2. 核心参数兜底逻辑 (Space ID 特殊处理：如果没传，设为默认)
        // 保证下游业务永远能拿到一个有效的 space_id
        if (!StringUtils.hasText(GlobalContext.getString(GlobalContext.KEY_SPACE_ID))) {
            GlobalContext.set(GlobalContext.KEY_SPACE_ID, GlobalContext.DEFAULT_SPACE_ID);
        }

        return true;
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler, Exception ex) {
        GlobalContext.clear(); // 务必清理，防止线程池复用导致的数据串台
    }
}
