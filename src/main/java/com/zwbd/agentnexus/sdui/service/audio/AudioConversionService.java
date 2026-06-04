package com.zwbd.agentnexus.sdui.service.audio;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

/**
 * Shared ffmpeg audio conversion utilities used by both TTS and STT providers.
 * Package-private — not exposed as public API; {@link com.zwbd.agentnexus.sdui.service.AudioService}
 * is the external entry point.
 */
@Slf4j
@Component
class AudioConversionService {

    private final String ffmpegPath;

    AudioConversionService(@Value("${sdui.tts.ffmpeg-path:ffmpeg}") String ffmpegPath) {
        this.ffmpegPath = ffmpegPath;
    }

    /**
     * Convert an audio file to 16-bit signed LE mono PCM at the given sample rate.
     * Used by TTS providers for final output format.
     *
     * @param inputFile  source audio file (WAV, AIFF, etc.)
     * @param outputFile destination PCM file
     * @param sampleRate target sample rate in Hz (e.g. 22050 for TTS, 16000 for STT)
     * @return true if conversion succeeded and the output file is non-empty
     */
    boolean convertToPcm(Path inputFile, Path outputFile, int sampleRate) {
        try {
            Process p = new ProcessBuilder(
                    ffmpegPath, "-y",
                    "-i", inputFile.toAbsolutePath().toString(),
                    "-f", "s16le",
                    "-acodec", "pcm_s16le",
                    "-ar", String.valueOf(sampleRate),
                    "-ac", "1",
                    outputFile.toAbsolutePath().toString())
                    .redirectErrorStream(true)
                    .start();
            if (!p.waitFor(15, TimeUnit.SECONDS)) {
                p.destroyForcibly();
                log.warn("ffmpeg PCM conversion timed out for {}", inputFile.getFileName());
                return false;
            }
            if (p.exitValue() != 0) {
                log.warn("ffmpeg PCM conversion failed with exit code {} for {}",
                        p.exitValue(), inputFile.getFileName());
                return false;
            }
            return Files.exists(outputFile) && Files.size(outputFile) > 0;
        } catch (IOException | InterruptedException e) {
            log.error("ffmpeg PCM conversion error: {}", e.getMessage());
            return false;
        }
    }

    /**
     * Convert raw audio bytes to 16kHz mono WAV for speech recognition preprocessing.
     * Handles format conversion, resampling, and channel reduction.
     *
     * @param inputBytes  raw audio data
     * @param inputFormat input format hint (e.g. "wav", "mp3", "opus"); null = ffmpeg auto-detect
     * @param outputFile  destination WAV file
     * @return true if conversion succeeded
     */
    boolean convertToWav(byte[] inputBytes, String inputFormat, Path outputFile) {
        Path tempInput = null;
        try {
            // Write input bytes to a temp file for ffmpeg
            String suffix = inputFormat != null && !inputFormat.isBlank() ? "." + inputFormat : ".bin";
            tempInput = Files.createTempFile("stt_in_", suffix);
            Files.write(tempInput, inputBytes);

            ProcessBuilder pb = new ProcessBuilder(
                    ffmpegPath, "-y",
                    "-i", tempInput.toAbsolutePath().toString(),
                    "-ar", "16000",
                    "-ac", "1",
                    "-sample_fmt", "s16",
                    outputFile.toAbsolutePath().toString())
                    .redirectErrorStream(true);
            Process p = pb.start();
            if (!p.waitFor(15, TimeUnit.SECONDS)) {
                p.destroyForcibly();
                log.warn("ffmpeg WAV conversion timed out");
                return false;
            }
            if (p.exitValue() != 0) {
                log.warn("ffmpeg WAV conversion failed with exit code {}", p.exitValue());
                return false;
            }
            return Files.exists(outputFile) && Files.size(outputFile) > 0;
        } catch (IOException | InterruptedException e) {
            log.error("ffmpeg WAV conversion error: {}", e.getMessage());
            return false;
        } finally {
            if (tempInput != null) {
                try { Files.deleteIfExists(tempInput); } catch (Exception ignored) {}
            }
        }
    }

    /**
     * Check whether ffmpeg is available on this system.
     */
    boolean isFfmpegAvailable() {
        try {
            Process p = new ProcessBuilder(ffmpegPath, "-version")
                    .redirectErrorStream(true)
                    .start();
            if (p.waitFor(5, TimeUnit.SECONDS) && p.exitValue() == 0) {
                return true;
            }
        } catch (Exception e) {
            log.debug("ffmpeg not found at '{}': {}", ffmpegPath, e.getMessage());
        }
        return false;
    }
}
