package com.zwbd.agentnexus.sdui.ui.repo;

import com.zwbd.agentnexus.sdui.ui.SduiUiTemplateEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface SduiUiTemplateRepository extends JpaRepository<SduiUiTemplateEntity, String> {
    Optional<SduiUiTemplateEntity> findByTemplateKey(String templateKey);
    List<SduiUiTemplateEntity> findAllByOrderByUpdatedAtDesc();
}
