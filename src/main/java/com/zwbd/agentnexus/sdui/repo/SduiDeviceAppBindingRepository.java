package com.zwbd.agentnexus.sdui.repo;

import com.zwbd.agentnexus.sdui.model.SduiDeviceAppBinding;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface SduiDeviceAppBindingRepository extends JpaRepository<SduiDeviceAppBinding, String> {
    Optional<SduiDeviceAppBinding> findByDeviceIdAndActiveTrue(String deviceId);

    List<SduiDeviceAppBinding> findByDeviceId(String deviceId);
}

