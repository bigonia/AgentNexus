package com.zwbd.agentnexus.sdui.controller;

import com.zwbd.agentnexus.common.web.ApiResponse;
import com.zwbd.agentnexus.sdui.section.SectionTypeCatalog;
import com.zwbd.agentnexus.sdui.v2.business.BusinessConfig;
import com.zwbd.agentnexus.sdui.v2.business.BusinessConfigValidator;
import com.zwbd.agentnexus.sdui.v2.business.ResponseStep;
import com.zwbd.agentnexus.sdui.v2.business.TriggerBinding;
import com.zwbd.agentnexus.sdui.v2.business.TriggerSource;
import com.zwbd.agentnexus.sdui.v2.capability.CapabilityQueryService;
import com.zwbd.agentnexus.sdui.workflow.NodeTypeRegistry;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 静态目录与校验 API。
 *
 * <p>这里只提供<b>与具体设备无关</b>的部分：Section 类型、触发源族、可绑定动作名、节点类型。
 * 设备相关的能力目录在 {@code /capabilities/{deviceId}}——两边共用同一份定义，不各存一套。</p>
 *
 * <p>{@code /validate} 是部署前校验的独立入口，与部署接口走<b>同一个校验器</b>。不复制规则：
 * 两处真值必然漂移。</p>
 *
 * <p>接口集定义见 {@code docs/sdui/front/CLIENT_API.md} §2.4。</p>
 */
@RestController
@RequestMapping("/api/v1/sdui/events")
@RequiredArgsConstructor
public class EventCatalogController {

    private final SectionTypeCatalog sectionTypeCatalog;
    private final NodeTypeRegistry nodeTypes;
    private final BusinessConfigValidator validator;
    private final CapabilityQueryService capabilities;

    /** 全局静态目录。 */
    @GetMapping("/catalog")
    public ApiResponse<Map<String, Object>> catalog() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("sections", sections());
        result.put("triggers", triggers());
        result.put("actions", actions());
        result.put("nodeTypes", nodeTypes.nodeTypes());
        return ApiResponse.ok(result);
    }

    /** Section 类型目录（03_UI_MODEL.md §2.1 的五类及其字段）。 */
    @GetMapping("/sections")
    public ApiResponse<Object> sections() {
        return ApiResponse.ok(sectionTypeCatalog.all());
    }

    /** 触发源族。设备实际声明哪些触发源由能力 Schema 决定。 */
    @GetMapping("/triggers")
    public ApiResponse<List<Map<String, Object>>> triggers() {
        List<Map<String, Object>> result = new ArrayList<>();
        for (TriggerSource source : TriggerSource.values()) {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("source", source.wire());
            entry.put("idPrefix", source.idPrefix());
            result.add(entry);
        }
        return ApiResponse.ok(result);
    }

    /** 平台可下发的动作名清单（并集，去重）。具体设备能否使用由能力 Schema 的 usableIn 决定。 */
    @GetMapping("/actions")
    public ApiResponse<List<String>> actions() {
        Set<String> actions = new LinkedHashSet<>();
        for (String nodeType : nodeTypes.nodeTypes()) {
            actions.addAll(nodeTypes.targetActions(nodeType));
        }
        return ApiResponse.ok(new ArrayList<>(actions));
    }

    /**
     * 校验一段绑定能否被指定设备接受。
     *
     * <p>入参：{@code {deviceId, bindings: [{triggerId, token?, responses: [{action, params}]}]}}。
     * 与部署接口共用 {@link BusinessConfigValidator}，所以这里的通过结论对部署成立。</p>
     */
    @PostMapping("/validate")
    public ApiResponse<Map<String, Object>> validate(@RequestBody Map<String, Object> body) {
        String deviceId = string(body.get("deviceId"));
        if (deviceId.isBlank()) {
            return ApiResponse.error(40000, "deviceId is required");
        }
        List<TriggerBinding> bindings = new ArrayList<>();
        if (body.get("bindings") instanceof List<?> raw) {
            for (Object item : raw) {
                if (item instanceof Map<?, ?> map) {
                    bindings.add(toBinding(map));
                }
            }
        }

        BusinessConfig draft = new BusinessConfig(deviceId, 0L, bindings);
        BusinessConfigValidator.ValidationResult result =
                validator.validate(capabilities.schemaOf(deviceId).orElse(null), draft);

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("deviceId", deviceId);
        response.put("ok", result.ok());
        response.put("errors", result.errors());
        response.put("schemaSynced", capabilities.schemaOf(deviceId).isPresent());
        return ApiResponse.ok(response);
    }

    // ── 内部 ──

    private TriggerBinding toBinding(Map<?, ?> raw) {
        String triggerId = string(raw.get("triggerId"));
        String token = raw.get("token") == null ? null : string(raw.get("token"));
        List<ResponseStep> responses = new ArrayList<>();
        if (raw.get("responses") instanceof List<?> steps) {
            for (Object step : steps) {
                if (step instanceof Map<?, ?> stepMap) {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> params = stepMap.get("params") instanceof Map<?, ?> p
                            ? normalize(p) : Map.of();
                    responses.add(new ResponseStep(string(stepMap.get("action")), params));
                }
            }
        }
        return new TriggerBinding(triggerId, TriggerSource.infer(triggerId), token, responses);
    }

    private Map<String, Object> normalize(Map<?, ?> raw) {
        Map<String, Object> result = new LinkedHashMap<>();
        raw.forEach((key, value) -> result.put(String.valueOf(key), value));
        return result;
    }

    private String string(Object value) {
        return value == null ? "" : String.valueOf(value);
    }
}
