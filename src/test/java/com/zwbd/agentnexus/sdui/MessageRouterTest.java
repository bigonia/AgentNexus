package com.zwbd.agentnexus.sdui;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zwbd.agentnexus.common.web.GlobalContext;
import com.zwbd.agentnexus.sdui.handler.BinaryFrameHandler;
import com.zwbd.agentnexus.sdui.model.SduiDevice;
import com.zwbd.agentnexus.sdui.protocol.BinaryProtocolCodec;
import com.zwbd.agentnexus.sdui.repo.SduiDeviceRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.socket.WebSocketSession;

import java.net.URI;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class MessageRouterTest {

    @AfterEach
    void tearDown() {
        GlobalContext.clear();
    }

    @Test
    void binaryRouteRestoresUserContextFromBoundDevice() {
        SduiDeviceRepository deviceRepository = mock(SduiDeviceRepository.class);
        DeviceSessionManager sessionManager = mock(DeviceSessionManager.class);
        WebSocketSession session = mock(WebSocketSession.class);
        AtomicReference<String> userSeenByHandler = new AtomicReference<>();
        BinaryFrameHandler handler = new BinaryFrameHandler() {
            @Override
            public int getSupportedMsgType() {
                return 9;
            }

            @Override
            public void handle(WebSocketSession session, BinaryProtocolCodec.DecodedFrame frame) {
                userSeenByHandler.set(GlobalContext.getUserId());
            }
        };

        SduiDevice device = new SduiDevice();
        device.setDeviceId("dev-a");
        device.setOwnerUserId("alice");

        when(session.getId()).thenReturn("session-1");
        when(session.getUri()).thenReturn(URI.create("ws://localhost/sdui?deviceId=dev-a"));
        when(sessionManager.getDeviceIdBySessionId("session-1")).thenReturn("dev-a");
        when(deviceRepository.findById("dev-a")).thenReturn(Optional.of(device));

        MessageRouter router = new MessageRouter(
                new ObjectMapper(),
                deviceRepository,
                sessionManager,
                List.of(),
                List.of(handler)
        );
        router.init();

        router.routeBinaryMessage(session, BinaryProtocolCodec.encode(9, 1, new byte[0]));

        assertEquals("alice", userSeenByHandler.get());
        assertNull(GlobalContext.getString(GlobalContext.KEY_USER_ID));
    }
}
