package com.zwbd.agentnexus.sdui.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Slf4j
@Service
@RequiredArgsConstructor
public class RgbControlService {

    private final CommandDispatcher dispatcher;

    private static final Set<String> VALID_MODES = Set.of("solid", "blink", "breathe", "rainbow", "chase", "off");

    public Map<String, Object> apply(String deviceId, String mode, String color, Integer periodMs) {
        if (mode == null || !VALID_MODES.contains(mode)) {
            return Map.of("sent", false, "deviceId", deviceId, "error",
                    "invalid mode: " + mode + ", valid modes: " + VALID_MODES);
        }

        if ("off".equals(mode)) {
            CommandDispatcher.DispatchResult result = dispatcher.dispatch(deviceId, "rgb.off", null);
            return buildResponse(deviceId, result, mode, null, null);
        }

        Map<String, Object> params = buildRgbParams(mode, color, periodMs);
        String action = "solid".equals(mode) ? "rgb_set" : "rgb_policy";
        CommandDispatcher.DispatchResult result = dispatcher.dispatchWithAction(
                deviceId, "rgb.effect.set", action, params);
        return buildResponse(deviceId, result, mode, color, periodMs);
    }

    private Map<String, Object> buildRgbParams(String mode, String color, Integer periodMs) {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("mode", mode);

        if (color != null && !color.isBlank()) {
            String hex = color.startsWith("#") ? color.substring(1) : color;
            try {
                int rgb = Integer.parseInt(hex, 16);
                params.put("r", (rgb >> 16) & 0xFF);
                params.put("g", (rgb >> 8) & 0xFF);
                params.put("b", rgb & 0xFF);
            } catch (NumberFormatException e) {
                params.put("r", 255);
                params.put("g", 255);
                params.put("b", 255);
            }
        } else {
            params.put("r", 255);
            params.put("g", 255);
            params.put("b", 255);
        }

        if (!"solid".equals(mode) && !"rainbow".equals(mode)) {
            params.put("period_ms", periodMs != null ? periodMs : 2000);
        } else if ("rainbow".equals(mode) && periodMs != null) {
            params.put("period_ms", periodMs);
        }

        return params;
    }

    private Map<String, Object> buildResponse(String deviceId, CommandDispatcher.DispatchResult result,
                                               String mode, String color, Integer periodMs) {
        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("sent", result.sent());
        resp.put("deviceId", deviceId);
        resp.put("cmdId", result.cmdId());
        resp.put("action", result.action());
        resp.put("mode", mode);
        if (color != null) resp.put("color", color);
        if (periodMs != null) resp.put("periodMs", periodMs);
        return resp;
    }

    public List<Map<String, Object>> getModes() {
        return List.of(
                Map.of("value", "solid", "label", "常亮"),
                Map.of("value", "blink", "label", "闪烁"),
                Map.of("value", "breathe", "label", "呼吸"),
                Map.of("value", "rainbow", "label", "彩虹"),
                Map.of("value", "chase", "label", "跑马灯"),
                Map.of("value", "off", "label", "关闭")
        );
    }
}
