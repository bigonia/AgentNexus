package com.zwbd.agentnexus.sdui.service;

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
 * Local TTS engine using Windows SAPI (SpeechSynthesizer) for speech generation
 * and ffmpeg for PCM format conversion.
 *
 * Pipeline: text → SAPI WAV → ffmpeg → 22050Hz 16-bit signed LE mono PCM
 */
@Slf4j
@Component
public class FfmpegTtsProvider implements TtsProvider {

    private final String ffmpegPath;
    private volatile boolean checked;
    private volatile boolean available;

    private static final int MAX_CACHE_SIZE = 200;
    private final Map<String, byte[]> cache = Collections.synchronizedMap(
            new LinkedHashMap<>(64, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, byte[]> eldest) {
                    return size() > MAX_CACHE_SIZE;
                }
            });

    public FfmpegTtsProvider(@Value("${sdui.tts.ffmpeg-path:ffmpeg}") String ffmpegPath) {
        this.ffmpegPath = ffmpegPath;
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
            Path wavFile = tempDir.resolve("speech.wav");
            Path pcmFile = tempDir.resolve("speech.pcm");

            Files.writeString(textFile, text);

            if (!generateWav(textFile, wavFile)) return null;
            if (!convertToPcm(wavFile, pcmFile)) return null;

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

    private boolean generateWav(Path textFile, Path wavFile) throws IOException, InterruptedException {
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

    private boolean convertToPcm(Path wavFile, Path pcmFile) throws IOException, InterruptedException {
        Process p = new ProcessBuilder(
                ffmpegPath, "-y",
                "-i", wavFile.toAbsolutePath().toString(),
                "-f", "s16le",
                "-acodec", "pcm_s16le",
                "-ar", "22050",
                "-ac", "1",
                pcmFile.toAbsolutePath().toString())
                .redirectErrorStream(true)
                .start();
        if (!p.waitFor(15, TimeUnit.SECONDS)) {
            p.destroyForcibly();
            log.warn("ffmpeg PCM conversion timed out");
            return false;
        }
        if (p.exitValue() != 0) {
            log.warn("ffmpeg PCM conversion failed with exit code {}", p.exitValue());
            return false;
        }
        return Files.exists(pcmFile) && Files.size(pcmFile) > 0;
    }

    private boolean checkAvailable() {
        if (checked) return available;
        checked = true;
        try {
            Process p = new ProcessBuilder(ffmpegPath, "-version")
                    .redirectErrorStream(true)
                    .start();
            if (p.waitFor(5, TimeUnit.SECONDS) && p.exitValue() == 0) {
                available = true;
                log.info("ffmpeg TTS provider ready: {}", ffmpegPath);
            } else {
                log.warn("ffmpeg exited abnormally at '{}'", ffmpegPath);
            }
        } catch (Exception e) {
            log.warn("ffmpeg not available at '{}': {}", ffmpegPath, e.getMessage());
        }
        return available;
    }
}
