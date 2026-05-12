package com.zwbd.agentnexus.sdui.repo;

import com.zwbd.agentnexus.sdui.model.SduiTrustedScriptInstance;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface SduiTrustedScriptInstanceRepository extends JpaRepository<SduiTrustedScriptInstance, String> {
    Optional<SduiTrustedScriptInstance> findByAppIdAndDeviceId(String appId, String deviceId);

    List<SduiTrustedScriptInstance> findTop200ByOrderByUpdatedAtDesc();

    long countByState(String state);
}
