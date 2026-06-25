package com.zwbd.agentnexus.sdui.service;

import com.zwbd.agentnexus.sdui.capability.CapabilityCatalog;
import com.zwbd.agentnexus.sdui.capability.CapabilityRegistry;
import com.zwbd.agentnexus.sdui.event.EventPayload;
import com.zwbd.agentnexus.sdui.handler.EventInputHandler;
import com.zwbd.agentnexus.sdui.section.SectionOrchestrationService;
import com.zwbd.agentnexus.sdui.section.SectionTypeCatalog;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Service
public class EventStreamService implements EventInputHandler.PayloadEventListener {

    private final Map<String, SseEmitter> emitters = new ConcurrentHashMap<>();
    private final CapabilityRegistry capabilityRegistry;
    private final CapabilityCatalog capabilityCatalog;
    private final SectionOrchestrationService orchestrationService;
    private final SectionTypeCatalog sectionTypeCatalog;

    public EventStreamService(EventInputHandler eventInputHandler,
                              CapabilityRegistry capabilityRegistry,
                              CapabilityCatalog capabilityCatalog,
                              SectionOrchestrationService orchestrationService,
                              SectionTypeCatalog sectionTypeCatalog) {
        this.capabilityRegistry = capabilityRegistry;
        this.capabilityCatalog = capabilityCatalog;
        this.orchestrationService = orchestrationService;
        this.sectionTypeCatalog = sectionTypeCatalog;
        eventInputHandler.addPayloadListener(this);
    }

    public SseEmitter subscribe(String deviceId) {
        SseEmitter existing = emitters.get(deviceId);
        if (existing != null) {
            existing.complete();
        }

        SseEmitter emitter = new SseEmitter(0L);
        emitters.put(deviceId, emitter);

        emitter.onCompletion(() -> {
            emitters.remove(deviceId, emitter);
            log.info("SSE completed for device {}", deviceId);
        });
        emitter.onTimeout(() -> {
            emitters.remove(deviceId, emitter);
            log.info("SSE timed out for device {}", deviceId);
        });
        emitter.onError(e -> {
            emitters.remove(deviceId, emitter);
            log.info("SSE error for device {}: {}", deviceId, e.getMessage());
        });

        try {
            emitter.send(SseEmitter.event()
                    .name("connected")
                    .data(Map.of("deviceId", deviceId, "message", "Event stream connected")));
            pushInputCatalog(emitter, deviceId);
        } catch (IOException e) {
            emitters.remove(deviceId, emitter);
            log.error("Failed to send SSE connected event for device {}", deviceId, e);
        }

        return emitter;
    }

    /**
     * Push an {@code input_catalog} event listing all business-facing input events
     * this device can produce. Uses the unified {@link CapabilityRegistry#buildEventCatalog}.
     */
    private void pushInputCatalog(SseEmitter emitter, String deviceId) {
        try {
            Map<String, Object> catalog2 = capabilityRegistry.buildEventCatalog(deviceId, Set.of());

            // Add board info
            capabilityRegistry.getBoardForDevice(deviceId).flatMap(capabilityRegistry::getBoardType)
                    .ifPresent(boardInfo -> catalog2.put("board", boardInfo.board()));

            emitter.send(SseEmitter.event()
                    .name("input_catalog")
                    .data(catalog2));
        } catch (IOException e) {
            log.warn("Failed to push input catalog for device {}: {}", deviceId, e.getMessage());
        }
    }

    // ── Payload event listener ──

    @Override
    public void onEvent(EventPayload payload) {
        SseEmitter emitter = emitters.get(payload.deviceId());
        if (emitter == null) return;

        Map<String, Object> data = enrichEvent(payload);

        String sseEventName = (payload.eventId() != null && !payload.eventId().isEmpty())
                ? payload.eventId() : "platform_event";

        try {
            emitter.send(SseEmitter.event()
                    .name(sseEventName)
                    .data(data));
        } catch (IOException e) {
            emitters.remove(payload.deviceId(), emitter);
            log.info("SSE payload send failed for device {}, removing emitter: {}", payload.deviceId(), e.getMessage());
            try { emitter.completeWithError(e); } catch (Exception ignored) {}
        }
    }

    // ── Business event enrichment ──

    /**
     * Enrich a raw {@link EventPayload} with human-readable labels, input source
     * context, and section context, producing a business-friendly SSE event.
     * <p>
     * The raw protocol data is preserved under the {@code raw} key for debugging.
     */
    private Map<String, Object> enrichEvent(EventPayload payload) {
        Map<String, Object> data = new LinkedHashMap<>();

        // Core identity
        data.put("eventId", payload.eventId());
        data.put("deviceId", payload.deviceId());
        data.put("ts", payload.ts());

        // Resolve display name and category
        enrichDisplayInfo(data, payload);

        // Input source context (button, motion, section, audio, etc.)
        enrichInputSource(data, payload);

        // Section context (which section emitted this event)
        if (payload.hasSectionContext()) {
            enrichSectionContext(data, payload);
        }

        // Event value (toggle state, etc.)
        if (payload.hasValue()) {
            data.put("value", payload.value());
        }

        // Raw protocol data for debugging
        data.put("raw", payload.rawFields());

        return data;
    }

