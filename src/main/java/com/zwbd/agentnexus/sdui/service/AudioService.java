package com.zwbd.agentnexus.sdui.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Base64;
import java.util.List;
import java.util.Map;

@Slf4j
@Service
public class AudioService {

    private final CommandDispatcher dispatcher;

    @Autowired(required = false)
    private TtsProvider ttsProvider;

    private static final int SAMPLE_RATE = 22050;

    public AudioService(CommandDispatcher dispatcher) {
        this.dispatcher = dispatcher;
    }

    public record PlayResult(String cmdId, int samples, int durationMs, boolean sent) {}

    public PlayResult playPreset(String deviceId, String preset) {
        String name = preset != null && isValidPreset(preset) ? preset : "notification";
        PresetDef def = lookupPreset(name);
        byte[] pcm = generatePresetPcm(def);
        String b64 = Base64.getEncoder().encodeToString(pcm);
        CommandDispatcher.DispatchResult dr = dispatcher.dispatch(deviceId, "audio.prompt.play", b64);
        log.info("Audio preset '{}' sent to device {}: {} samples, {} base64 chars, cmdId={}",
                name, deviceId, pcm.length / 2, b64.length(), dr.cmdId());
        return new PlayResult(dr.cmdId(), pcm.length / 2, def.durationMs(), dr.sent());
    }

    public PlayResult playTts(String deviceId, String text) {
        if (ttsProvider == null) {
            log.warn("TTS requested but no TtsProvider is configured: device={}, text={}", deviceId, text);
            return new PlayResult(null, 0, 0, false);
        }
        byte[] pcm = ttsProvider.synthesize(text);
        if (pcm == null || pcm.length == 0) {
            log.warn("TTS provider returned empty audio for: {}", text);
            return new PlayResult(null, 0, 0, false);
        }
        String b64 = Base64.getEncoder().encodeToString(pcm);
        CommandDispatcher.DispatchResult dr = dispatcher.dispatch(deviceId, "audio.prompt.play", b64);
        log.info("TTS sent to device {}: {} samples, {} base64 chars, cmdId={}",
                deviceId, pcm.length / 2, b64.length(), dr.cmdId());
        return new PlayResult(dr.cmdId(), pcm.length / 2, 0, dr.sent());
    }

    public boolean isTtsAvailable() {
        return ttsProvider != null;
    }

    public List<Map<String, Object>> getPresets() {
        return List.of(
                presetMap("notification", "通知", 880, 180),
                presetMap("success", "成功", 660, 200),
                presetMap("error", "错误", 440, 300),
                presetMap("warning", "警告", 660, 200),
                presetMap("click", "点击", 1000, 50),
                presetMap("beep", "蜂鸣", 1200, 80)
        );
    }

    public boolean isValidPreset(String preset) {
        return preset != null && lookupPreset(preset) != null;
    }

    private PresetDef lookupPreset(String preset) {
        return switch (preset) {
            case "notification" -> new PresetDef("notification", 880.0, 0, 180, false);
            case "success"     -> new PresetDef("success", 660.0, 880.0, 200, true);
            case "error"       -> new PresetDef("error", 440.0, 330.0, 300, true);
            case "warning"     -> new PresetDef("warning", 660.0, 0, 200, false);
            case "click"       -> new PresetDef("click", 1000.0, 0, 50, false);
            case "beep"        -> new PresetDef("beep", 1200.0, 0, 80, false);
            default            -> null;
        };
    }

    private byte[] generatePresetPcm(PresetDef def) {
        if (def.dual()) {
            int halfMs = def.durationMs() / 2;
            return generateDualTone(def.freqHz(), def.freq2Hz(), halfMs, def.durationMs() - halfMs);
        }
        return generateTone(def.freqHz(), def.durationMs());
    }

    private byte[] generateTone(double freqHz, int durationMs) {
        int samples = SAMPLE_RATE * durationMs / 1000;
        ByteBuffer buf = ByteBuffer.allocate(samples * 2).order(ByteOrder.LITTLE_ENDIAN);
        for (int i = 0; i < samples; i++) {
            short amp = (short) (12000 * Math.sin(2.0 * Math.PI * freqHz * i / SAMPLE_RATE));
            buf.putShort(amp);
        }
        return buf.array();
    }

    private byte[] generateDualTone(double freq1, double freq2, int ms1, int ms2) {
        byte[] tone1 = generateTone(freq1, ms1);
        byte[] tone2 = generateTone(freq2, ms2);
        byte[] combined = new byte[tone1.length + tone2.length];
        System.arraycopy(tone1, 0, combined, 0, tone1.length);
        System.arraycopy(tone2, 0, combined, tone1.length, tone2.length);
        return combined;
    }

    private record PresetDef(String name, double freqHz, double freq2Hz, int durationMs, boolean dual) {}

    private Map<String, Object> presetMap(String value, String label, int frequency, int durationMs) {
        return Map.of("value", value, "label", label, "frequency", frequency, "durationMs", durationMs);
    }
}
