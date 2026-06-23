package com.zwbd.agentnexus.sdui.artifact;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface SduiArtifactRepository extends JpaRepository<SduiArtifactEntity, String> {
    Optional<SduiArtifactEntity> findFirstByDeviceIdAndTypeOrderByCreatedAtDesc(String deviceId, String type);
    List<SduiArtifactEntity> findByRunIdOrderByCreatedAtDesc(String runId);
    List<SduiArtifactEntity> findByWorkflowIdAndRunIdOrderByCreatedAtDesc(String workflowId, String runId);
    List<SduiArtifactEntity> findByWorkflowIdOrderByCreatedAtDesc(String workflowId);
}
