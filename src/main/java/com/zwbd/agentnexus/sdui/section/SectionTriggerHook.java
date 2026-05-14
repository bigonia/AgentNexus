package com.zwbd.agentnexus.sdui.section;

/**
 * Callback interface for external integration with the section orchestration pipeline.
 * Register via {@link SectionOrchestrationService#registerHook(SectionTriggerHook)}.
 */
public interface SectionTriggerHook {

    enum TriggerType { SCENE, PATCH, AUTO_UPDATE }

    void onSectionSent(String deviceId, String pageId, TriggerType triggerType, String json);
}
