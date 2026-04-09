package com.zwbd.agentnexus.sdui.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zwbd.agentnexus.sdui.dto.SduiGenerateAppRequest;
import com.zwbd.agentnexus.sdui.dto.SduiGeneratedAppResponse;
import com.zwbd.agentnexus.sdui.model.SduiApp;
import com.zwbd.agentnexus.sdui.model.SduiAsset;
import com.zwbd.agentnexus.sdui.model.SduiAssetBinding;
import com.zwbd.agentnexus.sdui.model.SduiAppVersion;
import com.zwbd.agentnexus.sdui.repo.SduiAppRepository;
import com.zwbd.agentnexus.sdui.repo.SduiAppVersionRepository;
import com.zwbd.agentnexus.sdui.repo.SduiAssetBindingRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

@Slf4j
@Service
@RequiredArgsConstructor
public class SduiAppService {

    private final SduiAppRepository appRepository;
    private final SduiAppVersionRepository versionRepository;
    private final SduiAssetBindingRepository assetBindingRepository;
    private final ObjectMapper objectMapper;
    private final SduiScriptValidator scriptValidator;
    private final SduiAssetSetService assetSetService;

    @Qualifier("pythonCoder")
    private final ChatClient pythonCoder;

    @Transactional
    public SduiGeneratedAppResponse generate(SduiGenerateAppRequest request) {
        SduiAssetSetService.SelectedAssetContext selected = assetSetService.buildSelectedAssetContext(request.assetSetIds());
        String selectedAssetsSummary = toJson(selected.promptSets());
        GenerationPayload payload = askAiForScript(request.requirement(), request.sceneTags(), null, selectedAssetsSummary);
        return persistGeneratedPayload(payload, request.requirement(), selected.selectedSetIds(), selected.selectedAssets());
    }

    @Transactional
    public SduiGeneratedAppResponse revise(String appId, String instruction) {
        SduiApp app = appRepository.findById(appId).orElseThrow(() -> new IllegalArgumentException("app not found"));
        SduiAppVersion latest = versionRepository.findFirstByAppOrderByVersionNoDesc(app)
                .orElseThrow(() -> new IllegalArgumentException("app version not found"));
        SduiAssetSetService.SelectedAssetContext selected = assetSetService.buildSelectedAssetContext(app.getSelectedAssetSetIds());
        String selectedAssetsSummary = toJson(selected.promptSets());
        GenerationPayload payload = askAiForScript(instruction, app.getSceneTags(), latest.getScriptContent(), selectedAssetsSummary);
        payload.templateName = app.getName();
        payload.templateDescription = Optional.ofNullable(payload.templateDescription).orElse(app.getDescription());
        return persistGeneratedPayload(payload, instruction, selected.selectedSetIds(), selected.selectedAssets());
    }

    @Transactional(readOnly = true)
    public List<SduiApp> listApps() {
        return appRepository.findAll();
    }

    @Transactional(readOnly = true)
    public List<SduiAppVersion> listVersions(String appId) {
        SduiApp app = appRepository.findById(appId).orElseThrow(() -> new IllegalArgumentException("app not found"));
        return versionRepository.findByAppOrderByVersionNoDesc(app);
    }

    private SduiGeneratedAppResponse persistGeneratedPayload(GenerationPayload payload, String promptSnapshot,
                                                             List<String> selectedAssetSetIds,
                                                             List<SduiAsset> selectedAssets) {
        List<String> tags = payload.sceneTags == null ? new ArrayList<>() : payload.sceneTags;
        String name = sanitizeName(payload.templateName);
        String desc = sanitizeDescription(payload.templateDescription);
        String script = payload.scriptContent == null ? "" : payload.scriptContent;
        String previewLayout = payload.previewLayoutJson == null ? "{}" : payload.previewLayoutJson;

        SduiApp app = appRepository.findByName(name).orElseGet(SduiApp::new);
        app.setName(name);
        app.setDescription(desc);
        app.setSceneTags(tags);
        app.setSelectedAssetSetIds(selectedAssetSetIds == null ? new ArrayList<>() : new ArrayList<>(selectedAssetSetIds));
        app = appRepository.save(app);

        autoBindSelectedAssets(app, selectedAssets);

        int versionNo = versionRepository.findFirstByAppOrderByVersionNoDesc(app)
                .map(SduiAppVersion::getVersionNo)
                .orElse(0) + 1;

        String validationReport = scriptValidator.validate(script);

        SduiAppVersion version = new SduiAppVersion();
        version.setApp(app);
        version.setVersionNo(versionNo);
        version.setScriptContent(script);
        version.setLlmPromptSnapshot(promptSnapshot);
        version.setValidationReport(validationReport);
        version.setPublished(false);
        version = versionRepository.save(version);

        return new SduiGeneratedAppResponse(
                app.getId(),
                version.getId(),
                version.getVersionNo(),
                app.getName(),
                app.getDescription(),
                app.getSelectedAssetSetIds(),
                previewLayout,
                validationReport
        );
    }