    private void enrichDisplayInfo(Map<String, Object> data, EventPayload payload) {
        String eventId = payload.eventId();
        if (eventId == null || eventId.isEmpty()) {
            data.put("displayName", "unknown");
            data.put("category", "unknown");
            return;
        }

        // 1. Try namespaced ID match (e.g. "input:buttons.pwr.short_press")
        for (var entry : capabilityCatalog.getInputsByName().entrySet()) {
            CapabilityCatalog.InputDef inputDef = entry.getValue();
            if (inputDef.events() == null) continue;
            for (String eventName : inputDef.events()) {
                String normalizedId = "input:" + entry.getKey() + "." + eventName;
                if (normalizedId.equals(eventId)) {
                    String inputLabel = inputDef.displayName() != null ? inputDef.displayName() : entry.getKey();
                    String eventLabel = inputDef.eventDisplayName(eventName);
                    data.put("displayName", eventLabel);
                    data.put("description", inputLabel + " - " + eventLabel);
                    data.put("category", inputDef.platform() ? "media" : "physical_input");
                    return;
                }
            }
        }

        // 2. Fallback: try raw eventName + nodeId against catalog
        //    (device may send "shake" before it gets resolved to "input:motion.shake")
        String rawEventName = eventId.contains(":") ? eventId : eventId; // already raw
        String nodeId = payload.nodeId();
        for (var entry : capabilityCatalog.getInputsByName().entrySet()) {
            CapabilityCatalog.InputDef inputDef = entry.getValue();
            if (inputDef.events() == null || inputDef.platform()) continue;
            if (nodeId != null && !nodeId.isBlank() && entry.getKey().endsWith(nodeId)) {
                // nodeId matches input name suffix, e.g. nodeId="motion" matches input "motion"
                if (inputDef.events().contains(rawEventName)) {
                    String inputLabel = inputDef.displayName() != null ? inputDef.displayName() : entry.getKey();
                    String eventLabel = inputDef.eventDisplayName(rawEventName);
                    data.put("displayName", eventLabel);
                    data.put("description", inputLabel + " - " + eventLabel);
                    data.put("category", "physical_input");
                    return;
                }
            }
        }

        // 3. Last resort
        data.put("displayName", eventId);
        data.put("category", "unknown");
    }

    private void enrichInputSource(Map<String, Object> data, EventPayload payload) {
        Map<String, Object> source = new LinkedHashMap<>();
        String eventId = payload.eventId();
        String nodeId = payload.nodeId();

        // Try to find matching input by namespaced eventId
        if (eventId != null && eventId.startsWith("input:")) {
            String rest = eventId.substring("input:".length());
            int lastDot = rest.lastIndexOf('.');
            if (lastDot > 0) {
                String inputName = rest.substring(0, lastDot);
                source.put("name", inputName);
                capabilityCatalog.getInput(inputName).ifPresent(def -> {
                    if (def.displayName() != null) source.put("displayName", def.displayName());
                });
            }
        }

        // Fallback: try to identify by nodeId
        if (!source.containsKey("name") && nodeId != null && !nodeId.isBlank()) {
            for (var entry : capabilityCatalog.getInputsByName().entrySet()) {
                if (entry.getKey().endsWith(nodeId) || entry.getKey().equals(nodeId)) {
                    CapabilityCatalog.InputDef def = entry.getValue();
                    if (!def.platform()) {
                        source.put("name", entry.getKey());
                        if (def.displayName() != null) source.put("displayName", def.displayName());
                        break;
                    }
                }
            }
            // Still unknown — mark by type
            if (!source.containsKey("name")) {
                source.put("type", nodeId);
            }
        }

        // Section events
        if (eventId != null && eventId.startsWith("ui:")) {
            source.clear();
            source.put("type", "section");
        }

        // Audio events
        if (eventId != null && (eventId.startsWith("audio.") || eventId.startsWith("input:audio"))) {
            source.clear();
            source.put("type", "audio");
        }

        if (nodeId != null && !nodeId.isBlank()) {
            source.put("nodeId", nodeId);
        }

        if (!source.isEmpty()) {
            data.put("inputSource", source);
        }
    }

    private void enrichSectionContext(Map<String, Object> data, EventPayload payload) {
        Map<String, Object> sectionCtx = new LinkedHashMap<>();
        sectionCtx.put("sectionId", payload.sectionId());
        sectionCtx.put("pageId", payload.pageId().isEmpty() ? null : payload.pageId());

        // Resolve section type from orchestration service (takes pageId)
        // and from debug workspace (single page, no pageId needed)
        String sectionType = orchestrationService.findSectionType(
                payload.deviceId(), payload.pageId(), payload.sectionId());
        if (sectionType != null) {
            sectionCtx.put("sectionType", sectionType);
            sectionTypeCatalog.get(sectionType).ifPresent(def -> {
                sectionCtx.put("sectionDisplayName", def.displayName());
            });
        }

        data.put("section", sectionCtx);
    }
}
