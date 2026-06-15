package com.zwbd.agentnexus.sdui.workflow.service;

import com.zwbd.agentnexus.sdui.service.CommandResultStreamService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

@Slf4j
@Component
public class WorkflowCommandEventBridge implements CommandResultStreamService.CommandResultListener {

    private final WorkflowRuntimeService runtimeService;

    public WorkflowCommandEventBridge(CommandResultStreamService commandResultStreamService,
                                      WorkflowRuntimeService runtimeService) {
        this.runtimeService = runtimeService;
        commandResultStreamService.addListener(this);
    }

    @Override
    public void onCommandEvent(CommandLifecycleEvent event) {
        try {
            runtimeService.handleCommandEvent(event);
        } catch (Exception e) {
            log.warn("Workflow command event handling failed for device {} command {}: {}",
                    event.deviceId(), event.command(), e.getMessage());
        }
    }
}
