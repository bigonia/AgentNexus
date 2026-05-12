package com.zwbd.agentnexus.sdui.repo;

import com.zwbd.agentnexus.sdui.model.SduiTrustedScriptDeploy;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface SduiTrustedScriptDeployRepository extends JpaRepository<SduiTrustedScriptDeploy, String> {
    List<SduiTrustedScriptDeploy> findTop100ByAppIdOrderByCreatedAtDesc(String appId);
}
