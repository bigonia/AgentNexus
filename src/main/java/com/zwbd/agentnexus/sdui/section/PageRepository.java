package com.zwbd.agentnexus.sdui.section;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface PageRepository extends JpaRepository<SduiPageEntity, String> {

    Optional<SduiPageEntity> findByPageId(String pageId);

    void deleteByPageId(String pageId);
}
