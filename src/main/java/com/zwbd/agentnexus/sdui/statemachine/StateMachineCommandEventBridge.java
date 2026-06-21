package com.zwbd.agentnexus.sdui.statemachine;

import com.zwbd.agentnexus.sdui.service.CommandResultStreamService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

@Slf4j
@Component
public class StateMachineCommandEventBridge implements CommandResultStreamService.CommandResultListener {

    private final StateMachineService stateMachineService;

    public StateMachineCommandEventBridge(CommandResultStreamService commandResultStreamService,
                                          StateMachineService stateMachineService) {
        this.stateMachineService = stateMachineService;
        commandResultStreamService.addListener(this);
    }

    @Override
    public void onCommandEvent(CommandLifecycleEvent event) {
        try {
            stateMachineService.handleCommandEvent(event);
        } catch (Exception e) {
            log.warn("State machine command event handling failed for device {} command {}: {}",
                    event.deviceId(), event.command(), e.getMessage());
        }
    }
}
