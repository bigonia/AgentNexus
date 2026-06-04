package com.zwbd.agentnexus.sdui.workflow;

import java.time.Instant;

/**
 * Represents a Section slot on a device page.
 * Tracks ownership by workflows for conflict detection.
 */
public class SectionSlot {

    public enum BindingPolicy { EXCLUSIVE, SHARED_READ }

    private final String slotId;
    private final String pageId;
    private final String sectionType;
    private Binding binding;
    private final BindingPolicy bindingPolicy;

    public record Binding(
            String workflowId,
            String definitionName,
            String outputName,
            Instant installedAt
    ) {}

    public SectionSlot(String slotId, String pageId, String sectionType) {
        this(slotId, pageId, sectionType, BindingPolicy.EXCLUSIVE);
    }

    public SectionSlot(String slotId, String pageId, String sectionType, BindingPolicy bindingPolicy) {
        this.slotId = slotId;
        this.pageId = pageId;
        this.sectionType = sectionType;
        this.bindingPolicy = bindingPolicy;
    }

    public String slotId() { return slotId; }
    public String pageId() { return pageId; }
    public String sectionType() { return sectionType; }
    public Binding binding() { return binding; }
    public BindingPolicy bindingPolicy() { return bindingPolicy; }
    public boolean isFree() { return binding == null; }

    public boolean bind(String workflowId, String definitionName, String outputName) {
        if (this.binding != null && this.bindingPolicy == BindingPolicy.EXCLUSIVE) {
            return false;
        }
        this.binding = new Binding(workflowId, definitionName, outputName, Instant.now());
        return true;
    }

    public void unbind() {
        this.binding = null;
    }

    public boolean conflictsWith(SectionSlot other) {
        return this.slotId.equals(other.slotId) && this.pageId.equals(other.pageId);
    }
}
