package com.zwbd.agentnexus.sdui.service.audio;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.concurrent.TimeUnit;

/**
 * Local STT engine using ffmpeg for audio preprocessing and Whisper CLI for
 * speech recognition.
 *
 * <h3>Pipeline</h3>
 * <pre>
 *   raw audio bytes → ffmpeg (→ 16kHz mono WAV) → whisper CLI → text
 * </pre>
 *
 * <h3>Prerequisites</h3>
 * <ul>
 *   <li>ffmpeg — for audio format conversion and resampling</li>
 *   <li>whisper CLI — OpenAI Whisper or whisper.cpp command-line tool</li>
 *   <li>whisper model — downloaded to a known path (e.g. ~/.whisper/models/)</li>
 * </ul>
 *
 * <p>If whisper is not installed, the provider gracefully degrades:
 * {@link #isAvailable()} returns false and {@link #transcribe(byte[], String)}
 * returns null.</p>
 *
 * <h3>Alternative backends</h3>
 * To use a different recognition engine (OpenAI API, Google Cloud Speech, Vosk),
 * provide a different {@link SttProvider} bean. This implementation is
 * {@code @Autowired(required = false)} so it won't block startup.</p>
 */
@Slf4j
@Component
public class FfmpegSttProvider implements SttProvider {

    private final AudioConversionService conversionService;
    private final String whisperPath;
    private final String whisperModel;

    private volatile boolean checked;
    private volatile boolean available;

    public FfmpegSttProvider(
            @Value("${sdui.tts.ffmpeg-path:ffmpeg}") String ffmpegPath,
            @Value("${sdui.stt.whisper-path:whisper}") String whisperPath,
            @Value("${sdui.stt.whisper-model:base}") String whisperModel) {
        this.conversionService = new AudioConversionService(ffmpegPath);
        this.whisperPath = whisperPath;
        this.whisperModel = whisperModel;
    }

    @Override
    public String transcribe(byte[] audioBytes, String inputFormat) {
        if (audioBytes == null || audioBytes.length == 0) {
            log.warn("STT: empty audio data");
            return null;
        }
        if (!isAvailable()) return null;

        Path tempDir = null;
        try {
            tempDir = Files.createTempDirectory("stt_");
            Path wavFile = tempDir.resolve("input.wav");
            Path txtFile = tempDir.resolve("output.txt");

            // Step 1: ffmpeg preprocessing → 16kHz mono WAV
            if (!conversionService.convertToWav(audioBytes, inputFormat, wavFile)) {
                log.warn("STT: ffmpeg preprocessing failed");
                return null;
            }

            // Step 2: Whisper recognition
            if (!runWhisper(wavFile, tempDir)) {
                log.warn("STT: whisper recognition failed");
                return null;
            }

            // Step 3: Read result (whisper outputs <input>.txt)
            if (Files.exists(txtFile) && Files.size(txtFile) > 0) {
                String text = Files.readString(txtFile, StandardCharsets.UTF_8).trim();
                log.info("STT transcribed: {} chars", text.length());
                return text;
            }

            // Try alternate output filename patterns
            Path altTxt = tempDir.resolve("input.txt");
            if (Files.exists(altTxt) && Files.size(altTxt) > 0) {
                String text = Files.readString(altTxt, StandardCharsets.UTF_8).trim();
                log.info("STT transcribed (alt path): {} chars", text.length());
                return text;
            }

            log.warn("STT: no transcription output found");
            return null;
        } catch (Exception e) {
            log.error("STT transcription failed: {}", e.getMessage());
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
     * Run whisper CLI for speech recognition.
     * Supports both whisper.cpp and OpenAI whisper CLI formats.
     */
    private boolean runWhisper(Path wavFile, Path outputDir) throws IOException, InterruptedException {
        // Try whisper.cpp style: whisper -m <model> -f <wav> -otxt -of <output_dir>/output
        ProcessBuilder pb = new ProcessBuilder(
                whisperPath,
                "-m", whisperModel,
                "-f", wavFile.toAbsolutePath().toString(),
                "-otxt",
                "-of", outputDir.resolve("output").toAbsolutePath().toString())
                .redirectErrorStream(true);
        Process p = pb.start();
        if (!p.waitFor(60, TimeUnit.SECONDS)) {
            p.destroyForcibly();
            log.warn("Whisper recognition timed out (60s)");
            return false;
        }
        if (p.exitValue() != 0) {
            // Try OpenAI whisper CLI style: whisper <wav> --model <model> --output_dir <dir>
            return runWhisperOpenAi(wavFile, outputDir);
        }
        // whisper.cpp outputs output.txt
        return true;
    }

    private boolean runWhisperOpenAi(Path wavFile, Path outputDir) throws IOException, InterruptedException {
        ProcessBuilder pb = new ProcessBuilder(
                whisperPath,
                wavFile.toAbsolutePath().toString(),
                "--model", whisperModel,
                "--output_dir", outputDir.toAbsolutePath().toString(),
                "--output_format", "txt")
                .redirectErrorStream(true);
        Process p = pb.start();
        if (!p.waitFor(60, TimeUnit.SECONDS)) {
            p.destroyForcibly();
            log.warn("Whisper (OpenAI CLI) recognition timed out (60s)");
            return false;
        }
        if (p.exitValue() != 0) {
            log.warn("Whisper (OpenAI CLI) failed with exit code {}", p.exitValue());
            return false;
        }
        return true;
    }

    /**
     * Check whether both ffmpeg and whisper are available.
     */
    public boolean isAvailable() {
        if (checked) return available;
        checked = true;

        if (!conversionService.isFfmpegAvailable()) {
            log.info("ffmpeg not available — STT disabled");
            return false;
        }

        try {
            Process p = new ProcessBuilder(whisperPath, "--help")
                    .redirectErrorStream(true)
                    .start();
            if (p.waitFor(5, TimeUnit.SECONDS) && p.exitValue() == 0) {
                available = true;
                log.info("Whisper STT provider ready: {} (model={})", whisperPath, whisperModel);
            } else {
                // Try whisper -h (shorthand)
                Process p2 = new ProcessBuilder(whisperPath, "-h")
                        .redirectErrorStream(true)
                        .start();
                if (p2.waitFor(5, TimeUnit.SECONDS) && p2.exitValue() == 0) {
                    available = true;
                    log.info("Whisper STT provider ready: {} (model={})", whisperPath, whisperModel);
                } else {
                    log.info("Whisper not available at '{}' — STT disabled. "
                            + "Install via: brew install whisper-cpp", whisperPath);
                }
            }
        } catch (Exception e) {
            log.info("Whisper not available at '{}': {} — STT disabled", whisperPath, e.getMessage());
        }
        return available;
    }
}
