package com.zwbd.agentnexus.sdui.service;

import com.zwbd.agentnexus.sdui.dto.SduiControlDispatchResult;
import com.zwbd.agentnexus.sdui.model.SduiDeviceCommand;
import com.zwbd.agentnexus.sdui.repo.SduiDeviceCommandRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class CommandService {

    private final SduiDeviceCommandRepository commandRepository;
    private final CommandDispatcher dispatcher;

    private static final long COMMAND_TIMEOUT_SECONDS = 10L;

    @Transactional
    public SduiControlDispatchResult dispatchCommand(String deviceId, String action, Object value) {
        Integer v = value != null ? normalizeValue(value) : null;
        CommandDispatcher.DispatchResult result = dispatcher.dispatch(deviceId, action,
                v != null ? v : value);

        SduiDeviceCommand command = new SduiDeviceCommand();
        command.setDeviceId(deviceId);
        command.setTopic(result.topic());
        command.setCmdId(result.cmdId());
        command.setAction(result.action());
        command.setRequestedValue(v);
        command.setPayload(result.payload());
        command.setStatus(result.sent() ? "SENT" : "FAILED");
        commandRepository.save(command);

        return new SduiControlDispatchResult(result.cmdId(), result.action(), v, result.sent(), command.getStatus());
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
            commandRepository.save(cmd);
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
        }
        return sent.size();
    }

    private int normalizeValue(Object value) {
        if (value == null) throw new IllegalArgumentException("value must not be null");
        int v = value instanceof Number n ? n.intValue() : Integer.parseInt(value.toString());
        if (v < 0 || v > 100) throw new IllegalArgumentException("value must be in [0,100]");
        return v;
    }
}
