package com.zwbd.agentnexus.sdui.service;

import com.zwbd.agentnexus.sdui.service.audio.AudioRecordHandler;
import com.zwbd.agentnexus.sdui.service.audio.SttProvider;
import com.zwbd.agentnexus.sdui.service.audio.TtsProvider;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

@Slf4j
@Service
public class AudioService {

    private final SduiProtocolService protocolService;

    @Autowired(required = false)
    private TtsProvider ttsProvider;

    @Autowired(required = false)
    private SttProvider sttProvider;

    @Autowired(required = false)
    private AudioRecordHandler audioRecordHandler;

    private static final int SAMPLE_RATE = 22050;
    // Max raw PCM bytes per binary frame. 32 KB = ~1.5 s of audio at 22050 Hz mono 16-bit.
    // Well under UI3_MAX_BLOB_BYTES (512 KB) and ESP32 PSRAM limits.
    private static final int PCM_CHUNK_BYTES = 32 * 1024;

    public AudioService(SduiProtocolService protocolService) {
        this.protocolService = protocolService;
    }

    public record PlayResult(String cmdId, int samples, int durationMs, boolean sent) {}

    /**
     * Wire the AudioRecordHandler's processor callback to our STT pipeline.
     * Done in @PostConstruct to avoid circular dependency issues.
     */
    @PostConstruct
    public void wireAudioRecordHandler() {
        if (audioRecordHandler != null) {
            audioRecordHandler.setProcessor((deviceId, audioBytes, format) -> {
                String text = transcribeAudio(audioBytes, format);
                return (text != null && !text.isEmpty()) ? text : null;
            });
            log.info("AudioRecordHandler STT processor wired");
        }
    }

    // ── STT (Speech-to-Text) ──

    /**
     * Transcribe audio data to text via the configured STT provider.
     *
     * @param audioBytes  raw audio data
     * @param format      audio format hint (e.g. "wav", "pcm", "opus"); null for auto-detect
     * @return transcribed text, or null if STT is unavailable or failed
     */
    public String transcribeAudio(byte[] audioBytes, String format) {
        if (sttProvider == null) {
            log.debug("STT requested but no SttProvider is configured");
            return null;
        }
        String text = sttProvider.transcribe(audioBytes, format);
        if (text != null && !text.isEmpty()) {
            log.info("STT transcribed {} chars via {}", text.length(),
                    sttProvider.getClass().getSimpleName());
        }
        return text;
    }

    /**
     * Check whether an STT provider is available.
     */
    public boolean isSttAvailable() {
        return sttProvider != null;
    }

    /**
     * Send PCM audio as one or more binary frames (msgType=17).
     * No JSON wrapping, no base64 overhead — raw PCM in binary frames.
     */
    private boolean sendAudioChunked(String deviceId, byte[] pcm) {
        if (pcm == null || pcm.length == 0) return false;
        boolean allSent = true;
        int offset = 0;
        while (offset < pcm.length) {
            int len = Math.min(PCM_CHUNK_BYTES, pcm.length - offset);
            byte[] chunk = new byte[len];
            System.arraycopy(pcm, offset, chunk, 0, len);
            if (!protocolService.sendAudioPcm(deviceId, chunk)) {
                allSent = false;
            }
            offset += len;
        }
        return allSent;
    }

    public PlayResult playPreset(String deviceId, String preset) {
        String name = preset != null && isValidPreset(preset) ? preset : "notification";
        PresetDef def = lookupPreset(name);
        byte[] pcm = generatePresetPcm(def);
        boolean sent = sendAudioChunked(deviceId, pcm);
        log.info("Audio preset '{}' sent to device {}: {} samples ({} frames), sent={}",
                name, deviceId, pcm.length / 2,
                (pcm.length + PCM_CHUNK_BYTES - 1) / PCM_CHUNK_BYTES, sent);
        return new PlayResult(null, pcm.length / 2, def.durationMs(), sent);
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
        boolean sent = sendAudioChunked(deviceId, pcm);
        log.info("TTS sent to device {}: {} samples ({} frames), sent={}",
                deviceId, pcm.length / 2,
                (pcm.length + PCM_CHUNK_BYTES - 1) / PCM_CHUNK_BYTES, sent);
        return new PlayResult(null, pcm.length / 2, 0, sent);
    }

    public PlayResult playWav(String deviceId, byte[] wavBytes) {
        byte[] pcm = extractPcmFromWav(wavBytes);
        if (pcm == null || pcm.length == 0) {
            log.warn("WAV playback requested but no PCM data could be extracted: device={}", deviceId);
            return new PlayResult(null, 0, 0, false);
        }
        boolean sent = sendAudioChunked(deviceId, pcm);
        int durationMs = pcm.length * 1000 / (SAMPLE_RATE * 2);
        log.info("WAV artifact sent to device {}: {} samples ({} frames), sent={}",
                deviceId, pcm.length / 2,
                (pcm.length + PCM_CHUNK_BYTES - 1) / PCM_CHUNK_BYTES, sent);
        return new PlayResult(null, pcm.length / 2, durationMs, sent);
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

    private byte[] extractPcmFromWav(byte[] wavBytes) {
        if (wavBytes == null || wavBytes.length < 44) {
            return wavBytes;
        }
        String riff = new String(wavBytes, 0, 4, StandardCharsets.US_ASCII);
        String wave = new String(wavBytes, 8, 4, StandardCharsets.US_ASCII);
        if (!"RIFF".equals(riff) || !"WAVE".equals(wave)) {
            return wavBytes;
        }

        int offset = 12;
        while (offset + 8 <= wavBytes.length) {
            String chunkId = new String(wavBytes, offset, 4, StandardCharsets.US_ASCII);
            int chunkSize = ByteBuffer.wrap(wavBytes, offset + 4, 4)
                    .order(ByteOrder.LITTLE_ENDIAN)
                    .getInt();
            int dataStart = offset + 8;
            if ("data".equals(chunkId)) {
                int dataSize = Math.max(0, Math.min(chunkSize, wavBytes.length - dataStart));
                byte[] pcm = new byte[dataSize];
                System.arraycopy(wavBytes, dataStart, pcm, 0, dataSize);
                return pcm;
            }
            offset = dataStart + chunkSize + (chunkSize % 2);
        }
        return null;
    }

    private record PresetDef(String name, double freqHz, double freq2Hz, int durationMs, boolean dual) {}

    private Map<String, Object> presetMap(String value, String label, int frequency, int durationMs) {
        return Map.of("value", value, "label", label, "frequency", frequency, "durationMs", durationMs);
    }
}
