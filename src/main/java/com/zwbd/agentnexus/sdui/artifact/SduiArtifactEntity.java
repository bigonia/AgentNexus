package com.zwbd.agentnexus.sdui.artifact;

import com.zwbd.agentnexus.common.web.JsonMapConverter;
import jakarta.persistence.*;
import lombok.Data;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.TenantId;
import org.hibernate.type.SqlTypes;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

@Data
@Entity
@Table(name = "sdui_artifact", indexes = {
        @Index(name = "idx_sdui_artifact_device_type_created", columnList = "device_id,type,created_at"),
        @Index(name = "idx_sdui_artifact_run", columnList = "workflow_id,run_id")
})
public class SduiArtifactEntity {

    @TenantId
    @Column(name = "user_id", updatable = false)
    private String userId;

    @Id
    @Column(name = "artifact_id", length = 64)
    private String artifactId;

    @Column(name = "device_id", nullable = false, length = 64)
    private String deviceId;

    @Column(name = "workflow_id", length = 64)
    private String workflowId;

    @Column(name = "run_id", length = 64)
    private String runId;

    @Column(name = "node_id", length = 64)
    private String nodeId;

    @Column(nullable = false, length = 64)
    private String type;

    @Column(nullable = false, length = 128)
    private String mimeType;

    @Convert(converter = JsonMapConverter.class)
    @Column(nullable = false, columnDefinition = "text")
    private Map<String, Object> metadata = new LinkedHashMap<>();

    @Basic(fetch = FetchType.LAZY)
    @JdbcTypeCode(SqlTypes.VARBINARY)
    @Column(columnDefinition = "bytea")
    private byte[] blob;

    @CreationTimestamp
    @Column(name = "created_at")
    private LocalDateTime createdAt;
}
