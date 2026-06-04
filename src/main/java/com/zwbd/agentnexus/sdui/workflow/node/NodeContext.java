package com.zwbd.agentnexus.sdui.workflow.node;

import com.zwbd.agentnexus.sdui.event.EventPayload;
import com.zwbd.agentnexus.sdui.workflow.WorkflowInstance;

import java.util.Map;

/**
 * Execution context passed to a CapabilityNode at runtime.
 * Carries device identity, resolved inputs, variable space, trigger data,
 * and device capability snapshot.
 *
 * <h3>Trigger data</h3>
 * <ul>
 *   <li>{@code eventPayload} — the structured {@link EventPayload} (preferred, NEW)</li>
 *   <li>{@code triggerPayload} — legacy {@code Map<String, Object>} for backward compat</li>
 * </ul>
 * Nodes should prefer {@code eventPayload} when available, falling back to
 * {@code triggerPayload} for backward compatibility.
 */
public record NodeContext(
        String deviceId,
        WorkflowInstance instance,
        Map<String, Object> resolvedInputs,
        Map<String, Object> triggerPayload,
        Map<String, String> env,
        Map<String, Object> capabilitySnapshot,
        EventPayload eventPayload
) {
    // ── Convenience accessors ──
    public String deviceId() { return deviceId; }
    public WorkflowInstance instance() { return instance; }
    public Map<String, Object> resolvedInputs() { return resolvedInputs; }
    public Map<String, Object> triggerPayload() { return triggerPayload; }
    public Map<String, String> env() { return env; }
    public Map<String, Object> capabilitySnapshot() { return capabilitySnapshot; }
    public EventPayload eventPayload() { return eventPayload; }

    /** @return true if a structured EventPayload is available. */
    public boolean hasEventPayload() {
        return eventPayload != null && eventPayload.eventId() != null;
    }

    // ── Builder ──

    public static Builder builder(String deviceId, WorkflowInstance instance) {
        return new Builder(deviceId, instance);
    }

    public static class Builder {
        private final String deviceId;
        private final WorkflowInstance instance;
        private Map<String, Object> resolvedInputs = Map.of();
        private Map<String, Object> triggerPayload = Map.of();
        private Map<String, String> env = Map.of();
        private Map<String, Object> capabilitySnapshot = Map.of();
        private EventPayload eventPayload = null;

        Builder(String deviceId, WorkflowInstance instance) {
            this.deviceId = deviceId;
            this.instance = instance;
        }

        public Builder resolvedInputs(Map<String, Object> v) { this.resolvedInputs = v; return this; }
        public Builder triggerPayload(Map<String, Object> v) { this.triggerPayload = v; return this; }
        public Builder env(Map<String, String> v) { this.env = v; return this; }
        public Builder capabilitySnapshot(Map<String, Object> v) { this.capabilitySnapshot = v; return this; }
        /** Set the structured event payload (preferred over triggerPayload map). */
        public Builder eventPayload(EventPayload v) { this.eventPayload = v; return this; }

        public NodeContext build() {
            return new NodeContext(deviceId, instance, resolvedInputs, triggerPayload,
                    env, capabilitySnapshot, eventPayload);
        }
    }
}
