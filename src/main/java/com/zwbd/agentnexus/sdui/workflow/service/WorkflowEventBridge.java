package com.zwbd.agentnexus.sdui.workflow.service;

import com.zwbd.agentnexus.sdui.event.EventPayload;
import com.zwbd.agentnexus.sdui.handler.EventInputHandler;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

@Slf4j
@Component
public class WorkflowEventBridge implements EventInputHandler.PayloadEventListener {

    private final WorkflowRuntimeService runtimeService;

    public WorkflowEventBridge(EventInputHandler eventInputHandler, WorkflowRuntimeService runtimeService) {
        this.runtimeService = runtimeService;
        eventInputHandler.addPayloadListener(this);
    }

    @Override
    public void onEvent(EventPayload payload) {
        try {
            runtimeService.handleDeviceEvent(payload);
        } catch (Exception e) {
            log.warn("Workflow event handling failed for device {}: {}", payload.deviceId(), e.getMessage());
        }
    }
}
