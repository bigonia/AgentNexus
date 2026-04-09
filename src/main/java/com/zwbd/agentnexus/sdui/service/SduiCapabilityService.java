package com.zwbd.agentnexus.sdui.service;

import com.zwbd.agentnexus.file.KnowledgeFile;
import com.zwbd.agentnexus.file.KnowledgeFileRepository;
import com.zwbd.agentnexus.sdui.model.SduiAsset;
import com.zwbd.agentnexus.sdui.model.SduiAssetBinding;
import com.zwbd.agentnexus.sdui.model.SduiDevice;
import com.zwbd.agentnexus.sdui.repo.SduiAssetBindingRepository;
import com.zwbd.agentnexus.sdui.repo.SduiDeviceRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Service
@RequiredArgsConstructor
public class SduiCapabilityService {

    private final SduiDeviceRepository deviceRepository;
    private final SduiAssetBindingRepository assetBindingRepository;
    private final KnowledgeFileRepository knowledgeFileRepository;

    private final ConcurrentHashMap<String, Map<String, Object>> store = new ConcurrentHashMap<>();

    public Map<String, Object> buildRuntimeContext(String appId, String deviceId) {
        Map<String, Object> ctx = new HashMap<>();
        ctx.put("device_id", deviceId);
        ctx.put("app_id", appId);
        ctx.put("device_state", deviceState(deviceId));
        ctx.put("assets", assetsForApp(appId));
        ctx.put("store", getStore(appId, deviceId));
        return ctx;
    }

    public void applyStore(String appId, String deviceId, Map<String, Object> latestStore) {
        if (latestStore == null) {
            return;
        }
        store.put(key(appId, deviceId), new HashMap<>(latestStore));
    }

    private Map<String, Object> getStore(String appId, String deviceId) {
        return new HashMap<>(store.computeIfAbsent(key(appId, deviceId), k -> new HashMap<>()));
    }

    private String key(String appId, String deviceId) {
        return appId + ":" + deviceId;
    }

    private Map<String, Object> deviceState(String deviceId) {
        return deviceRepository.findById(deviceId).map(this::toDeviceMap).orElseGet(HashMap::new);
    }

    private Map<String, Object> toDeviceMap(SduiDevice d) {
        Map<String, Object> m = new HashMap<>();
        m.put("device_id", d.getDeviceId());
        m.put("status", d.getStatus());
        m.put("current_app_id", d.getCurrentAppId());
        m.put("current_page_id", d.getCurrentPageId());
        m.put("last_seen_at", d.getLastSeenAt() == null ? null : d.getLastSeenAt().toString());
        return m;
    }

    private List<Map<String, Object>> assetsForApp(String appId) {
        List<SduiAssetBinding> bindings = assetBindingRepository.findByApp_Id(appId);
        return bindings.stream().map(binding -> {
            SduiAsset asset = binding.getAsset();
            KnowledgeFile file = knowledgeFileRepository.findById(asset.getFileId()).orElse(null);
            Map<String, Object> map = new HashMap<>();
            map.put("asset_id", asset.getId());
            map.put("asset_type", asset.getAssetType());
            map.put("usage_type", binding.getUsageType());
            map.put("name", asset.getName());
            map.put("tags", asset.getTags());
            map.put("file_id", asset.getFileId());
            map.put("processed_status", asset.getProcessedStatus());
            map.put("processed_payload", asset.getProcessedPayload());
            if (file != null) {
                map.put("original_filename", file.getOriginalFilename());
                map.put("stored_filename", file.getStoredFilename());
            }
            return map;
        }).toList();
    }
}
