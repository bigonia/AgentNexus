package com.zwbd.agentnexus.utils;

import org.springframework.stereotype.Service;
import org.springframework.util.Assert;
import org.springframework.util.PropertyPlaceholderHelper;

import java.util.Map;
import java.util.Properties;

/**
 * @Author: wnli
 * @Date: 2025/12/1 16:38
 * @Desc:
 * 通用模版渲染服务
 * 职责：负责将结构化数据(Map)渲染为非结构化文本(String)
 * 技术选型：使用 Spring Core 自带的 PropertyPlaceholderHelper，支持 ${key} 格式
 */
@Service
public class TemplateRenderService {

    // 定义占位符的前缀和后缀，例如 ${name}
    private final PropertyPlaceholderHelper helper = new PropertyPlaceholderHelper("${", "}");

    /**
     * 渲染文本
     * @param template 用户定义的模版，例如 "用户 ${name} 的年龄是 ${age}"
     * @param variables 数据参数 map
     * @return 渲染后的文本
     */
    public String render(String template, Map<String, Object> variables) {
        Assert.hasText(template, "Template content must not be empty");
        if (variables == null || variables.isEmpty()) {
            return template;
        }

        // PropertyPlaceholderHelper 需要 Properties 或者 PlaceholderResolver
        // 这里使用简单的 Properties 适配，或者自定义 Resolver 以获得更高性能
        Properties props = new Properties();
        variables.forEach((k, v) -> {
            if (v != null) {
                props.put(k, v.toString());
            }
        });

        // 执行替换
        return helper.replacePlaceholders(template, props);
    }
}
