package com.zwbd.agentnexus.sdui.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.zwbd.agentnexus.common.web.GlobalContext;
import com.zwbd.agentnexus.sdui.dto.SduiControlDispatchResult;
import com.zwbd.agentnexus.sdui.dto.SduiDeviceControlRequest;
import com.zwbd.agentnexus.sdui.model.SduiDevice;
import com.zwbd.agentnexus.sdui.model.SduiDeviceCommand;
import com.zwbd.agentnexus.sdui.model.SduiDeviceTelemetry;
import com.zwbd.agentnexus.sdui.repo.SduiDeviceCommandRepository;
import com.zwbd.agentnexus.sdui.repo.SduiDeviceRepository;
import com.zwbd.agentnexus.sdui.repo.SduiDeviceTelemetryRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class SduiDeviceService {

    private final SduiDeviceRepository deviceRepository;
    private final SduiDeviceTelemetryRepository telemetryRepository;
    private final SduiDeviceCommandRepository commandRepository;
    private final SduiProtocolService protocolService;
    private final ObjectMapper objectMapper;

    private static final long OFFLINE_TIMEOUT_SECONDS = 90L;
    private static final long COMMAND_TIMEOUT_SECONDS = 10L;
    private static final long CLAIM_CODE_TTL_MINUTES = 15L;
    private static final DateTimeFormatter CLAIM_EXPIRE_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    @Transactional
    public SduiDevice onHeartbeat(String deviceId, JsonNode payload) {
        SduiDevice device = deviceRepository.findById(deviceId).orElseGet(() -> {
            SduiDevice d = new SduiDevice();
            d.setDeviceId(deviceId);
            d.setName("device-" + deviceId);
            d.setOwnerSpaceId("");
            d.setRegistrationStatus("UNCLAIMED");
            return d;
        });

        device.setStatus("ONLINE");
        device.setLastSeenAt(LocalDateTime.now());

        if (!isClaimed(device)) {
            device.setRegistrationStatus("UNCLAIMED");
            if (!StringUtils.hasText(device.getClaimCode()) || isClaimCodeExpired(device)) {
                issueClaimCode(device);
            }
        }

        device = deviceRepository.save(device);

        if (isClaimed(device)) {
            SduiDeviceTelemetry telemetry = new SduiDeviceTelemetry();
            telemetry.setDeviceId(deviceId);
            telemetry.setWifiRssi(payload.path("wifi_rssi").isMissingNode() ? null : payload.path("wifi_rssi").asInt());
            telemetry.setTemperature(payload.path("temperature").isMissingNode() ? null : payload.path("temperature").asDouble());
            telemetry.setFreeHeapInternal(payload.path("free_heap_internal").isMissingNode() ? null : payload.path("free_heap_internal").asInt());
            telemetry.setFreeHeapTotal(payload.path("free_heap_total").isMissingNode() ? null : payload.path("free_heap_total").asInt());
            telemetry.setUptimeS(payload.path("uptime_s").isMissingNode() ? null : payload.path("uptime_s").asInt());
            telemetryRepository.save(telemetry);
        }

        return device;
    }

    @Transactional
    public void updateCurrentPage(String deviceId, String pageId) {
        SduiDevice device = deviceRepository.findById(deviceId).orElseGet(() -> {
            SduiDevice d = new SduiDevice();
            d.setDeviceId(deviceId);
            d.setName("device-" + deviceId);
            d.setOwnerSpaceId("");
            d.setRegistrationStatus("UNCLAIMED");
            issueClaimCode(d);
            return d;
        });
        device.setCurrentPageId(pageId);
        device.setLastSeenAt(LocalDateTime.now());
        if (device.getStatus() == null) {
            device.setStatus("ONLINE");
        }
        deviceRepository.save(device);
    }

    @Transactional(readOnly = true)
    public long countDevices() {
        return deviceRepository.countByOwnerSpaceId(currentSpaceId());
    }

    @Transactional(readOnly = true)
    public long countOnlineDevices() {
        refreshOnlineStatus();
        return deviceRepository.countByOwnerSpaceIdAndStatusIgnoreCase(currentSpaceId(), "ONLINE");
    }

    @Transactional(readOnly = true)
    public long countOfflineDevices() {
        refreshOnlineStatus();
        return deviceRepository.countByOwnerSpaceIdAndStatusIgnoreCase(currentSpaceId(), "OFFLINE");
    }

    @Transactional(readOnly = true)
    public List<SduiDevice> listDevices() {
        refreshOnlineStatus();
        return deviceRepository.findByOwnerSpaceId(currentSpaceId());
    }

    @Transactional(readOnly = true)
    public List<SduiDevice> listUnclaimedDevices() {
        refreshOnlineStatus();
        return deviceRepository.findByRegistrationStatus("UNCLAIMED");
    }

    @Transactional(readOnly = true)
    public Optional<SduiDevice> getDevice(String deviceId) {
        refreshOnlineStatus();
        return deviceRepository.findById(deviceId)
                .filter(d -> currentSpaceId().equals(d.getOwnerSpaceId()));
    }

    @Transactional(readOnly = true)
    public List<SduiDeviceTelemetry> telemetry(String deviceId) {
        requireOwnedByCurrentSpace(deviceId);
        return telemetryRepository.findTop50ByDeviceIdOrderByCreatedAtDesc(deviceId);
    }

    @Transactional
    public SduiControlDispatchResult controlDevice(String deviceId, SduiDeviceControlRequest req) {
        requireOwnedByCurrentSpace(deviceId);

        String action = normalizeAction(req.command());
        Integer value = normalizeValue(req.value());
        String cmdId = UUID.randomUUID().toString();

        ObjectNode payload = objectMapper.createObjectNode();
        payload.put("cmd_id", cmdId);
        payload.put("action", action);
        payload.put("value", value);

        boolean sent = protocolService.sendControl(deviceId, payload);
        SduiDeviceCommand command = new SduiDeviceCommand();
        command.setDeviceId(deviceId);
        command.setTopic("cmd/control");
        command.setCmdId(cmdId);
        command.setAction(action);
        command.setRequestedValue(value);
        command.setAppliedValue(null);
        command.setReason("");
        command.setPayload(payload.toString());
        command.setStatus(sent ? "SENT" : "FAILED");
        commandRepository.save(command);
        return new SduiControlDispatchResult(cmdId, action, value, sent, command.getStatus());
    }

    @Transactional
    public void handleControlAck(String deviceId, JsonNode payload) {
        String cmdId = payload.path("cmd_id").asText(null);
        if (cmdId == null || cmdId.isBlank()) {
            return;
        }
        String status = payload.path("status").asText("ERROR").toUpperCase();
        String reason = payload.path("reason").asText("");
        String action = payload.path("action").asText("");
        Integer requestedValue = payload.path("requested_value").isMissingNode() ? null : payload.path("requested_value").asInt();
        Integer appliedValue = payload.path("applied_value").isMissingNode() ? null : payload.path("applied_value").asInt();
        Long ts = payload.path("ts").isMissingNode() ? null : payload.path("ts").asLong();

        SduiDeviceCommand command = commandRepository.findFirstByDeviceIdAndCmdIdOrderByCreatedAtDesc(deviceId, cmdId).orElse(null);
        if (command == null) {
            return;
        }
        command.setStatus(switch (status) {
            case "ACKED" -> "ACKED";
            case "REJECTED" -> "REJECTED";
            default -> "ERROR";
        });
        if (!action.isBlank()) {
            command.setAction(action);
        }
        if (requestedValue != null) {
            command.setRequestedValue(requestedValue);
        }
        command.setAppliedValue(appliedValue);
        command.setReason(reason);
        command.setAckTs(ts);
        commandRepository.save(command);
    }

    @Transactional
    public int markTimedOutCommands() {
        LocalDateTime threshold = LocalDateTime.now().minusSeconds(COMMAND_TIMEOUT_SECONDS);
        List<SduiDeviceCommand> sentCommands = commandRepository.findByStatusAndCreatedAtBefore("SENT", threshold);
        for (SduiDeviceCommand cmd : sentCommands) {
            cmd.setStatus("TIMEOUT");
            if (cmd.getReason() == null || cmd.getReason().isBlank()) {
                cmd.setReason("ack_timeout");
            }
            commandRepository.save(cmd);
        }
        return sentCommands.size();
    }

    @Transactional
    public SduiDevice claimDevice(String deviceId, String claimCode, String deviceName) {
        SduiDevice device = deviceRepository.findById(deviceId)
                .orElseThrow(() -> new IllegalArgumentException("device not found"));

        if (!StringUtils.hasText(claimCode) || !claimCode.trim().equalsIgnoreCase(nullSafe(device.getClaimCode()))) {
            throw new IllegalArgumentException("invalid claim code");
        }
        if (isClaimCodeExpired(device)) {
            throw new IllegalArgumentException("claim code expired");
        }

        String currentSpaceId = currentSpaceId();
        if (StringUtils.hasText(device.getOwnerSpaceId()) && !currentSpaceId.equals(device.getOwnerSpaceId())) {
            throw new IllegalArgumentException("device already claimed by another space");
        }

        device.setOwnerSpaceId(currentSpaceId);
        device.setRegistrationStatus("CLAIMED");
        device.setClaimedAt(LocalDateTime.now());
        device.setClaimCode(null);
        device.setClaimCodeExpireAt(null);
        if (StringUtils.hasText(deviceName)) {
            device.setName(deviceName.trim());
        }

        return deviceRepository.save(device);
    }

    public JsonNode registrationPagePayload(SduiDevice device) {
        ObjectNode payload = objectMapper.createObjectNode();
        payload.put("page_id", "register");
        payload.put("safe_pad", 10);
        ArrayNode children = payload.putArray("children");

        ObjectNode bgScene = children.addObject();
        bgScene.put("type", "scene");
        bgScene.put("id", "register_bg_scene");
        bgScene.put("bg_color", "#061423");
        ObjectNode bgAnim = bgScene.putObject("anim");
        bgAnim.put("type", "color_pulse");
        bgAnim.put("duration", 1800);
        bgAnim.put("repeat", 0);
        bgAnim.put("color_a", "#061423");
        bgAnim.put("color_b", "#0E2740");

        ObjectNode root = children.addObject();
        root.put("type", "container");
        root.put("id", "register_root");
        root.put("w", "100%");
        root.put("h", "100%");
        root.put("flex", "column");
        root.put("justify", "center");
        root.put("align_items", "center");
        root.put("gap", 8);
        ArrayNode rootChildren = root.putArray("children");

        ObjectNode logoBadge = rootChildren.addObject();
        logoBadge.put("type", "container");
        logoBadge.put("id", "register_logo_badge");
        logoBadge.put("w", 64);
        logoBadge.put("h", 64);
        logoBadge.put("radius", 32);
        logoBadge.put("bg_color", "#173A5B");
        logoBadge.put("border_w", 1);
        logoBadge.put("border_color", "#6EC1FF");
        logoBadge.put("shadow_w", 8);
        logoBadge.put("shadow_color", "#0E2740");
        logoBadge.put("flex", "column");
        logoBadge.put("justify", "center");
        logoBadge.put("align_items", "center");
        ObjectNode logoAnim = logoBadge.putObject("anim");
        logoAnim.put("type", "breathe");
        logoAnim.put("duration", 1200);
        logoAnim.put("repeat", 0);
        ArrayNode logoChildren = logoBadge.putArray("children");

        ObjectNode logoText = logoChildren.addObject();
        logoText.put("type", "label");
        logoText.put("id", "register_logo_text");
        logoText.put("text", "AN");
        logoText.put("font_size", 24);
        logoText.put("text_color", "#E8F3FF");

        ObjectNode card = rootChildren.addObject();
        card.put("type", "container");
        card.put("id", "register_card");
        card.put("w", "92%");
        card.put("h", "content");
        card.put("pad", 10);
        card.put("radius", 18);
        card.put("bg_color", "#10263A");
        card.put("border_w", 1);
        card.put("border_color", "#5FB2FF70");
        card.put("flex", "column");
        card.put("gap", 6);
        ObjectNode cardAnim = card.putObject("anim");
        cardAnim.put("type", "slide_in");
        cardAnim.put("from", "bottom");
        cardAnim.put("duration", 360);
        ArrayNode cardChildren = card.putArray("children");

        ObjectNode title = cardChildren.addObject();
        title.put("type", "label");
        title.put("id", "title");
        title.put("text", "Ready To Register");
        title.put("font_size", 20);
        title.put("text_color", "#E8F3FF");

        ObjectNode subtitle = cardChildren.addObject();
        subtitle.put("type", "label");
        subtitle.put("id", "subtitle");
        subtitle.put("text", "Claim this terminal from the admin panel.");
        subtitle.put("font_size", 12);
        subtitle.put("text_color", "#9AB8D5");

        ObjectNode deviceBlock = cardChildren.addObject();
        deviceBlock.put("type", "container");
        deviceBlock.put("id", "device_block");
        deviceBlock.put("w", "100%");
        deviceBlock.put("pad", 8);
        deviceBlock.put("radius", 10);
        deviceBlock.put("bg_color", "#17324B");
        deviceBlock.put("flex", "column");
        deviceBlock.put("gap", 2);
        ArrayNode deviceChildren = deviceBlock.putArray("children");

        ObjectNode deviceLabel = deviceChildren.addObject();
        deviceLabel.put("type", "label");
        deviceLabel.put("id", "device_label");
        deviceLabel.put("text", "Device ID");
        deviceLabel.put("font_size", 11);
        deviceLabel.put("text_color", "#80A8CC");

        ObjectNode deviceLine = deviceChildren.addObject();
        deviceLine.put("type", "label");
        deviceLine.put("id", "device_id");
        deviceLine.put("w", "100%");
        deviceLine.put("text", nullSafe(device.getDeviceId()));
        deviceLine.put("long_mode", "marquee");
        deviceLine.put("font_size", 14);
        deviceLine.put("text_color", "#DFF1FF");

        ObjectNode claimBlock = cardChildren.addObject();
        claimBlock.put("type", "container");
        claimBlock.put("id", "claim_block");
        claimBlock.put("w", "100%");
        claimBlock.put("pad", 8);
        claimBlock.put("radius", 10);
        claimBlock.put("bg_color", "#1B3E5E");
        claimBlock.put("border_w", 1);
        claimBlock.put("border_color", "#4EA8EF");
        claimBlock.put("flex", "column");
        claimBlock.put("gap", 2);
        ArrayNode claimChildren = claimBlock.putArray("children");

        ObjectNode claimLabel = claimChildren.addObject();
        claimLabel.put("type", "label");
        claimLabel.put("id", "claim_label");
        claimLabel.put("text", "Claim Code");
        claimLabel.put("font_size", 11);
        claimLabel.put("text_color", "#93C7F1");

        ObjectNode codeLine = claimChildren.addObject();
        codeLine.put("type", "label");
        codeLine.put("id", "claim_code");
        codeLine.put("text", nullSafe(device.getClaimCode()));
        codeLine.put("font_size", 20);
        codeLine.put("text_color", "#FFFFFF");
        ObjectNode codeAnim = codeLine.putObject("anim");
        codeAnim.put("type", "blink");
        codeAnim.put("duration", 1000);
        codeAnim.put("repeat", 0);

        ObjectNode expireLine = claimChildren.addObject();
        expireLine.put("type", "label");
        expireLine.put("id", "claim_expire_at");
        expireLine.put("text", "Expires at: " + formatClaimExpireAt(device));
        expireLine.put("font_size", 11);
        expireLine.put("text_color", "#B8D9F5");

        ObjectNode hint = cardChildren.addObject();
        hint.put("type", "label");
        hint.put("id", "hint");
        hint.put("w", "100%");
        hint.put("long_mode", "wrap");
        hint.put("text", "Path: Admin Panel > Devices > Unclaimed > Claim.");
        hint.put("font_size", 11);
        hint.put("text_color", "#9AB8D5");

        return payload;
    }

    public JsonNode waitingForPublishPagePayload(SduiDevice device) {
        ObjectNode payload = objectMapper.createObjectNode();
        payload.put("page_id", "waiting_publish");
        payload.put("safe_pad", 14);
        ArrayNode children = payload.putArray("children");

        ObjectNode root = children.addObject();
        root.put("type", "container");
        root.put("id", "waiting_root");
        root.put("w", "100%");
        root.put("h", "100%");
        root.put("flex", "column");
        root.put("justify", "center");
        root.put("align_items", "center");
        root.put("gap", 10);
        ArrayNode rootChildren = root.putArray("children");

        ObjectNode card = rootChildren.addObject();
        card.put("type", "container");
        card.put("id", "waiting_card");
        card.put("w", "92%");
        card.put("h", "content");
        card.put("pad", 14);
        card.put("radius", 20);
        card.put("bg_color", "#10263A");
        card.put("border_w", 1);
        card.put("border_color", "#5FB2FF70");
        card.put("flex", "column");
        card.put("gap", 8);
        ArrayNode cardChildren = card.putArray("children");

        ObjectNode title = cardChildren.addObject();
        title.put("type", "label");
        title.put("id", "title");
        title.put("text", "Device Connected");
        title.put("font_size", 22);
        title.put("text_color", "#E8F3FF");

        ObjectNode status = cardChildren.addObject();
        status.put("type", "label");
        status.put("id", "status");
        status.put("text", "Claimed. Waiting for app publish.");
        status.put("font_size", 13);
        status.put("text_color", "#9AB8D5");

        ObjectNode deviceLine = cardChildren.addObject();
        deviceLine.put("type", "label");
        deviceLine.put("id", "device_id");
        deviceLine.put("w", "100%");
        deviceLine.put("text", "Device: " + nullSafe(device.getDeviceId()));
        deviceLine.put("long_mode", "marquee");
        deviceLine.put("font_size", 14);
        deviceLine.put("text_color", "#DFF1FF");

        ObjectNode hint = cardChildren.addObject();
        hint.put("type", "label");
        hint.put("id", "hint");
        hint.put("text", "Publish an app to enter business UI.");
        hint.put("font_size", 12);
        hint.put("text_color", "#9AB8D5");
        ObjectNode hintAnim = hint.putObject("anim");
        hintAnim.put("type", "breathe");
        hintAnim.put("duration", 1400);
        hintAnim.put("repeat", 0);

        return payload;
    }

    @Transactional
    public void refreshOnlineStatus() {
        LocalDateTime now = LocalDateTime.now();
        List<SduiDevice> devices = deviceRepository.findAll();
        for (SduiDevice device : devices) {
            LocalDateTime lastSeen = device.getLastSeenAt();
            if (lastSeen == null) {
                continue;
            }
            boolean offline = lastSeen.isBefore(now.minusSeconds(OFFLINE_TIMEOUT_SECONDS));
            String expectedStatus = offline ? "OFFLINE" : "ONLINE";
            if (!expectedStatus.equalsIgnoreCase(device.getStatus())) {
                device.setStatus(expectedStatus);
                deviceRepository.save(device);
            }
        }
    }

    private boolean isClaimed(SduiDevice device) {
        return StringUtils.hasText(device.getOwnerSpaceId());
    }

    private void issueClaimCode(SduiDevice device) {
        String code = UUID.randomUUID().toString().replace("-", "").substring(0, 6).toUpperCase();
        device.setClaimCode(code);
        device.setClaimCodeExpireAt(LocalDateTime.now().plusMinutes(CLAIM_CODE_TTL_MINUTES));
    }

    private boolean isClaimCodeExpired(SduiDevice device) {
        if (device.getClaimCodeExpireAt() == null) {
            return true;
        }
        return LocalDateTime.now().isAfter(device.getClaimCodeExpireAt());
    }

    private void requireOwnedByCurrentSpace(String deviceId) {
        SduiDevice device = deviceRepository.findById(deviceId)
                .orElseThrow(() -> new IllegalArgumentException("device not found"));
        if (!currentSpaceId().equals(device.getOwnerSpaceId())) {
            throw new IllegalArgumentException("device does not belong to current space");
        }
    }

    private String currentSpaceId() {
        String spaceId = GlobalContext.getSpaceId();
        if (!StringUtils.hasText(spaceId)) {
            throw new IllegalStateException("space_id is required");
        }
        return spaceId;
    }

    private String normalizeAction(String command) {
        if (command == null) {
            throw new IllegalArgumentException("command must not be null");
        }
        String action = command.trim().toLowerCase();
        if (!"brightness".equals(action) && !"volume".equals(action)) {
            throw new IllegalArgumentException("unsupported command, only brightness|volume are supported");
        }
        return action;
    }

    private Integer normalizeValue(Object value) {
        if (value == null) {
            throw new IllegalArgumentException("value must not be null");
        }
        int v;
        if (value instanceof Number n) {
            v = n.intValue();
        } else {
            v = Integer.parseInt(value.toString());
        }
        if (v < 0 || v > 100) {
            throw new IllegalArgumentException("value must be in [0,100]");
        }
        return v;
    }

    private String nullSafe(String value) {
        return value == null ? "" : value;
    }

    private String formatClaimExpireAt(SduiDevice device) {
        if (device.getClaimCodeExpireAt() == null) {
            return "--";
        }
        return device.getClaimCodeExpireAt().format(CLAIM_EXPIRE_FORMATTER);
    }
}
