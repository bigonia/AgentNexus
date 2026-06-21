package com.zwbd.agentnexus.sdui.capability;

import com.zwbd.agentnexus.sdui.section.SectionTypeCatalog;
import com.zwbd.agentnexus.sdui.protocol.catalog.DeviceCapabilityProjection;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * Unified capability validation entry point.
 * Wraps CapabilityRegistry + SectionTypeCatalog.
 *
 * Designed so workflow nodes and debug tools share the same validation logic.
 * Later migration: inject this into state-machine deployment validation and action execution.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CapabilityValidator {

    private final CapabilityRegistry registry;
    private final DeviceCapabilityProjection capabilityProjection;

    public enum CheckType { EVENT, COMMAND, SECTION }

    // ── Validation result ──

    public record ValidationResult(
            boolean valid,
            String capabilityId,
            String deviceId,
            CheckType checkType,
            String issue,
            List<String> suggestions
    ) {
        public static ValidationResult ok(String capabilityId, String deviceId, CheckType type) {
            return new ValidationResult(true, capabilityId, deviceId, type, null, List.of());
        }

        public static ValidationResult fail(String capabilityId, String deviceId, CheckType type,
                                            String issue, List<String> suggestions) {
            return new ValidationResult(false, capabilityId, deviceId, type, issue, suggestions);
        }
    }

    public record BulkCheckRequest(List<CapabilityCheck> checks) {
        public record CapabilityCheck(CheckType type, String capabilityId) {}
    }

    // ── Single validations ──

    /**
     * Validate that a device supports a specific output command.
     */
    public ValidationResult validateCommand(String deviceId, String command) {
        if (!registry.supportsCommand(deviceId, command)) {
            List<String> suggestions = registry.suggestSimilar(command);
            String issue = registry.getDeviceSnapshot(deviceId).isEmpty()
                    ? "Device not found or has not reported capabilities"
                    : "Device does not support command: " + command;
            return ValidationResult.fail(command, deviceId, CheckType.COMMAND, issue, suggestions);
        }
        return ValidationResult.ok(command, deviceId, CheckType.COMMAND);
    }

    /**
     * Validate that a device supports a specific input event.
     */
    public ValidationResult validateEvent(String deviceId, String eventId) {
        boolean supported = capabilityProjection.events(deviceId).stream()
                .anyMatch(event -> event.id().equals(eventId));
        if (!supported) {
            // Also check if it's a valid interaction event from SectionTypeCatalog
            // that this device's section types could support
            boolean isKnownInteractionEvent = eventId != null && eventId.startsWith("ui:");
            String issue;
            if (isKnownInteractionEvent) {
                List<String> requiredSectionTypes = SectionTypeCatalog.getSectionTypesForEvent(eventId);
                issue = "Device does not support this interaction event. "
                        + "Required section types: " + requiredSectionTypes
                        + ". Device section types: " + registry.getDeviceSnapshot(deviceId)
                        .map(CapabilityRegistry.DeviceCapabilities::sectionTypes).orElse(Set.of());
            } else if (registry.getDeviceSnapshot(deviceId).isEmpty()) {
                issue = "Device not found or has not reported capabilities";
            } else {
                issue = "Device does not support event: " + eventId;
            }
            List<String> suggestions = registry.suggestSimilar(eventId);
            return ValidationResult.fail(eventId, deviceId, CheckType.EVENT, issue, suggestions);
        }
        return ValidationResult.ok(eventId, deviceId, CheckType.EVENT);
    }

    /**
     * Validate that a device supports a specific Section type.
     */
    public ValidationResult validateSectionType(String deviceId, String sectionType) {
        // First check the section type is known
        if (SectionTypeCatalog.get(sectionType).isEmpty()) {
            return ValidationResult.fail(sectionType, deviceId, CheckType.SECTION,
                    "Unknown section type: " + sectionType + ". Known: " + SectionTypeCatalog.allTypes(),
                    List.of());
        }
        // Then check device supports it
        if (!registry.supportsSectionType(deviceId, sectionType)) {
            List<String> suggestions = registry.suggestSimilar(sectionType);
            String issue = registry.getDeviceSnapshot(deviceId).isEmpty()
                    ? "Device not found or has not reported capabilities"
                    : "Device does not support section type: " + sectionType
                    + ". Supported: " + registry.getDeviceSnapshot(deviceId)
                    .map(CapabilityRegistry.DeviceCapabilities::sectionTypes).orElse(Set.of());
            return ValidationResult.fail(sectionType, deviceId, CheckType.SECTION, issue, suggestions);
        }
        return ValidationResult.ok(sectionType, deviceId, CheckType.SECTION);
    }

    // ── Bulk validation ──

    /**
     * Validate multiple capability checks in one call.
     * Used by the debug validation endpoint.
     */
    public Map<String, Object> validateBulk(String deviceId, BulkCheckRequest request) {
        List<ValidationResult> results = new ArrayList<>();
        Map<String, List<String>> suggestionMap = new LinkedHashMap<>();

        for (var check : request.checks()) {
            ValidationResult result = switch (check.type()) {
                case COMMAND -> validateCommand(deviceId, check.capabilityId());
                case EVENT -> validateEvent(deviceId, check.capabilityId());
                case SECTION -> validateSectionType(deviceId, check.capabilityId());
            };
            results.add(result);
            if (!result.valid() && !result.suggestions().isEmpty()) {
                suggestionMap.put(check.capabilityId(), result.suggestions());
            }
        }

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("results", results);
        response.put("allValid", results.stream().allMatch(ValidationResult::valid));
        if (!suggestionMap.isEmpty()) {
            response.put("suggestions", suggestionMap);
        }
        return response;
    }

    // ── Device capability summary (for debug UI) ──

    /**
     * Build a comprehensive capability summary for a device,
     * including validation of known capabilities not on this device.
     */
    public Map<String, Object> buildDeviceCapabilitySummary(String deviceId) {
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("deviceId", deviceId);

        Optional<CapabilityRegistry.DeviceCapabilities> snapshot = registry.getDeviceSnapshot(deviceId);
        if (snapshot.isEmpty()) {
            summary.put("status", "no_capability_data");
            summary.put("globalCatalog", registry.getGlobalCatalog());
            return summary;
        }

        CapabilityRegistry.DeviceCapabilities caps = snapshot.get();
        summary.put("status", "ok");
        summary.put("reportedAt", caps.reportedAt().toString());

        // Device capabilities
        Map<String, Object> deviceCaps = new LinkedHashMap<>();
        deviceCaps.put("inputEvents", new ArrayList<>(caps.inputEvents()));
        deviceCaps.put("outputCommands", new ArrayList<>(caps.outputCommands()));
        deviceCaps.put("sectionTypes", new ArrayList<>(caps.sectionTypes()));
        deviceCaps.put("deviceProfile", Map.of(
                "shape", caps.deviceProfileShape() != null ? caps.deviceProfileShape() : "unknown",
                "screenW", caps.screenW(),
                "screenH", caps.screenH(),
                "inputMode", caps.inputMode() != null ? caps.inputMode() : "unknown",
                "sizeClass", caps.sizeClass() != null ? caps.sizeClass() : "large"
        ));
        summary.put("deviceCapabilities", deviceCaps);

        // Global catalog (for context)
        summary.put("globalCatalog", registry.getGlobalCatalog());

        // Section interaction events supported
        Set<String> interactionEvents = registry.getDeviceInteractionEvents(deviceId);
        Map<String, Object> sectionInteractions = new LinkedHashMap<>();
        for (String stype : caps.sectionTypes()) {
            List<SectionTypeCatalog.InteractionEvent> events =
                    SectionTypeCatalog.getInteractionEvents(stype);
            if (!events.isEmpty()) {
                List<String> eventIds = events.stream()
                        .map(SectionTypeCatalog.InteractionEvent::eventId).toList();
                sectionInteractions.put(stype, eventIds);
            }
        }
        summary.put("sectionInteractionEvents", sectionInteractions);

        // Capabilities the device does NOT support
        Map<String, Object> unsupported = new LinkedHashMap<>();
        Set<String> globalEvents = registry.getKnownEvents();
        Set<String> missingEvents = new LinkedHashSet<>(globalEvents);
        missingEvents.removeAll(caps.inputEvents());
        // Also remove interaction events from unsupported section types
        missingEvents.removeIf(e -> e.startsWith("section.") && interactionEvents.contains(e));
        if (!missingEvents.isEmpty()) {
            unsupported.put("events", new ArrayList<>(missingEvents));
        }

        Set<String> globalCommands = registry.getKnownCommands();
        Set<String> missingCommands = new LinkedHashSet<>(globalCommands);
        missingCommands.removeAll(caps.outputCommands());
        if (!missingCommands.isEmpty()) {
            unsupported.put("commands", new ArrayList<>(missingCommands));
        }

        Set<String> globalSections = registry.getKnownSectionTypes();
        Set<String> missingSections = new LinkedHashSet<>(globalSections);
        missingSections.removeAll(caps.sectionTypes());
        if (!missingSections.isEmpty()) {
            unsupported.put("sectionTypes", new ArrayList<>(missingSections));
        }

        if (!unsupported.isEmpty()) {
            summary.put("unsupportedCapabilities", unsupported);
        }

        return summary;
    }

    // ── Workflow-level validation (for future migration) ──

    /**
     * Validate a state-machine trigger definition against a device's capabilities.
     * This can be called by state-machine deployment validation before publishing.
     */
    public ValidationResult validateTrigger(String deviceId, String triggerType, String eventOrCommand) {
        if ("device.ui.event".equals(triggerType)) {
            return validateEvent(deviceId, eventOrCommand);
        }
        if ("device_command".equals(triggerType)) {
            return validateCommand(deviceId, eventOrCommand);
        }
        // manual, cron, webhook triggers don't reference device capabilities
        return ValidationResult.ok(triggerType, deviceId, CheckType.EVENT);
    }

    /**
     * Validate a workflow action definition against a device's capabilities.
     */
    public ValidationResult validateAction(String deviceId, String actionType, String command) {
        if ("control".equals(actionType)) {
            return validateCommand(deviceId, command);
        }
        // fetch, set_variable, condition, etc. don't directly reference device capabilities
        return ValidationResult.ok(actionType, deviceId, CheckType.COMMAND);
    }
}