    private GenerationPayload askAiForScript(String requirement, List<String> tags, String currentScript, String selectedAssetsSummary) {
        String validationFeedback = "";
        Exception lastException = null;
        for (int attempt = 1; attempt <= 3; attempt++) {
            String prompt = buildGenerationPrompt(requirement, tags, currentScript, validationFeedback, selectedAssetsSummary);
            String response = pythonCoder.prompt(prompt).call().content();
            String jsonText = extractJson(response);
            try {
                JsonNode root = objectMapper.readTree(jsonText);
                GenerationPayload payload = new GenerationPayload();
                payload.templateName = root.path("template_name").asText("SDUI Template");
                payload.templateDescription = root.path("template_description").asText("AI generated SDUI template");
                payload.previewLayoutJson = root.path("preview_layout_json").asText("{}");
                payload.scriptContent = root.path("script_content").asText("");
                if (root.has("scene_tags") && root.get("scene_tags").isArray()) {
                    payload.sceneTags = new ArrayList<>();
                    for (JsonNode node : root.get("scene_tags")) {
                        payload.sceneTags.add(node.asText());
                    }
                }
                String report = scriptValidator.validate(payload.scriptContent);
                if ("OK".equals(report)) {
                    return payload;
                }
                validationFeedback = "Previous script failed validation: " + report;
                log.warn("Generated script failed validation on attempt {}: {}", attempt, report);
            } catch (Exception e) {
                lastException = e;
                validationFeedback = "Previous output is not valid strict JSON with required fields.";
                log.warn("Failed to parse generation json on attempt {}", attempt, e);
            }
        }
        log.warn("Fallback to minimal script after retries. lastError={}", lastException == null ? "validation_failed" : lastException.getMessage());
        GenerationPayload fallback = new GenerationPayload();
        fallback.templateName = "SDUI Template";
        fallback.templateDescription = "Fallback template after generation failure";
        fallback.sceneTags = tags == null ? new ArrayList<>() : tags;
        fallback.previewLayoutJson = """
                {"children":[{"type":"label","id":"title","text":"SDUI App"}]}
                """;
        fallback.scriptContent = """
                def on_start(ctx):
                    return {"action":"layout","page_id":"home","payload":{"children":[{"type":"label","id":"title","text":"SDUI App Ready"}]}}

                def on_event(ctx, event):
                    return {"action":"noop","page_id":"home","payload":{}}
                """;
        return fallback;
    }

