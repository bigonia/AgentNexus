package com.zwbd.agentnexus.sdui.repo;

import com.zwbd.agentnexus.sdui.model.SduiAsset;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface SduiAssetRepository extends JpaRepository<SduiAsset, String> {
    List<SduiAsset> findByNameContainingIgnoreCase(String keyword);
}

