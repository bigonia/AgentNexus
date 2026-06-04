package com.zwbd.agentnexus.sdui.service.audio;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Local TTS engine using the platform-native speech synthesizer for speech
 * generation and ffmpeg for PCM format conversion.
 *
 * <h3>Platform support</h3>
 * <ul>
 *   <li><b>macOS</b> — {@code say} command → AIFF → ffmpeg → PCM</li>
 *   <li><b>Windows</b> — PowerShell SAPI (System.Speech) → WAV → ffmpeg → PCM</li>
 * </ul>
 *
 * <p>Pipeline: text → platform speech engine → audio file → ffmpeg → 22050Hz 16-bit signed LE mono PCM</p>
 */
@Slf4j
@Component
public class FfmpegTtsProvider implements TtsProvider {

    private static final int MAX_CACHE_SIZE = 200;
    private static final int PCM_SAMPLE_RATE = 22050;
    private static final String OS_NAME = System.getProperty("os.name", "").toLowerCase();

    private final AudioConversionService conversionService;
    private final MacOsTtsEngine macOsEngine;

    private volatile boolean checked;
    private volatile boolean available;

    private final Map<String, byte[]> cache = Collections.synchronizedMap(
            new LinkedHashMap<>(64, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, byte[]> eldest) {
                    return size() > MAX_CACHE_SIZE;
                }
            });

    public FfmpegTtsProvider(@Value("${sdui.tts.ffmpeg-path:ffmpeg}") String ffmpegPath) {
        // Build shared conversion service inline (it's a package-private component,
        // but we construct it directly since AudioConversionService is also a bean).
        // For consistency with the Spring context, we create supporting engines here.
        this.conversionService = new AudioConversionService(ffmpegPath);
        this.macOsEngine = new MacOsTtsEngine();
    }

    @Override
    public byte[] synthesize(String text) {
        byte[] cached = cache.get(text);
        if (cached != null) {
            log.debug("TTS cache hit: {} chars", text.length());
            return cached;
        }

        if (!checkAvailable()) return null;

        Path tempDir = null;
        try {
            tempDir = Files.createTempDirectory("tts_");
            Path textFile = tempDir.resolve("input.txt");
            // macOS say command auto-appends .aiff if missing; Windows SAPI produces .wav.
            // The audio path MUST have the correct extension so ffmpeg can find the file.
            String audioExt = isMacOs() ? ".aiff" : ".wav";
            Path audioFile = tempDir.resolve("speech_orig" + audioExt);
            Path pcmFile = tempDir.resolve("speech.pcm");

            Files.writeString(textFile, text);

            if (!generateSpeech(textFile, audioFile)) return null;
            if (!conversionService.convertToPcm(audioFile, pcmFile, PCM_SAMPLE_RATE)) return null;

            byte[] pcm = Files.readAllBytes(pcmFile);
            if (pcm.length == 0) {
                log.warn("TTS produced empty PCM");
                return null;
            }
            cache.put(text, pcm);
            log.info("TTS synthesized: {} chars → {} PCM samples, cache size={}",
                    text.length(), pcm.length / 2, cache.size());
            return pcm;
        } catch (Exception e) {
            log.error("TTS synthesis failed: {}", e.getMessage());
            return null;
        } finally {
            if (tempDir != null) {
                try {
                    Files.walk(tempDir)
                            .sorted(Comparator.reverseOrder())
                            .forEach(p -> { try { Files.delete(p); } catch (Exception ignored) {} });
                } catch (Exception ignored) {}
            }
        }
    }

    /**
     * Generate speech audio from text using the platform-native engine.
     * The output path must already include the platform-appropriate extension
     * ({@code .aiff} for macOS, {@code .wav} for Windows).
     */
    private boolean generateSpeech(Path textFile, Path audioFile) throws IOException, InterruptedException {
        if (isMacOs()) {
            return macOsEngine.generateSpeech(textFile, audioFile);
        }
        // Windows: use PowerShell SAPI
        return generateWavWindowsSapi(textFile, audioFile);
    }

    /**
     * Windows SAPI speech synthesis via PowerShell.
     */
    private boolean generateWavWindowsSapi(Path textFile, Path wavFile) throws IOException, InterruptedException {
        String script = "Add-Type -AssemblyName System.Speech; " +
                "$s = New-Object System.Speech.Synthesis.SpeechSynthesizer; " +
                "$s.SetOutputToWaveFile('" + wavFile.toAbsolutePath() + "'); " +
                "$text = Get-Content -Path '" + textFile.toAbsolutePath() + "' -Encoding UTF8 -Raw; " +
                "$s.Speak($text); " +
                "$s.Dispose()";

        Process p = new ProcessBuilder("powershell", "-NoProfile", "-NonInteractive", "-Command", script)
                .redirectErrorStream(true)
                .start();
        if (!p.waitFor(30, TimeUnit.SECONDS)) {
            p.destroyForcibly();
            log.warn("SAPI speech synthesis timed out");
            return false;
        }
        if (p.exitValue() != 0) {
            log.warn("SAPI speech synthesis failed with exit code {}", p.exitValue());
            return false;
        }
        return Files.exists(wavFile) && Files.size(wavFile) > 0;
    }

    private boolean checkAvailable() {
        if (checked) return available;
        checked = true;

        if (!conversionService.isFfmpegAvailable()) {
            log.warn("ffmpeg not available — TTS disabled");
            return false;
        }

        if (isMacOs()) {
            if (macOsEngine.isAvailable()) {
                available = true;
                log.info("ffmpeg TTS provider ready (macOS say + ffmpeg)");
            } else {
                log.warn("macOS say command not available — TTS disabled");
            }
            return available;
        }

        // Windows check
        try {
            Process p = new ProcessBuilder("powershell", "-NoProfile", "-NonInteractive", "-Command",
                    "Add-Type -AssemblyName System.Speech; exit 0").start();
            if (p.waitFor(5, TimeUnit.SECONDS) && p.exitValue() == 0) {
                available = true;
                log.info("ffmpeg TTS provider ready (Windows SAPI + ffmpeg)");
            } else {
                log.warn("Windows SAPI not available — TTS disabled");
            }
        } catch (Exception e) {
            log.warn("Platform speech engine not available: {}", e.getMessage());
        }
        return available;
    }

    private static boolean isMacOs() {
        return OS_NAME.contains("mac") || OS_NAME.contains("darwin");
    }
}
