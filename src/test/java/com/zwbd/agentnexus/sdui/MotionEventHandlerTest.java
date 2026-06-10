package com.zwbd.agentnexus.sdui;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zwbd.agentnexus.sdui.event.EventPayload;
import com.zwbd.agentnexus.sdui.service.DeviceLifecycleService;
import com.zwbd.agentnexus.sdui.workflow.WorkflowService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.socket.WebSocketSession;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class MotionEventHandlerTest {

    private WorkflowService workflowService;
    private MotionEventHandler handler;

    @BeforeEach
    void setUp() {
        workflowService = mock(WorkflowService.class);
        handler = new MotionEventHandler(
                mock(DeviceSessionManager.class),
                workflowService,
                mock(DeviceLifecycleService.class),
                new ObjectMapper()
        );
    }

    @Test
    void mapsShakeEventToNamespacedMotionEvent() {
        SduiMessage message = new SduiMessage();
        message.setTopic("motion");
        message.setDeviceId("1020BA3D35D0");
        message.setPayload(new ObjectMapper().valueToTree(new MotionPayload("shake", 16.2)));
        when(workflowService.fireEvent(eq("1020BA3D35D0"), any(EventPayload.class))).thenReturn(1);

        handler.handle(mock(WebSocketSession.class), message);

        var payloadCaptor = org.mockito.ArgumentCaptor.forClass(EventPayload.class);
        verify(workflowService).fireEvent(eq("1020BA3D35D0"), payloadCaptor.capture());
        assertEquals("input:motion.imu.shake", payloadCaptor.getValue().eventId());
        assertEquals("imu.shake", payloadCaptor.getValue().rawFields().get("eventName"));
    }

    private record MotionPayload(String type, double magnitude) {}
}
