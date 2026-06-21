package com.zwbd.agentnexus.sdui.statemachine;

import com.zwbd.agentnexus.sdui.event.EventPayload;
import com.zwbd.agentnexus.sdui.handler.EventInputHandler;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

@Slf4j
@Component
public class StateMachineEventBridge implements EventInputHandler.PayloadEventListener {

    private final StateMachineService stateMachineService;

    public StateMachineEventBridge(EventInputHandler eventInputHandler,
                                   StateMachineService stateMachineService) {
        this.stateMachineService = stateMachineService;
        eventInputHandler.addPayloadListener(this);
    }

    @Override
    public void onEvent(EventPayload payload) {
        try {
            stateMachineService.handleDeviceEvent(payload);
        } catch (Exception e) {
            log.warn("State machine event handling failed for device {} event {}: {}",
                    payload.deviceId(), payload.eventId(), e.getMessage());
        }
    }
}