    private String buildGenerationPrompt(String requirement, List<String> tags, String currentScript,
                                         String validationFeedback, String selectedAssetsSummary) {
        String prompt = """
                You are an SDUI Python app generator for an embedded terminal.
                Output STRICT JSON only. Do not output markdown, explanation, or code fences.

                Required output JSON fields:
                - template_name: 1-40 chars
                - template_description: 10-200 chars
                - scene_tags: string array, max 5 items
                - preview_layout_json: a renderable ui/layout payload JSON string
                - script_content: Python script with def on_start(ctx) and def on_event(ctx, event)

                Runtime contract (MUST follow exactly):
                - Python runtime is sandboxed. Do NOT use import, open(), eval(), exec(), __import__.
                - Available context in on_start/on_event:
                  ctx["device_id"], ctx["app_id"], ctx["device_state"], ctx["assets"], ctx["store"], ctx["topic"]
                - Global capability object is "cap":
                  cap.store.get(key, default), cap.store.set(key, value)
                  cap.asset_get(asset_id), cap.asset_search(tags, limit), cap.device_get_state()
                - Return action dict only:
                  {"action":"layout","page_id":"home","payload":...}
                  {"action":"update","page_id":"home","payload":...}
                  {"action":"noop","page_id":"home","payload":{}}
                - NEVER return any other action.

                Resource context policy:
                - Only selected asset sets below are available to this app during generation.
                - Do NOT reference assets outside the selected sets.
                - If selected assets are empty, generate a resource-independent UI and fallback copy.
                - Selected sets below are compact structural summaries, not full asset catalogs.
                - Do NOT hardcode per-item names from context; generate logic that discovers assets at runtime.
                - Prefer tag/type/usage-driven selection logic (e.g. cap.asset_search + processed_payload metadata).

                Selected asset sets (compact JSON):
                %s

                MUST follow terminal protocol style:
                - Use only SDUI component types: container,label,button,image,bar,slider,scene,overlay,viewport,widget
                - NEVER use Flutter/React style DSL like: column,row,text,props,style
                - Layout fields use protocol keys: flex,justify,align_items,w,h,pad,gap,font_size,text_color
                - Root payload must contain children
                - Prefer card layout: scene + centered container card
                - Use stable unique ids for every component
                - Keep size compact for embedded screen (title <= 24, body 11-18)
                - Prefer 4-9 visible components in one page for readability/perf balance
                - Use mostly ui/update for event responses; use ui/layout only for scene/page rebuild

                Response action constraints for script:
                - on_start must return {"action":"layout","page_id":"home","payload":...}
                - on_event should return update/noop in normal interactions
                - action can only be layout/update/noop

                Visual quality reference (soft guidance, not mandatory template lock):
                - Background layer first (scene/container), content card above it.
                - Strong hierarchy: title + subtitle + primary action + status/progress.
                - Avoid giant flat blocks; use radius/border/gap/pad to build depth.
                - Keep text concise and aligned for round-screen safe area.
                - You MAY adapt or deviate from reference structures if it better matches requirement.
                - Do NOT force every app into identical card composition; prioritize scenario fit and usability.
                - Avoid reusing the same literal ids/texts from examples unless requirement explicitly asks for them.

                Example layout style snippet (reference only, do not copy mechanically):
                {"children":[{"type":"container","id":"card","w":"92%%","h":"88%%","align":"center","pad":12,"radius":20,"bg_color":"#13263A","flex":"column","gap":8,"children":[{"type":"label","id":"title","text":"Now Playing","font_size":22,"text_color":"#E8F3FF"},{"type":"button","id":"btn_play","text":"Play","w":96,"h":40,"on_click":"server://ui/click?action=play"}]}]}

                Example script pattern (reference only; keep update-first and stateful style):
                def on_start(ctx):
                    count = ctx.get("store", {}).get("count", 0)
                    return {"action":"layout","page_id":"home","payload":{"children":[
                        {"type":"container","id":"root","w":"100%%","h":"100%%","flex":"column","justify":"center","align_items":"center","gap":10,"children":[
                            {"type":"label","id":"title","text":"Counter","font_size":22,"text_color":"#E8F3FF"},
                            {"type":"label","id":"count_text","text":str(count),"font_size":18,"text_color":"#BFE4FF"},
                            {"type":"button","id":"btn_inc","text":"+1","w":90,"h":40,"on_click":"server://ui/click?action=inc"}
                        ]}
                    ]}}

                def on_event(ctx, event):
                    action = (event or {}).get("action", "")
                    count = cap.store.get("count", 0)
                    if action == "inc":
                        count += 1
                        cap.store.set("count", count)
                        return {"action":"update","page_id":"home","payload":{"id":"count_text","text":str(count)}}
                    return {"action":"noop","page_id":"home","payload":{}}

                User requirement:
                %s

                Suggested tags:
                %s
                """.formatted(selectedAssetsSummary, requirement, tags == null ? List.of() : tags);
        if (currentScript != null && !currentScript.isBlank()) {
            prompt = prompt + "\nRevise based on current script:\n" + currentScript;
        }
        if (validationFeedback != null && !validationFeedback.isBlank()) {
            prompt = prompt + "\n\nFix required:\n" + validationFeedback;
        }
        return prompt;
    }

    private void autoBindSelectedAssets(SduiApp app, List<SduiAsset> selectedAssets) {
        if (app == null || selectedAssets == null || selectedAssets.isEmpty()) {
            return;
        }
        for (SduiAsset asset : selectedAssets) {
            if (asset == null || asset.getId() == null) {
                continue;
            }
            if (assetBindingRepository.existsByApp_IdAndAsset_Id(app.getId(), asset.getId())) {
                continue;
            }
            SduiAssetBinding binding = new SduiAssetBinding();
            binding.setApp(app);
            binding.setAsset(asset);
            binding.setUsageType("set_ctx");
            assetBindingRepository.save(binding);
        }
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value == null ? List.of() : value);
        } catch (Exception e) {
            return "[]";
        }
    }

    private String extractJson(String raw) {
        if (raw == null || raw.isBlank()) {
            return "{}";
        }
        String trimmed = raw.trim();
        int firstBrace = trimmed.indexOf('{');
        int lastBrace = trimmed.lastIndexOf('}');
        if (firstBrace >= 0 && lastBrace > firstBrace) {
            return trimmed.substring(firstBrace, lastBrace + 1);
        }
        return trimmed;
    }

    private String sanitizeName(String name) {
        String base = (name == null || name.isBlank()) ? "SDUI Template" : name.trim();
        return base.length() > 40 ? base.substring(0, 40) : base;
    }

    private String sanitizeDescription(String desc) {
        String base = (desc == null || desc.isBlank()) ? "AI generated SDUI template" : desc.trim();
        return base.length() > 200 ? base.substring(0, 200) : base;
    }

    private static class GenerationPayload {
        private String templateName;
        private String templateDescription;
        private List<String> sceneTags;
        private String previewLayoutJson;
        private String scriptContent;
    }
}

