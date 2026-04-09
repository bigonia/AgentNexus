package com.zwbd.agentnexus.sdui.repo;

import com.zwbd.agentnexus.sdui.model.SduiApp;
import com.zwbd.agentnexus.sdui.model.SduiAppVersion;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface SduiAppVersionRepository extends JpaRepository<SduiAppVersion, String> {
    List<SduiAppVersion> findByAppOrderByVersionNoDesc(SduiApp app);

    Optional<SduiAppVersion> findFirstByAppOrderByVersionNoDesc(SduiApp app);
}

