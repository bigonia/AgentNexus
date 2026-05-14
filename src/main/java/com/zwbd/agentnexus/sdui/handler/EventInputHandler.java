package com.zwbd.agentnexus.sdui.handler;

import com.zwbd.agentnexus.sdui.DeviceSessionManager;
import com.zwbd.agentnexus.sdui.protocol.BinaryProtocolCodec.DecodedFrame;
import com.zwbd.agentnexus.sdui.protocol.BinaryProtocolCodec.TlvEntry;
import com.zwbd.agentnexus.sdui.workflow.WorkflowService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.WebSocketSession;

import java.util.Map;
import java.util.function.Function;

@Slf4j
@Component
public class EventInputHandler implements BinaryFrameHandler {

    private final DeviceSessionManager sessionManager;
    private final WorkflowService workflowService;

    public EventInputHandler(DeviceSessionManager sessionManager, WorkflowService workflowService) {
        this.sessionManager = sessionManager;
        this.workflowService = workflowService;
    }

    @Override
    public int getSupportedMsgType() { return 9; }

    @Override
    public void handle(WebSocketSession session, DecodedFrame frame) {
        String kind = findTlv(frame, 120, TlvEntry::asString);
        String nodeId = findTlv(frame, 121, TlvEntry::asString);
        Long ts = findTlv(frame, 125, TlvEntry::asU32);
        log.info("Event input: kind={}, nodeId={}, ts={}", kind, nodeId, ts);

        String deviceId = sessionManager.getDeviceIdBySessionId(session.getId());
        if (deviceId != null && kind != null) {
            workflowService.fireEvent(deviceId, kind,
                    Map.of("nodeId", nodeId != null ? nodeId : "", "ts", ts != null ? ts : 0));
        }
    }

    private <T> T findTlv(DecodedFrame frame, int type, Function<TlvEntry, T> extractor) {
        return frame.tlvs().stream()
                .filter(t -> t.type() == type)
                .findFirst().map(extractor).orElse(null);
    }
}
