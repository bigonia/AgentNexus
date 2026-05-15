package com.zwbd.agentnexus.sdui.service;

/**
 * Pluggable TTS backend. Implementations can be local (edge TTS engine)
 * or remote (cloud TTS API). The returned PCM is 16-bit signed little-endian
 * mono at 22050 Hz, matching the terminal's audio/play pipeline.
 */
@FunctionalInterface
public interface TtsProvider {

    /**
     * @param text plain text to synthesize
     * @return PCM 16-bit signed LE mono 22050 Hz, or null / zero-length if unavailable
     */
    byte[] synthesize(String text);
}
