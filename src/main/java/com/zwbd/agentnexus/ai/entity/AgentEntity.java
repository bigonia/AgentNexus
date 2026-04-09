package com.zwbd.agentnexus.ai.entity;

import com.zwbd.agentnexus.utils.StringListConverter;
import jakarta.persistence.*;
import lombok.Data;
import org.hibernate.annotations.TenantId;

import java.util.ArrayList;
import java.util.List;

/**
 * @Author: wnli
 * @Date: 2026/1/7 15:10
 * @Desc:
 */
@Data
@Entity
@Table(name = "ai_agents")
public class AgentEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "agent_id", nullable = false, unique = true)
    private String id;

    @TenantId
    @Column(name = "space_id", nullable = false, updatable = false)
    private String spaceId;

    @Column(name = "name", nullable = false)
    private String name; // 显示名称

    @Column(name = "model_name", nullable = false)
    private String modelName; // 例如: "qwen-plus", "gpt-4"

    @Column(name = "system_prompt", length = 4096)
    private String systemPrompt;

    // 存储工具名称列表，使用 JSON 转换器
    @Convert(converter = StringListConverter.class)
    @Column(name = "tool_names", columnDefinition = "TEXT")
    private List<String> toolNames = new ArrayList<>();

    // Provider 级授权（如 mcp-xxx）
    @Convert(converter = StringListConverter.class)
    @Column(name = "provider_ids", columnDefinition = "TEXT")
    private List<String> providerIds = new ArrayList<>();

    // Tool 级白名单（如 mcp-xxx:tool_name）
    @Convert(converter = StringListConverter.class)
    @Column(name = "tool_ids", columnDefinition = "TEXT")
    private List<String> toolIds = new ArrayList<>();

    // 存储工具名称列表，使用 JSON 转换器
    @Convert(converter = StringListConverter.class)
    @Column(name = "advisors", columnDefinition = "TEXT")
    private List<String> advisors = new ArrayList<>();

}
