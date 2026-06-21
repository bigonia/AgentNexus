package com.zwbd.agentnexus.sdui.handler;

import com.zwbd.agentnexus.sdui.DeviceSessionManager;
import com.zwbd.agentnexus.sdui.capability.CapabilityRegistry;
import com.zwbd.agentnexus.sdui.event.EventPayload;
import com.zwbd.agentnexus.sdui.event.EventRegistry;
import com.zwbd.agentnexus.sdui.protocol.BinaryProtocolCodec;
import com.zwbd.agentnexus.sdui.protocol.BinaryProtocolCodec.DecodedFrame;
import com.zwbd.agentnexus.sdui.service.DeviceLifecycleService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.WebSocketSession;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Handles UI3 Binary EVENT_INPUT frames (msgType=9) from devices.
 *
 * Extracts structured event data from TLV fields, builds an {@link EventPayload},
 * and routes it to event listeners (SSE).
 *
 * TLV fields consumed:
 * <ul>
 *   <li>120 — eventKind (u8)</li>
 *   <li>121 — nodeId (string)</li>
 *   <li>122 — eventName (string)</li>
 *   <li>123 — sectionId (string, NEW — firmware should populate)</li>
 *   <li>124 — pageId (string, NEW — firmware should populate)</li>
 *   <li>125 — ts (u32, millis)</li>
 *   <li>126 — value (NEW — event-specific value)</li>
 * </ul>
 */
@Slf4j
@Component
public class EventInputHandler implements BinaryFrameHandler {

    private final DeviceSessionManager sessionManager;
    private final DeviceLifecycleService lifecycleService;
    private final EventRegistry eventRegistry;
    private final CapabilityRegistry capabilityRegistry;
    private final List<EventListener> listeners = new CopyOnWriteArrayList<>();
    private final List<PayloadEventListener> payloadListeners = new CopyOnWriteArrayList<>();

    public EventInputHandler(DeviceSessionManager sessionManager,
                             DeviceLifecycleService lifecycleService,
                             EventRegistry eventRegistry,
                             CapabilityRegistry capabilityRegistry) {
        this.sessionManager = sessionManager;
        this.lifecycleService = lifecycleService;
        this.eventRegistry = eventRegistry;
        this.capabilityRegistry = capabilityRegistry;
    }

    public interface EventListener {
        void onEvent(String deviceId, String event, String nodeId, long ts);
    }

    public interface PayloadEventListener {
        void onEvent(EventPayload payload);
    }

    public void addListener(EventListener listener) {
        listeners.add(listener);
    }

    public void removeListener(EventListener listener) {
        listeners.remove(listener);
    }

    public void addPayloadListener(PayloadEventListener listener) {
        payloadListeners.add(listener);
    }

    public void removePayloadListener(PayloadEventListener listener) {
        payloadListeners.remove(listener);
    }

    /**
     * Publish an event from external code (e.g. audio recording pipeline).
     * Fires both the simple {@link EventListener} and rich {@link PayloadEventListener} chains.
     */
    public void publishEvent(EventPayload payload) {
        if (payload == null || payload.deviceId() == null) return;
        log.debug("Publishing external event: device={}, eventId={}", payload.deviceId(), payload.eventId());
        if (!listeners.isEmpty()) {
            String evt = payload.eventId() != null ? payload.eventId() : "unknown";
            for (EventListener listener : listeners) {
                try {
                    listener.onEvent(payload.deviceId(), evt, payload.nodeId(), payload.ts());
                } catch (Exception e) {
                    log.error("EventListener error for device {}: {}", payload.deviceId(), e.getMessage());
                }
            }
        }
        if (!payloadListeners.isEmpty()) {
            for (PayloadEventListener listener : payloadListeners) {
                try {
                    listener.onEvent(payload);
                } catch (Exception e) {
                    log.error("PayloadEventListener error for device {}: {}", payload.deviceId(), e.getMessage());
                }
            }
        }
    }

    @Override
    public int getSupportedMsgType() { return 9; }

    @Override
    public void handle(WebSocketSession session, DecodedFrame frame) {
        String deviceId = sessionManager.getDeviceIdBySessionId(session.getId());

        // Build structured EventPayload from binary frame TLVs
        EventPayload rawPayload = EventPayload.fromBinaryInput(deviceId, frame);

        // Step 1: Resolve section interaction events (ui:action.click, etc.) via EventRegistry
        EventPayload payload = eventRegistry.normalizePayload(rawPayload);

        // Step 2: Resolve physical input events (buttons, motion) via CapabilityRegistry.
        // If the eventId is still a raw name (e.g. "short_press"), try to resolve it
        // to the namespaced form (e.g. "input:buttons.pwr.short_press") so it matches
        // state-machine transition triggers.
        if (deviceId != null) {
            String resolved = capabilityRegistry.resolveInputEventId(
                    deviceId, payload.eventId(), payload.kind(), payload.nodeId());
            if (resolved != null && !resolved.equals(payload.eventId())) {
                payload = payload.withEventId(resolved);
            }
        }

        log.info("Event input: device={}, eventId={}, kind={}, nodeId={}, sectionId={}, pageId={}, value={}, ts={}",
                deviceId, payload.eventId(), payload.kind(), payload.nodeId(),
                payload.sectionId(), payload.pageId(), payload.value(), payload.ts());

        // Touch device lifecycle
        if (deviceId != null) {
            lifecycleService.touchDevice(deviceId);
        }

        // Notify SSE listeners (real-time event monitor)
        if (deviceId != null && !listeners.isEmpty()) {
            String evt = payload.eventId() != null ? payload.eventId() : "unknown";
            for (EventListener listener : listeners) {
                try {
                    listener.onEvent(deviceId, evt, payload.nodeId(), payload.ts());
                } catch (Exception e) {
                    log.error("EventListener error for device {}: {}", deviceId, e.getMessage());
                }
            }
        }

        if (deviceId != null && !payloadListeners.isEmpty()) {
            for (PayloadEventListener listener : payloadListeners) {
                try {
                    listener.onEvent(payload);
                } catch (Exception e) {
                    log.error("PayloadEventListener error for device {}: {}", deviceId, e.getMessage());
                }
            }
        }
    }
}
