package com.zwbd.agentnexus.sdui.repo;

import com.zwbd.agentnexus.sdui.model.SduiApp;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface SduiAppRepository extends JpaRepository<SduiApp, String> {
    Optional<SduiApp> findByName(String name);
}

