package com.zwbd.agentnexus.sdui.repo;

import com.zwbd.agentnexus.sdui.model.SduiAssetSet;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface SduiAssetSetRepository extends JpaRepository<SduiAssetSet, String> {
    List<SduiAssetSet> findByNameContainingIgnoreCase(String keyword);
}

