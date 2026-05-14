package com.zwbd.agentnexus.sdui.workflow;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@Slf4j
@RestController
@RequiredArgsConstructor
public class WorkflowWebhookController {

    private final TriggerScheduler triggerScheduler;
    private final WorkflowService workflowService;

    @PostMapping("/api/v1/sdui/webhook/{path}")
    public Map<String, Object> handleWebhook(@PathVariable String path, @RequestBody(required = false) Map<String, Object> body) {
        String deviceId = triggerScheduler.findWebhookDevice(path);
        if (deviceId == null) {
            log.warn("No device registered for webhook path: {}", path);
            return Map.of("status", "no_device", "path", path);
        }

        Map<String, Object> payload = body != null ? body : Map.of();
        workflowService.fireEvent(deviceId, "webhook:" + path, payload);
        log.info("Webhook {} -> device {} payload={}", path, deviceId, payload);
        return Map.of("status", "ok", "deviceId", deviceId, "path", path);
    }
}
