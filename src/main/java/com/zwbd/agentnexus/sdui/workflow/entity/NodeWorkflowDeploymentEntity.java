package com.zwbd.agentnexus.sdui.workflow.entity;

import com.zwbd.agentnexus.common.web.JsonMapConverter;
import jakarta.persistence.*;
import lombok.Data;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.TenantId;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

@Data
@Entity
@Table(name = "sdui_node_workflow_deployment")
public class NodeWorkflowDeploymentEntity {

    @TenantId
    @Column(name = "user_id", updatable = false)
    private String userId;

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(length = 64)
    private String id;

    @Column(nullable = false, length = 64)
    private String workflowId;

    @Column(nullable = false)
    private Integer workflowVersion = 1;

    @Column(nullable = false, length = 24)
    private String status = "active";

    @Convert(converter = JsonMapConverter.class)
    @Column(nullable = false, columnDefinition = "text")
    private Map<String, Object> slotBindings = new LinkedHashMap<>();

    /**
     * 部署时固化的业务配置与平台步骤，形如
     * {@code deviceId → {triggers: [...], platformSteps: [...]}}。
     *
     * <p>为什么要固化而不是运行时重新组装：设备侧的本地响应序列在部署那一刻就已经下发并生效，
     * 平台侧的后续步骤必须与那一份严格对应。按当前能力 Schema 重新组装会引入漂移——设备已按旧配置
     * 执行，平台却按新 Schema 算出另一套步骤。</p>
     *
     * <p>同时它回答了"这台设备当时收到的是什么配置"这个运维问题。这是
     * 12_DESIGN_NOTES.md 未决问题 Q2（平台侧业务配置持久化到什么程度）的第一步；跨重启的兜底重发
     * 仍由 {@code BusinessConfigService} 的内存状态承担。</p>
     */
    @Convert(converter = JsonMapConverter.class)
    @Column(nullable = false, columnDefinition = "text")
    private Map<String, Object> businessConfigs = new LinkedHashMap<>();

    @CreationTimestamp
    private LocalDateTime deployedAt;

    private LocalDateTime stoppedAt;
}
