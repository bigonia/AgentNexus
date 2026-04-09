package com.zwbd.agentnexus.sdui.service;

import com.zwbd.agentnexus.sdui.dto.SduiRuntimeWorkerStatus;
import com.zwbd.agentnexus.sdui.repo.SduiDeviceCommandRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class SduiOpsService {

    private final SduiDeviceService deviceService;
    private final SduiDeviceCommandRepository commandRepository;
    private final SduiScriptRuntimeManager runtimeManager;

    public Map<String, Object> overview() {
        deviceService.markTimedOutCommands();

        long totalDevices = deviceService.countDevices();
        long onlineDevices = deviceService.countOnlineDevices();
        long offlineDevices = deviceService.countOfflineDevices();

        long sentCommands = commandRepository.countByStatus("SENT");
        long failedCommands = commandRepository.countByStatus("FAILED");
        long ackedCommands = commandRepository.countByStatus("ACKED");
        long rejectedCommands = commandRepository.countByStatus("REJECTED");
        long errorCommands = commandRepository.countByStatus("ERROR");
        long timeoutCommands = commandRepository.countByStatus("TIMEOUT");

        List<SduiRuntimeWorkerStatus> workers = runtimeManager.workerStatuses();
        long healthyWorkers = workers.stream().filter(SduiRuntimeWorkerStatus::healthy).count();

        Map<String, Object> data = new HashMap<>();
        data.put("totalDevices", totalDevices);
        data.put("onlineDevices", onlineDevices);
        data.put("offlineDevices", offlineDevices);
        data.put("sentCommands", sentCommands);
        data.put("failedCommands", failedCommands);
        data.put("ackedCommands", ackedCommands);
        data.put("rejectedCommands", rejectedCommands);
        data.put("errorCommands", errorCommands);
        data.put("timeoutCommands", timeoutCommands);
        data.put("workerCount", workers.size());
        data.put("healthyWorkerCount", healthyWorkers);
        data.put("workers", workers);
        return data;
    }
}
