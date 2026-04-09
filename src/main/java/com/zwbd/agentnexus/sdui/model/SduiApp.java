package com.zwbd.agentnexus.sdui.model;

import com.zwbd.agentnexus.utils.StringListConverter;
import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Data;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.TenantId;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Data
@Entity
@Table(name = "sdui_app")
public class SduiApp {

    @TenantId
    @Column(name = "space_id", nullable = false, updatable = false)
    private String spaceId;

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(length = 64)
    private String id;

    @Column(nullable = false, length = 40)
    private String name;

    @Column(nullable = false, length = 200)
    private String description;

    @Convert(converter = StringListConverter.class)
    @Column(name = "scene_tags", columnDefinition = "text")
    private List<String> sceneTags = new ArrayList<>();

    @Convert(converter = StringListConverter.class)
    @Column(name = "selected_asset_set_ids", columnDefinition = "text")
    private List<String> selectedAssetSetIds = new ArrayList<>();

    @Column(nullable = false, length = 16)
    private String status = "DRAFT";

    @Column(nullable = false, length = 32)
    private String entryMode = "PYTHON_SCRIPT";

    @Column(nullable = false, length = 64)
    private String createdBy = "system";

    @CreationTimestamp
    private LocalDateTime createdAt;

    @UpdateTimestamp
    private LocalDateTime updatedAt;
}
