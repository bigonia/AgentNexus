package com.zwbd.agentnexus.sdui.service;

import com.zwbd.agentnexus.sdui.DeviceSessionManager;
import com.zwbd.agentnexus.sdui.protocol.CapabilitySchema;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.*;

@Service
@RequiredArgsConstructor
public class ConsoleLayoutService {

    private final SduiCapabilityService capabilityService;
    private final DeviceSessionManager sessionManager;
    private final AudioService audioService;

    public Map<String, Object> buildLayout(String deviceId) {
        Optional<CapabilitySchema.CapabilitySnapshot> capsOpt = capabilityService.getCapabilities(deviceId);
        boolean online = sessionManager.isDeviceOnline(deviceId);

        List<Map<String, Object>> panels = new ArrayList<>();
        int order = 1;

        if (capsOpt.isPresent()) {
            CapabilitySchema.CapabilitySnapshot caps = capsOpt.get();
            Set<String> commands = collectCommands(caps.outputs());
            Set<String> capabilityIds = collectCapabilityIds(caps.outputs());
            boolean hasInputEvents = hasAnyInputEvents(caps.inputs());

            if (commands.contains("display.brightness.set")) {
                panels.add(buildDisplayPanel(order++));
            }

            if (capabilityIds.contains("audio.prompt") || commands.contains("audio.prompt.play")) {
                panels.add(buildAudioPanel(order++));
            }

            if (capabilityIds.contains("rgb.effect") || commands.contains("rgb.effect.set")) {
                panels.add(buildRgbPanel(order++));
            }

            if (hasInputEvents) {
                panels.add(buildInputEventsPanel(order++));
            }
        }

        panels.add(buildSystemPanel(order));

        Map<String, Object> layout = new LinkedHashMap<>();
        layout.put("deviceId", deviceId);
        layout.put("online", online);
        layout.put("panels", panels);
        return layout;
    }

    private Set<String> collectCommands(List<CapabilitySchema.OutputCapability> outputs) {
        Set<String> cmds = new LinkedHashSet<>();
        for (CapabilitySchema.OutputCapability o : outputs) {
            if (o.enabled() && o.commands() != null) {
                cmds.addAll(o.commands());
            }
        }
        return cmds;
    }

    private Set<String> collectCapabilityIds(List<CapabilitySchema.OutputCapability> outputs) {
        Set<String> ids = new LinkedHashSet<>();
        for (CapabilitySchema.OutputCapability o : outputs) {
            if (o.enabled() && o.capability() != null) {
                ids.add(o.capability());
            }
        }
        return ids;
    }

    private boolean hasAnyInputEvents(List<CapabilitySchema.InputCapability> inputs) {
        for (CapabilitySchema.InputCapability in : inputs) {
            if (in.enabled() && in.events() != null && !in.events().isEmpty()) {
                return true;
            }
        }
        return false;
    }

    private Map<String, Object> buildDisplayPanel(int order) {
        Map<String, Object> panel = new LinkedHashMap<>();
        panel.put("id", "display");
        panel.put("label", "显示控制");
        panel.put("icon", "monitor");
        panel.put("order", order);
        panel.put("controls", List.of(
                buildControl("slider", "brightness", "亮度", Map.of(
                        "command", "display.brightness.set",
                        "min", 0, "max", 100, "step", 1
                ))
        ));
        return panel;
    }

    private Map<String, Object> buildAudioPanel(int order) {
        List<Map<String, Object>> presetOptions = new ArrayList<>();
        for (Map<String, Object> preset : audioService.getPresets()) {
            presetOptions.add(Map.of(
                    "value", preset.get("value"),
                    "label", preset.get("label")
            ));
        }

        Map<String, Object> panel = new LinkedHashMap<>();
        panel.put("id", "audio");
        panel.put("label", "音频");
        panel.put("icon", "volume-2");
        panel.put("order", order);
        panel.put("controls", List.of(
                buildControl("preset_buttons", "presets", "提示音", Map.of("options", presetOptions)),
                buildControl("tts_input", "tts", "TTS 朗读", Map.of()),
                buildControl("slider", "volume", "音量", Map.of(
                        "command", "audio.volume.set",
                        "min", 0, "max", 100, "step", 1
                ))
        ));
        return panel;
    }

    private Map<String, Object> buildRgbPanel(int order) {
        Map<String, Object> panel = new LinkedHashMap<>();
        panel.put("id", "rgb");
        panel.put("label", "RGB 灯效");
        panel.put("icon", "palette");
        panel.put("order", order);
        panel.put("controls", List.of(
                buildControl("color_picker", "color", "颜色", Map.of("default", "#ffffff")),
                buildControl("mode_selector", "mode", "模式", Map.of("options", List.of(
                        Map.of("value", "solid", "label", "常亮"),
                        Map.of("value", "blink", "label", "闪烁"),
                        Map.of("value", "breathe", "label", "呼吸"),
                        Map.of("value", "rainbow", "label", "彩虹"),
                        Map.of("value", "chase", "label", "跑马灯"),
                        Map.of("value", "off", "label", "关闭")
                ))),
                buildControl("slider", "period", "周期(ms)", Map.of(
                        "min", 200, "max", 5000, "step", 100, "default", 2000
                )),
                buildControl("button", "apply", "应用", Map.of("style", "primary"))
        ));
        return panel;
    }

    private Map<String, Object> buildInputEventsPanel(int order) {
        Map<String, Object> panel = new LinkedHashMap<>();
        panel.put("id", "inputs");
        panel.put("label", "输入事件");
        panel.put("icon", "activity");
        panel.put("order", order);
        panel.put("stream", "/api/v1/sdui/devices/{deviceId}/console/events/stream");
        return panel;
    }

    private Map<String, Object> buildSystemPanel(int order) {
        Map<String, Object> panel = new LinkedHashMap<>();
        panel.put("id", "system");
        panel.put("label", "系统");
        panel.put("icon", "settings");
        panel.put("order", order);
        panel.put("controls", List.of(
                buildControl("button", "reboot", "重启设备", Map.of(
                        "confirm", true,
                        "confirmText", "确定要重启设备吗？"
                ))
        ));
        return panel;
    }

    private Map<String, Object> buildControl(String type, String id, String label, Map<String, Object> props) {
        Map<String, Object> control = new LinkedHashMap<>();
        control.put("type", type);
        control.put("id", id);
        control.put("label", label);
        control.putAll(props);
        return control;
    }
}
