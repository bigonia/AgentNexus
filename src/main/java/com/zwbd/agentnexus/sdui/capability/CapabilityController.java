package com.zwbd.agentnexus.sdui.capability;

import com.zwbd.agentnexus.common.web.ApiResponse;
import com.zwbd.agentnexus.sdui.section.SectionLayout;
import com.zwbd.agentnexus.sdui.section.SectionTypeCatalog;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.*;

/**
 * Global capability catalog endpoints — not scoped to a specific device.
 */
@RestController
@RequestMapping("/api/v1/sdui/capability")
@RequiredArgsConstructor
public class CapabilityController {

    private final CapabilityRegistry registry;

    /**
     * Aggregated capability catalog — single endpoint replacing the 5 separate
     * catalog/* + stats calls. Returns stats, events, commands, and section types
     * in one response so the frontend loads the catalog page with 1 request.
     */
    @GetMapping("/catalog")
    public ApiResponse<Map<String, Object>> aggregatedCatalog() {
        // Stats
        Map<String, Object> stats = registry.stats();

        // Events
        List<String> knownEvents = new ArrayList<>(registry.getKnownEvents());
        List<String> interactionEvents = new ArrayList<>(SectionTypeCatalog.allInteractionEventIds());

        // Commands
        List<String> knownCommands = new ArrayList<>(registry.getKnownCommands());

        // Section types with full metadata
        List<Map<String, Object>> sectionTypes = new ArrayList<>();
        for (SectionTypeCatalog.SectionTypeDef def : SectionTypeCatalog.all().values()) {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("type", def.type());
            entry.put("displayName", def.displayName());
            entry.put("interactive", def.interactive());
            entry.put("displayFields", SectionTypeCatalog.fieldsToMaps(def.displayFields()));
            entry.put("interactionEvents", def.interactionEvents().stream()
                    .map(e -> Map.of(
                            "eventId", e.eventId(),
                            "description", e.description(),
                            "params", e.params().stream()
                                    .map(p -> Map.of("name", p.name(), "type", p.type(),
                                            "description", p.description()))
                                    .toList()))
                    .toList());
            entry.put("defaultConstraints", def.defaultConstraints());
            sectionTypes.add(entry);
        }

        Map<String, Object> catalog = new LinkedHashMap<>();
        catalog.put("stats", stats);
        catalog.put("events", knownEvents);
        catalog.put("interactionEvents", interactionEvents);
        catalog.put("commands", knownCommands);
        catalog.put("sectionTypes", sectionTypes);
        catalog.put("availableLayouts", SectionLayout.availableLayouts());
        return ApiResponse.ok(catalog);
    }

}
