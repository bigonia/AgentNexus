package com.zwbd.agentnexus.sdui.service;

import com.zwbd.agentnexus.sdui.DeviceSessionManager;
import com.zwbd.agentnexus.sdui.capability.CapabilityCatalog;
import com.zwbd.agentnexus.sdui.capability.CapabilityRegistry;
import com.zwbd.agentnexus.sdui.event.EventPayload;
import com.zwbd.agentnexus.sdui.handler.EventInputHandler;
import com.zwbd.agentnexus.sdui.section.DebugSectionWorkspaceService;
import com.zwbd.agentnexus.sdui.section.SectionOrchestrationService;
import com.zwbd.agentnexus.sdui.section.SectionTypeCatalog;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Service
public class EventStreamService implements EventInputHandler.PayloadEventListener {

    private final Map<String, SseEmitter> emitters = new ConcurrentHashMap<>();
    private final DeviceSessionManager sessionManager;
    private final CapabilityRegistry capabilityRegistry;
    private final CapabilityCatalog capabilityCatalog;
    private final SectionOrchestrationService orchestrationService;
    private final SectionTypeCatalog sectionTypeCatalog;
    private final DebugSectionWorkspaceService debugWorkspaceService;

    public EventStreamService(EventInputHandler eventInputHandler,
                              DeviceSessionManager sessionManager,
                              CapabilityRegistry capabilityRegistry,
                              CapabilityCatalog capabilityCatalog,
                              SectionOrchestrationService orchestrationService,
                              SectionTypeCatalog sectionTypeCatalog,
                              DebugSectionWorkspaceService debugWorkspaceService) {
        this.sessionManager = sessionManager;
        this.capabilityRegistry = capabilityRegistry;
        this.capabilityCatalog = capabilityCatalog;
        this.orchestrationService = orchestrationService;
        this.sectionTypeCatalog = sectionTypeCatalog;
        this.debugWorkspaceService = debugWorkspaceService;
        eventInputHandler.addPayloadListener(this);
    }

