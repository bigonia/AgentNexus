package com.zwbd.agentnexus.sdui.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.zwbd.agentnexus.common.web.GlobalContext;
import com.zwbd.agentnexus.sdui.SduiMessage;
import com.zwbd.agentnexus.sdui.dto.SduiPublishRequest;
import com.zwbd.agentnexus.sdui.model.SduiApp;
import com.zwbd.agentnexus.sdui.model.SduiAppVersion;
import com.zwbd.agentnexus.sdui.model.SduiDeviceAppBinding;
import com.zwbd.agentnexus.sdui.repo.SduiAppRepository;
import com.zwbd.agentnexus.sdui.repo.SduiAppVersionRepository;
import com.zwbd.agentnexus.sdui.repo.SduiDeviceAppBindingRepository;
import com.zwbd.agentnexus.sdui.repo.SduiDeviceRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class SduiAppRuntimeService {

    private final SduiAppRepository appRepository;
    private final SduiAppVersionRepository versionRepository;
    private final SduiDeviceRepository deviceRepository;
    private final SduiDeviceAppBindingRepository bindingRepository;
    private final SduiScriptRuntimeManager runtimeManager;
    private final SduiProtocolService protocolService;
    private final ObjectMapper objectMapper;
    private final SduiCapabilityService capabilityService;
    private final SduiRevisionService revisionService;

    @Transactional
    public int publish(String appId, SduiPublishRequest request) {
        SduiApp app = appRepository.findById(appId).orElseThrow(() -> new IllegalArgumentException("app not found"));
        SduiAppVersion version = resolveVersion(app, request.versionId());
        log.info("Publish start. appId={}, versionId={}, versionNo={}, requestedDevices={}",
                appId, version.getId(), version.getVersionNo(), request.deviceIds());
        version.setPublished(true);
        versionRepository.save(version);
        app.setStatus("PUBLISHED");
        appRepository.save(app);

        int successCount = 0;
        String currentSpaceId = GlobalContext.getSpaceId();
        for (String deviceId : request.deviceIds()) {
            deviceRepository.findById(deviceId).ifPresentOrElse(device -> {
                if (!StringUtils.hasText(device.getOwnerSpaceId()) || !currentSpaceId.equals(device.getOwnerSpaceId())) {
                    throw new IllegalArgumentException("device does not belong to current space: " + deviceId);
                }
                log.info("Publish device precheck ok. deviceId={}, ownerSpaceId={}, status={}",
                        deviceId, device.getOwnerSpaceId(), device.getStatus());
            }, () -> {
                throw new IllegalArgumentException("device not found: " + deviceId);
            });

            bindingRepository.findByDeviceId(deviceId).forEach(existing -> {
                if (Boolean.TRUE.equals(existing.getActive())) {
                    existing.setActive(false);
                    bindingRepository.save(existing);
                }
            });

            SduiDeviceAppBinding binding = bindingRepository.findByDeviceIdAndActiveTrue(deviceId).orElse(new SduiDeviceAppBinding());
            binding.setDeviceId(deviceId);
            binding.setApp(app);
            binding.setAppVersion(version);
            binding.setActive(true);
            bindingRepository.save(binding);

            Map<String, Object> ctx = capabilityService.buildRuntimeContext(app.getId(), deviceId);

            SduiScriptRuntimeManager.ScriptExecutionResult result = runtimeManager.invokeOnStart(version, deviceId, ctx);
            log.info("Publish on_start result. deviceId={}, action={}, pageId={}, hasPayload={}, error={}",
                    deviceId, result.action(), result.pageId(), result.payload() != null, result.error());
            capabilityService.applyStore(app.getId(), deviceId, result.store());
            if ("layout".equals(result.action()) && result.payload() != null) {
                JsonNode normalizedLayout = normalizeLayoutPayload(result.payload());
                boolean sent = protocolService.sendLayout(deviceId, normalizedLayout);
                log.info("Publish layout dispatch. deviceId={}, sent={}", deviceId, sent);
                if (sent) {
                    successCount++;
                }
            } else {
                log.warn("Publish skipped layout dispatch. deviceId={}, action={}, hasPayload={}, error={}",
                        deviceId, result.action(), result.payload() != null, result.error());
            }

            deviceRepository.findById(deviceId).ifPresent(device -> {
                device.setCurrentAppId(app.getId());
                device.setCurrentPageId(result.pageId());
                deviceRepository.save(device);
            });
        }
        log.info("Publish completed. appId={}, versionNo={}, sent={}, requested={}",
                appId, version.getVersionNo(), successCount, request.deviceIds().size());
        return successCount;
    }

    @Transactional
    public void handleEvent(String deviceId, SduiMessage msg) {
        SduiDeviceAppBinding binding = bindingRepository.findByDeviceIdAndActiveTrue(deviceId).orElse(null);
        if (binding == null) {
            return;
        }
        Map<String, Object> ctx = capabilityService.buildRuntimeContext(binding.getApp().getId(), deviceId);
        ctx.put("topic", msg.getTopic());

        Map<String, Object> eventPayload = objectMapper.convertValue(msg.getPayload(), Map.class);
        SduiScriptRuntimeManager.ScriptExecutionResult result = runtimeManager.invokeOnEvent(binding.getAppVersion(), deviceId, ctx, eventPayload);
        capabilityService.applyStore(binding.getApp().getId(), deviceId, result.store());

        if ("layout".equals(result.action()) && result.payload() != null) {
            protocolService.sendLayout(deviceId, normalizeLayoutPayload(result.payload()));
        } else if ("update".equals(result.action()) && result.payload() != null) {
            JsonNode updatePayload = ensureRevisionPayload(deviceId, result.pageId(), result.payload());
            protocolService.sendUpdate(deviceId, updatePayload);
        }
    }

    private JsonNode ensureRevisionPayload(String deviceId, String pageId, JsonNode payload) {
        if (payload.isObject()) {
            if (!payload.has("page_id")) {
                ((com.fasterxml.jackson.databind.node.ObjectNode) payload).put("page_id", pageId);
            }
            if (!payload.has("revision")) {
                int revision = revisionService.nextRevision(deviceId, pageId);
                ((com.fasterxml.jackson.databind.node.ObjectNode) payload).put("revision", revision);
            }
            if (payload.has("ops") && !payload.has("transaction")) {
                ((com.fasterxml.jackson.databind.node.ObjectNode) payload).put("transaction", true);
            }
        }
        return payload;
    }

    private SduiAppVersion resolveVersion(SduiApp app, String versionId) {
        if (versionId != null && !versionId.isBlank()) {
            return versionRepository.findById(versionId)
                    .filter(v -> v.getApp().getId().equals(app.getId()))
                    .orElseThrow(() -> new IllegalArgumentException("version not found"));
        }
        return versionRepository.findFirstByAppOrderByVersionNoDesc(app)
                .orElseThrow(() -> new IllegalArgumentException("no app version available"));
    }

    private JsonNode normalizeLayoutPayload(JsonNode raw) {
        if (raw == null || raw.isNull()) {
            return objectMapper.createObjectNode();
        }
        if (!raw.isObject()) {
            return raw;
        }
        return normalizeNode(raw);
    }

    private JsonNode normalizeNode(JsonNode node) {
        if (node == null || node.isNull()) {
            return node;
        }
        if (node.isArray()) {
            ArrayNode arr = objectMapper.createArrayNode();
            node.forEach(child -> arr.add(normalizeNode(child)));
            return arr;
        }
        if (!node.isObject()) {
            return node;
        }

        ObjectNode src = (ObjectNode) node;
        ObjectNode dst = objectMapper.createObjectNode();
        src.fields().forEachRemaining(entry -> {
            String k = entry.getKey();
            if (!"children".equals(k) && !"props".equals(k)) {
                dst.set(k, normalizeNode(entry.getValue()));
            }
        });

        String type = src.path("type").asText("");
        ObjectNode props = src.path("props").isObject() ? (ObjectNode) src.path("props") : null;

        if ("column".equals(type) || "row".equals(type)) {
            dst.put("type", "container");
            dst.put("flex", "column".equals(type) ? "column" : "row");
            if (props != null) {
                String mainAxis = props.path("mainAxisAlignment").asText("");
                if (!mainAxis.isBlank()) {
                    dst.put("justify", mapMainAxis(mainAxis));
                }
            }
        } else if ("text".equals(type)) {
            dst.put("type", "label");
        }

        if (props != null) {
            copyProp(props, "id", dst, "id");
            copyProp(props, "text", dst, "text");
            copyProp(props, "src", dst, "src");
            copyProp(props, "width", dst, "img_w");
            copyProp(props, "height", dst, "img_h");
            copyProp(props, "fontSize", dst, "font_size");
            copyProp(props, "color", dst, "text_color");
            copyProp(props, "cornerRadius", dst, "radius");
        }

        if (src.has("children")) {
            dst.set("children", normalizeNode(src.get("children")));
        }
        return dst;
    }

    private void copyProp(ObjectNode props, String fromKey, ObjectNode dst, String toKey) {
        if (props.has(fromKey) && !props.get(fromKey).isNull()) {
            dst.set(toKey, normalizeNode(props.get(fromKey)));
        }
    }

    private String mapMainAxis(String value) {
        return switch (value) {
            case "center" -> "center";
            case "start", "startOfLine" -> "start";
            case "end", "endOfLine" -> "end";
            case "spaceBetween" -> "space_between";
            case "spaceAround" -> "space_around";
            case "spaceEvenly" -> "space_evenly";
            default -> "start";
        };
    }
}
