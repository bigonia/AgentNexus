package com.zwbd.agentnexus.sdui.service.audio;

/**
 * Pluggable STT (Speech-to-Text) backend. Implementations can be local
 * (whisper.cpp, Vosk) or cloud (OpenAI Whisper API, Google Speech-to-Text).
 *
 * <p>The input audio is raw bytes in any format the implementation supports.
 * Implementations should handle format conversion internally (typically via ffmpeg).</p>
 */
@FunctionalInterface
public interface SttProvider {

    /**
     * Transcribe audio bytes to text.
     *
     * @param audioBytes  raw audio data
     * @param inputFormat format hint (e.g. "wav", "mp3", "opus", "pcm");
     *                    may be null for auto-detection
     * @return transcribed text, or null / empty string if unavailable
     */
    String transcribe(byte[] audioBytes, String inputFormat);
}
