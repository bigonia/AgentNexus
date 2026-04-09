package com.zwbd.agentnexus.sdui.repo;

import com.zwbd.agentnexus.sdui.model.SduiAssetBinding;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface SduiAssetBindingRepository extends JpaRepository<SduiAssetBinding, String> {
    List<SduiAssetBinding> findByApp_Id(String appId);

    Optional<SduiAssetBinding> findByApp_IdAndAsset_IdAndUsageType(String appId, String assetId, String usageType);

    Optional<SduiAssetBinding> findByIdAndApp_Id(String id, String appId);

    boolean existsByApp_IdAndAsset_Id(String appId, String assetId);
}
