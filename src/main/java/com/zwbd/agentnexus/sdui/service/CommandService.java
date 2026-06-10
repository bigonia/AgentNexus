package com.zwbd.agentnexus.sdui.service;

import com.zwbd.agentnexus.sdui.dto.SduiControlDispatchResult;
import com.zwbd.agentnexus.sdui.model.SduiDeviceCommand;
import com.zwbd.agentnexus.sdui.repo.SduiDeviceCommandRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class CommandService {

    private final SduiDeviceCommandRepository commandRepository;
    private final CommandDispatcher dispatcher;
    private final AudioService audioService;
    private final CommandResultStreamService commandResultStreamService;
    private final ObjectMapper objectMapper;

    private static final long COMMAND_TIMEOUT_SECONDS = 10L;

    @Transactional
    public SduiControlDispatchResult dispatchCommand(String deviceId, String action, Object value) {
        SduiControlDispatchResult serverHandled = dispatchServerHandledCommand(deviceId, action, value);
        if (serverHandled != null) {
            return serverHandled;
        }

        SduiControlDispatchResult deviceHandled = dispatchDeviceHandledCommand(deviceId, action, value);
        if (deviceHandled != null) {
            return deviceHandled;
        }

        Integer v = normalizeRequestedValue(value);
        CommandDispatcher.DispatchResult result = dispatcher.dispatch(deviceId, action,
                v != null ? v : value);

        SduiDeviceCommand command = new SduiDeviceCommand();
        command.setDeviceId(deviceId);
        command.setTopic(result.topic());
        command.setCmdId(result.cmdId());
        command.setCommand(action);
        command.setAction(result.action());
        command.setRequestedValue(v);
        command.setPayload(result.payload());
        command.setStatus(result.sent() ? "SENT" : "FAILED");
        commandRepository.save(command);
        commandResultStreamService.publishCommand(command, "dispatch");

        return new SduiControlDispatchResult(result.cmdId(), result.action(), v, result.sent(), command.getStatus());
    }

    private SduiControlDispatchResult dispatchServerHandledCommand(String deviceId, String action, Object value) {
        if ("audio.prompt.play".equals(action)) {
            String preset = extractStringParam(value, "preset", "notification");
            AudioService.PlayResult playResult = audioService.playPreset(deviceId, preset);
            String cmdId = persistServerHandledCommand(deviceId, action, value, playResult.sent(), null);
            return new SduiControlDispatchResult(cmdId, action, null, playResult.sent(),
                    playResult.sent() ? "ACKED" : "FAILED");
        }

        if ("audio.tts.speak".equals(action)) {
            String text = extractStringParam(value, "text", "");
            AudioService.PlayResult playResult = audioService.playTts(deviceId, text);
            String cmdId = persistServerHandledCommand(deviceId, action, value, playResult.sent(), null);
            return new SduiControlDispatchResult(cmdId, action, null, playResult.sent(),
                    playResult.sent() ? "ACKED" : "FAILED");
        }

        return null;
    }

    private SduiControlDispatchResult dispatchDeviceHandledCommand(String deviceId, String action, Object value) {
        if (!"rgb.effect.set".equals(action)) {
            return null;
        }

        Map<String, Object> params = normalizeMap(value);
        String mode = extractStringParam(params, "mode", "solid");
        String explicitAction = "solid".equals(mode) ? "rgb_set" : "rgb_policy";

        CommandDispatcher.DispatchResult result = dispatcher.dispatchWithAction(deviceId, action, explicitAction, params);
        SduiDeviceCommand command = new SduiDeviceCommand();
        command.setDeviceId(deviceId);
        command.setTopic(result.topic());
        command.setCmdId(result.cmdId());
        command.setCommand(action);
        command.setAction(result.action());
        command.setPayload(result.payload());
        command.setStatus(result.sent() ? "SENT" : "FAILED");
        commandRepository.save(command);
        commandResultStreamService.publishCommand(command, "dispatch");

        return new SduiControlDispatchResult(result.cmdId(), result.action(), null, result.sent(), command.getStatus());
    }

    private String persistServerHandledCommand(String deviceId, String action, Object value,
                                               boolean sent, Integer requestedValue) {
        String cmdId = UUID.randomUUID().toString();
        SduiDeviceCommand command = new SduiDeviceCommand();
        command.setDeviceId(deviceId);
        command.setTopic("server");
        command.setCmdId(cmdId);
        command.setCommand(action);
        command.setAction(action);
        command.setRequestedValue(requestedValue);
        command.setPayload(serializePayload(value));
        command.setStatus(sent ? "ACKED" : "FAILED");
        commandRepository.save(command);
        commandResultStreamService.publishCommand(command, "dispatch");
        log.info("Server-side command handled: device={} cmd={} cmdId={} sent={}", deviceId, action, cmdId, sent);
        return cmdId;
    }

    private String extractStringParam(Object value, String key, String defaultValue) {
        if (value instanceof Map<?, ?> map) {
            Object raw = map.get(key);
            return raw != null ? raw.toString() : defaultValue;
        }
        return value != null ? value.toString() : defaultValue;
    }

    private String serializePayload(Object value) {
        if (value == null) {
            return "{}";
        }
        if (value instanceof Map<?, ?> map) {
            try {
                Map<String, Object> normalized = new LinkedHashMap<>();
                for (var entry : map.entrySet()) {
                    normalized.put(String.valueOf(entry.getKey()), entry.getValue());
                }
                return objectMapper.writeValueAsString(normalized);
            } catch (JsonProcessingException e) {
                log.warn("Failed to serialize command payload as JSON: {}", e.getMessage());
            }
        }
        return String.valueOf(value);
    }

    private Map<String, Object> normalizeMap(Object value) {
        Map<String, Object> normalized = new LinkedHashMap<>();
        if (value instanceof Map<?, ?> map) {
            for (var entry : map.entrySet()) {
                normalized.put(String.valueOf(entry.getKey()), entry.getValue());
            }
        }
        return normalized;
    }

    @Transactional
    public void handleBinaryAck(int seq, int code, String detail) {
        log.info("Binary ACK seq={} code={} detail={}", seq, code, detail);
    }

    @Transactional
    public void handleControlAck(String deviceId, String cmdId, String status, String reason) {
        commandRepository.findFirstByDeviceIdAndCmdIdOrderByCreatedAtDesc(deviceId, cmdId).ifPresent(cmd -> {
            cmd.setStatus(switch (status.toUpperCase()) {
                case "ACKED" -> "ACKED";
                case "REJECTED" -> "REJECTED";
                default -> "ERROR";
            });
            cmd.setReason(reason);
            cmd.setAckTs(System.currentTimeMillis());
            commandRepository.save(cmd);
            commandResultStreamService.publishCommand(cmd, "ack");
        });
    }

    @Transactional
    @Scheduled(fixedDelayString = "${sdui.command.timeout-scan-ms:3000}")
    public int markTimedOutCommands() {
        LocalDateTime threshold = LocalDateTime.now().minusSeconds(COMMAND_TIMEOUT_SECONDS);
        List<SduiDeviceCommand> sent = commandRepository.findByStatusAndCreatedAtBefore("SENT", threshold);
        for (SduiDeviceCommand cmd : sent) {
            cmd.setStatus("TIMEOUT");
            if (cmd.getReason() == null || cmd.getReason().isBlank()) cmd.setReason("ack_timeout");
            commandRepository.save(cmd);
            commandResultStreamService.publishCommand(cmd, "timeout");
        }
        return sent.size();
    }

    private int normalizeValue(Object value) {
        if (value == null) throw new IllegalArgumentException("value must not be null");
        int v = value instanceof Number n ? n.intValue() : Integer.parseInt(value.toString());
        if (v < 0 || v > 100) throw new IllegalArgumentException("value must be in [0,100]");
        return v;
    }

    private Integer normalizeRequestedValue(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Number) {
            return normalizeValue(value);
        }
        if (value instanceof String s) {
            String trimmed = s.trim();
            if (trimmed.matches("\\d+")) {
                return normalizeValue(trimmed);
            }
        }
        return null;
    }
}
