package com.zwbd.agentnexus.common.web;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * @Author: wnli
 * @Date: 2025/11/24 10:08
 * @Desc:
 * 通用拦截器：自动从 SecurityContext 提取用户身份注入 GlobalContext，
 * 同时兼容 X-Space-Id header（向后兼容旧前端）。
 */
@Component
public class GlobalContextInterceptor implements HandlerInterceptor {

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        // 1. 优先从 JWT SecurityContext 提取用户名作为隔离键
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.isAuthenticated() && StringUtils.hasText(auth.getName())) {
            GlobalContext.set(GlobalContext.KEY_USER_ID, auth.getName());
        }

        // 2. 兼容旧前端：X-Space-Id header 作为 fallback
        if (!StringUtils.hasText(GlobalContext.getString(GlobalContext.KEY_USER_ID))) {
            String spaceId = request.getHeader("X-Space-Id");
            if (StringUtils.hasText(spaceId)) {
                GlobalContext.set(GlobalContext.KEY_USER_ID, spaceId);
            }
        }

        // 3. 核心参数兜底逻辑：如果仍然没有用户标识，设为默认
        if (!StringUtils.hasText(GlobalContext.getString(GlobalContext.KEY_USER_ID))) {
            GlobalContext.set(GlobalContext.KEY_USER_ID, GlobalContext.DEFAULT_USER_ID);
        }

        return true;
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler, Exception ex) {
        GlobalContext.clear(); // 务必清理，防止线程池复用导致的数据串台
    }
}
