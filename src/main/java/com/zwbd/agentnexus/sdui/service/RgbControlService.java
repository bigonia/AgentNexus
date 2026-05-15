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

        String value = buildCommandValue(mode, color, periodMs);
        CommandDispatcher.DispatchResult result = dispatcher.dispatch(deviceId, "rgb.effect.set", value);
        return buildResponse(deviceId, result, mode, color, periodMs);
    }

    private String buildCommandValue(String mode, String color, Integer periodMs) {
        StringBuilder sb = new StringBuilder();
        sb.append(mode);
        if (color != null && !color.isBlank()) {
            String hex = color.startsWith("#") ? color.substring(1) : color;
            sb.append(':').append(hex);
        }
        if (periodMs != null && !"solid".equals(mode) && !"rainbow".equals(mode)) {
            sb.append(':').append(periodMs);
        }
        return sb.toString();
    }

    private Map<String, Object> buildResponse(String deviceId, CommandDispatcher.DispatchResult result,
                                               String mode, String color, Integer periodMs) {
        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("sent", result.sent());
        resp.put("deviceId", deviceId);
        resp.put("cmdId", result.cmdId());
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