    // ── SSE subscription ──

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
            pushEventCatalog(emitter, deviceId);
        } catch (IOException e) {
            emitters.remove(deviceId, emitter);
            log.error("Failed to send SSE connected event for device {}", deviceId, e);
        }

        return emitter;
    }

    // ── Unified event catalog (replaces input_catalog + page_event_catalog) ──

    /**
     * Push a unified {@code event_catalog} event to the current SSE subscriber.
     * Called on initial connection and after every UI change (push/patch/clear).
     *
     * The catalog always includes physical inputs and media capabilities.
     * Section interaction events are included only if the current debug page
     * has interactive sections — only events that can actually fire are listed.
     */
    public void pushEventCatalog(String deviceId) {
        SseEmitter emitter = emitters.get(deviceId);
        if (emitter == null) return;
        pushEventCatalog(emitter, deviceId);
    }

    private void pushEventCatalog(SseEmitter emitter, String deviceId) {
        try {
            Map<String, Object> catalog = buildUnifiedCatalog(deviceId);
            emitter.send(SseEmitter.event()
                    .name("event_catalog")
                    .data(catalog));
        } catch (IOException e) {
            log.warn("Failed to push event catalog for device {}: {}", deviceId, e.getMessage());
        }
    }

    /**
     * Build a unified event catalog for a device — flattened events array
     * merging physical inputs and page section interactions.
     */
    private Map<String, Object> buildUnifiedCatalog(String deviceId) {
        Map<String, Object> catalog = new LinkedHashMap<>();
        catalog.put("deviceId", deviceId);
        catalog.put("online", sessionManager.isDeviceOnline(deviceId));

        // Board info
        capabilityRegistry.getBoardForDevice(deviceId)
                .flatMap(capabilityRegistry::getBoardType)
                .ifPresent(b -> catalog.put("board", b.board()));

        List<Map<String, Object>> events = new ArrayList<>();

        // Physical input events — flatten grouped structure to event entries
        for (Map<String, Object> input : capabilityRegistry.getDevicePhysicalInputs(deviceId)) {
            String source = string(input.get("inputName"));
            String sourceLabel = string(input.get("displayName"));
            if (input.get("events") instanceof List<?> evts) {
                for (Object e : evts) {
                    if (!(e instanceof Map<?, ?> em)) continue;
                    String eventId = string(em.get("eventId"));
                    if (eventId.isBlank()) continue;
                    Map<String, Object> entry = new LinkedHashMap<>();
                    entry.put("eventId", eventId);
                    entry.put("category", "event");
                    entry.put("source", source);
                    entry.put("sourceLabel", sourceLabel);
                    entry.put("action", string(em.get("displayName")));
                    events.add(entry);
                }
            }
        }

        // Section events from current debug page
        Map<String, Object> pageCatalog = debugWorkspaceService.buildPageEventCatalogMap(deviceId);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> sectionEvents =
                (List<Map<String, Object>>) pageCatalog.getOrDefault("events", List.of());
        catalog.put("pageId", pageCatalog.getOrDefault("pageId", ""));

        for (Map<String, Object> se : sectionEvents) {
            String eventId = string(se.get("eventId"));
            if (eventId.isBlank()) continue;
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("eventId", eventId);
            entry.put("category", "sectionEvent");
            entry.put("sectionType", string(se.get("sectionType")));
            entry.put("sectionLabel", string(se.get("sectionDisplayName")));
            String el = string(se.get("elementLabel"));
            if (!el.isBlank()) {
                entry.put("elementLabel", el);
            }
            events.add(entry);
        }

        catalog.put("events", events);
        return catalog;
    }

    private String string(Object value) {
        return value == null ? "" : String.valueOf(value);
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
     * Enrich a raw {@link EventPayload} into a human-readable SSE debug event.
     * Machine-level fields (ts, raw protocol data) are omitted; only fields
     * meaningful for debugging are included.
     */
    private Map<String, Object> enrichEvent(EventPayload payload) {
        Map<String, Object> data = new LinkedHashMap<>();

        // eventId — strip ui: prefix for catalog consistency
        String displayEventId = payload.eventId() != null && payload.eventId().startsWith("ui:")
                ? payload.eventId().substring(3) : payload.eventId();
        data.put("eventId", displayEventId);
        data.put("deviceId", payload.deviceId());

        // Resolve category and human-readable fields
        enrichDisplayInfo(data, payload);

        // Section context
        if (payload.hasSectionContext()) {
            enrichSectionContext(data, payload);
        }

        // Event value (toggle state, etc.)
        if (payload.hasValue()) {
            data.put("value", payload.value());
        }

        return data;
    }

    private void enrichDisplayInfo(Map<String, Object> data, EventPayload payload) {
        String eventId = payload.eventId();
        if (eventId == null || eventId.isEmpty()) {
            data.put("category", "event");
            return;
        }

        // Section events — category + section context is enough
        if (payload.hasSectionContext() || eventId.startsWith("ui:")) {
            data.put("category", "sectionEvent");
            return;
        }

        // Physical input events — resolve source/sourceLabel/action from CapabilityCatalog
        // Step 1: by namespaced eventId (e.g. "input:buttons.pwr.short_press")
        for (var entry : capabilityCatalog.getInputsByName().entrySet()) {
            CapabilityCatalog.InputDef inputDef = entry.getValue();
            if (inputDef.events() == null || inputDef.platform()) continue;
            for (String eventName : inputDef.events()) {
                String normalizedId = "input:" + entry.getKey() + "." + eventName;
                if (normalizedId.equals(eventId)) {
                    data.put("source", entry.getKey());
                    data.put("sourceLabel", inputDef.displayName() != null ? inputDef.displayName() : entry.getKey());
                    data.put("action", inputDef.eventDisplayName(eventName));
                    data.put("category", "event");
                    return;
                }
            }
        }

        // Step 2: fallback by nodeId
        String nodeId = payload.nodeId();
        String rawEventName = eventId;
        for (var entry : capabilityCatalog.getInputsByName().entrySet()) {
            CapabilityCatalog.InputDef inputDef = entry.getValue();
            if (inputDef.events() == null || inputDef.platform()) continue;
            if (nodeId != null && !nodeId.isBlank() && entry.getKey().endsWith(nodeId)) {
                if (inputDef.events().contains(rawEventName)) {
                    data.put("source", entry.getKey());
                    data.put("sourceLabel", inputDef.displayName() != null ? inputDef.displayName() : entry.getKey());
                    data.put("action", inputDef.eventDisplayName(rawEventName));
                    data.put("category", "event");
                    return;
                }
            }
        }

        // Fallback
        data.put("category", "event");
    }

    private void enrichSectionContext(Map<String, Object> data, EventPayload payload) {
        Map<String, Object> sectionCtx = new LinkedHashMap<>();

        String sectionType = orchestrationService.findSectionType(
                payload.deviceId(), payload.pageId(), payload.sectionId());
        if (sectionType != null) {
            sectionCtx.put("sectionType", sectionType);
            sectionTypeCatalog.get(sectionType).ifPresent(def -> {
                sectionCtx.put("sectionLabel", def.displayName());
            });
        }

        // Element label — resolved from page section fields
        if (payload.nodeId() != null && !payload.nodeId().isBlank()) {
            debugWorkspaceService.resolveElementLabel(
                    payload.deviceId(), payload.sectionId(), payload.nodeId())
                    .ifPresent(label -> sectionCtx.put("elementLabel", label));
        }

        if (!sectionCtx.isEmpty()) {
            data.put("section", sectionCtx);
        }
    }
}
