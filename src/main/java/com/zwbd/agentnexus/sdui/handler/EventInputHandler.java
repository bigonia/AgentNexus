package com.zwbd.agentnexus.sdui.handler;

import com.zwbd.agentnexus.sdui.DeviceSessionManager;
import com.zwbd.agentnexus.sdui.protocol.BinaryProtocolCodec.DecodedFrame;
import com.zwbd.agentnexus.sdui.protocol.BinaryProtocolCodec.TlvEntry;
import com.zwbd.agentnexus.sdui.workflow.WorkflowService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.WebSocketSession;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Function;

@Slf4j
@Component
public class EventInputHandler implements BinaryFrameHandler {

    private final DeviceSessionManager sessionManager;
    private final WorkflowService workflowService;
    private final List<EventListener> listeners = new CopyOnWriteArrayList<>();

    public EventInputHandler(DeviceSessionManager sessionManager, WorkflowService workflowService) {
        this.sessionManager = sessionManager;
        this.workflowService = workflowService;
    }

    public interface EventListener {
        void onEvent(String deviceId, String kind, String nodeId, String sectionId, long ts);
    }

    public void addListener(EventListener listener) {
        listeners.add(listener);
    }

    public void removeListener(EventListener listener) {
        listeners.remove(listener);
    }

    @Override
    public int getSupportedMsgType() { return 9; }

    @Override
    public void handle(WebSocketSession session, DecodedFrame frame) {
        String kind = findTlv(frame, 120, TlvEntry::asString);
        String nodeId = findTlv(frame, 121, TlvEntry::asString);
        String sectionId = findTlv(frame, 122, TlvEntry::asString);
        Long ts = findTlv(frame, 125, TlvEntry::asU32);
        log.info("Event input: kind={}, nodeId={}, sectionId={}, ts={}", kind, nodeId, sectionId, ts);

        String deviceId = sessionManager.getDeviceIdBySessionId(session.getId());
        if (deviceId != null && kind != null) {
            workflowService.fireEvent(deviceId, kind,
                    Map.of("nodeId", nodeId != null ? nodeId : "",
                           "sectionId", sectionId != null ? sectionId : "",
                           "ts", ts != null ? ts : 0));
        }

        if (deviceId != null && !listeners.isEmpty()) {
            long eventTs = ts != null ? ts : System.currentTimeMillis();
            for (EventListener listener : listeners) {
                try {
                    listener.onEvent(deviceId, kind, nodeId, sectionId, eventTs);
                } catch (Exception e) {
                    log.error("EventListener error for device {}: {}", deviceId, e.getMessage());
                }
            }
        }
    }

    private <T> T findTlv(DecodedFrame frame, int type, Function<TlvEntry, T> extractor) {
        return frame.tlvs().stream()
                .filter(t -> t.type() == type)
                .findFirst().map(extractor).orElse(null);
    }
}
