package com.zwbd.agentnexus.sdui.workflow;

import com.zwbd.agentnexus.sdui.protocol.CapabilitySchema;
import com.zwbd.agentnexus.sdui.service.SduiCapabilityService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * Auto-matches workflow virtual I/O slots to concrete device resources.
 * Uses device capability snapshot for matching inputs (buttons, sensors)
 * and DisplayInfo for matching output UI sections.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class BindingAutoMatcher {

    private final SduiCapabilityService capabilityService;

    public enum MatchStatus { MATCHED, NO_MATCH, DEGRADED }

    public record MatchResult(
            String virtualId,
            String virtualCapability,
            MatchStatus status,
            String matchedResource,
            String matchDescription
    ) {}

    public record BindingResult(
            String deviceId,
            List<MatchResult> inputMatches,
            List<MatchResult> outputMatches,
            boolean allMatched
    ) {}

    /**
     * Match virtual inputs to device input capabilities.
     */
    public List<MatchResult> matchInputs(String deviceId, List<VirtualIO> virtualInputs) {
        List<MatchResult> results = new ArrayList<>();
        if (virtualInputs == null || virtualInputs.isEmpty()) return results;

        Optional<CapabilitySchema.CapabilitySnapshot> capsOpt = capabilityService.getCapabilities(deviceId);
        if (capsOpt.isEmpty()) {
            for (VirtualIO vio : virtualInputs) {
                results.add(new MatchResult(vio.id(), vio.capability(), MatchStatus.NO_MATCH,
                        null, "Device capabilities not available (offline?)"));
            }
            return results;
        }

        CapabilitySchema.CapabilitySnapshot caps = capsOpt.get();
        for (VirtualIO vio : virtualInputs) {
            MatchResult match = matchInput(vio, caps);
            results.add(match);
        }
        return results;
    }

    private MatchResult matchInput(VirtualIO vio, CapabilitySchema.CapabilitySnapshot caps) {
        String capability = vio.capability();

        if (caps.inputs() != null) {
            if (capability.startsWith("button.") && caps.inputs().stream().anyMatch(n -> n.startsWith("buttons."))) {
                String matched = caps.inputs().stream().filter(n -> n.startsWith("buttons.")).findFirst().orElse("buttons");
                return new MatchResult(vio.id(), capability, MatchStatus.MATCHED,
                        "input:" + matched, "Input " + matched + " (button)");
            }
            if (capability.startsWith("sensor.") && caps.inputs().contains("motion")) {
                return new MatchResult(vio.id(), capability, MatchStatus.MATCHED,
                        "input:motion", "Input motion (sensor)");
            }
        }

        if (caps.outputs() != null) {
            for (String outputName : caps.outputs()) {
                if (capability.equals(outputName)) {
                    return new MatchResult(vio.id(), capability, MatchStatus.MATCHED,
                            "output:" + outputName, "Output " + outputName);
                }
            }
        }

        return new MatchResult(vio.id(), capability, MatchStatus.NO_MATCH,
                null, "No matching capability found on device");
    }

    /**
     * Match virtual outputs (section.*) to device section slots.
     */
    public List<MatchResult> matchOutputs(String deviceId, List<VirtualIO> virtualOutputs,
                                           Map<String, SectionSlot> existingSlots) {
        List<MatchResult> results = new ArrayList<>();
        if (virtualOutputs == null || virtualOutputs.isEmpty()) return results;

        for (VirtualIO vio : virtualOutputs) {
            String sectionType = vio.capability();
            if (sectionType.startsWith("section.")) {
                sectionType = sectionType.substring("section.".length());
            }

            boolean matched = false;
            for (var entry : existingSlots.entrySet()) {
                SectionSlot slot = entry.getValue();
                if (slot.isFree() && slot.sectionType().equals(sectionType)) {
                    matched = true;
                    results.add(new MatchResult(vio.id(), vio.capability(), MatchStatus.MATCHED,
                            slot.pageId() + "/" + slot.slotId(),
                            "Section slot " + slot.slotId() + " on page " + slot.pageId()));
                    break;
                }
            }

            if (!matched) {
                String adapted = findAdaptedSectionType(sectionType, existingSlots);
                if (adapted != null) {
                    for (var entry : existingSlots.entrySet()) {
                        SectionSlot slot = entry.getValue();
                        if (slot.isFree() && slot.sectionType().equals(adapted)) {
                            results.add(new MatchResult(vio.id(), vio.capability(),
                                    MatchStatus.DEGRADED,
                                    slot.pageId() + "/" + slot.slotId(),
                                    "Degraded: " + sectionType + " -> " + adapted +
                                            " on slot " + slot.slotId()));
                            matched = true;
                            break;
                        }
                    }
                }
            }

            if (!matched) {
                results.add(new MatchResult(vio.id(), vio.capability(), MatchStatus.NO_MATCH,
                        null, "No free section slot of type " + sectionType));
            }
        }
        return results;
    }

    private String findAdaptedSectionType(String desiredType, Map<String, SectionSlot> existingSlots) {
        Map<String, List<String>> downgradeChain = Map.of(
                "text_section", List.of("hero_section"),
                "overlay_section", List.of("text_section", "hero_section"),
                "list_section", List.of("text_section"),
                "chart_section", List.of("metric_section")
        );
        List<String> alternatives = downgradeChain.get(desiredType);
        if (alternatives != null) {
            for (String alt : alternatives) {
                for (SectionSlot slot : existingSlots.values()) {
                    if (slot.isFree() && slot.sectionType().equals(alt)) {
                        return alt;
                    }
                }
            }
        }
        return null;
    }

    /**
     * Full binding resolution for a workflow on a device.
     */
    public BindingResult resolveBindings(String deviceId, WorkflowDefinition def,
                                          Map<String, SectionSlot> existingSlots) {
        List<MatchResult> inputMatches = matchInputs(deviceId, def.virtualInputs());
        List<MatchResult> outputMatches = matchOutputs(deviceId, def.virtualOutputs(), existingSlots);

        boolean allMatched = inputMatches.stream().allMatch(m -> m.status() != MatchStatus.NO_MATCH)
                && outputMatches.stream().allMatch(m -> m.status() != MatchStatus.NO_MATCH);

        return new BindingResult(deviceId, inputMatches, outputMatches, allMatched);
    }
}
