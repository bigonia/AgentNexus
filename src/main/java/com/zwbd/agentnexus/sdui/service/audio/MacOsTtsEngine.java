package com.zwbd.agentnexus.sdui.service.audio;

import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

/**
 * macOS TTS engine using the built-in {@code say} command.
 * Generates AIFF audio from text, which is then converted to PCM by ffmpeg.
 *
 * <p>The {@code say} command supports many voices including Chinese (Ting-Ting, Sin-ji),
 * English, Japanese, Korean, and more. The default system voice is used unless overridden.</p>
 */
@Slf4j
class MacOsTtsEngine {

    private static final String SAY_CMD = "/usr/bin/say";

    /**
     * Generate speech as an AIFF audio file using the macOS {@code say} command.
     *
     * @param textFile a text file containing the text to speak (UTF-8)
     * @param aiffFile destination AIFF file path (should end with {@code .aiff} or {@code .aif};
     *                otherwise macOS {@code say -o} will auto-append {@code .aiff} and the
     *                resulting file won't match this path — breaking downstream consumers)
     * @return true if speech was generated and the output file is non-empty
     */
    boolean generateSpeech(Path textFile, Path aiffFile) {
        try {
            Process p = new ProcessBuilder(
                    SAY_CMD,
                    "-o", aiffFile.toAbsolutePath().toString(),
                    "-f", textFile.toAbsolutePath().toString())
                    .redirectErrorStream(true)
                    .start();

            if (!p.waitFor(30, TimeUnit.SECONDS)) {
                p.destroyForcibly();
                log.warn("macOS say command timed out");
                return false;
            }
            if (p.exitValue() != 0) {
                log.warn("macOS say command failed with exit code {}", p.exitValue());
                return false;
            }
            return Files.exists(aiffFile) && Files.size(aiffFile) > 0;
        } catch (IOException | InterruptedException e) {
            log.error("macOS say command error: {}", e.getMessage());
            return false;
        }
    }

    /**
     * Check whether the macOS {@code say} command is available.
     */
    boolean isAvailable() {
        try {
            Process p = new ProcessBuilder(SAY_CMD, "-v", "?")
                    .redirectErrorStream(true)
                    .start();
            return p.waitFor(5, TimeUnit.SECONDS) && p.exitValue() == 0;
        } catch (Exception e) {
            log.debug("macOS say not available: {}", e.getMessage());
            return false;
        }
    }
}
