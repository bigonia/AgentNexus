package com.zwbd.agentnexus.sdui.v2.audio;

import java.util.Optional;

/**
 * 从 WAV 容器中取出 PCM 载荷。
 *
 * <p>v2 的下行音频接口（04_PROTOCOL_MODEL.md §7）传输的是裸 PCM 帧，而平台侧的音频产物
 * （录音 artifact、TTS 落盘结果）通常是 WAV 容器。这里只做容器拆解，不做任何重采样或格式转换。</p>
 *
 * <p>不做重采样是刻意的：终端在能力 Schema 的 {@code audio} 中声明自己接受的采样率，而平台侧 TTS
 * 输出与 artifact 可能是别的采样率。静默转换会掩盖这个不一致，让"声音不对"变成只能靠耳朵发现的
 * 问题。差异由调用方记录并上报，见 12_DESIGN_NOTES.md 的待确认项。</p>
 */
public final class WavPcm {

    private WavPcm() {}

    /**
     * 拆解结果。
     *
     * @param pcm           PCM 载荷
     * @param sampleRate    采样率；无法识别容器时为 0
     * @param channels      声道数；无法识别容器时为 0
     * @param bitsPerSample 位深；无法识别容器时为 0
     * @param wav           是否为可识别的 RIFF/WAVE 容器
     */
    public record Payload(byte[] pcm, int sampleRate, int channels, int bitsPerSample, boolean wav) {

        public boolean isEmpty() {
            return pcm == null || pcm.length == 0;
        }

        public int durationMs() {
            if (isEmpty() || sampleRate <= 0 || channels <= 0 || bitsPerSample <= 0) {
                return 0;
            }
            int frameBytes = channels * bitsPerSample / 8;
            return (int) ((long) pcm.length * 1000 / ((long) sampleRate * frameBytes));
        }
    }

    /**
     * 取出 PCM 载荷。
     *
     * <p>无法识别为 RIFF/WAVE 时按裸 PCM 处理并原样返回，{@code wav=false}——这样"平台存了一段裸
     * PCM"不会变成静默失败，同时调用方能从 {@code wav} 标志判断是否需要提醒格式待确认。</p>
     */
    public static Payload extract(byte[] bytes) {
        if (bytes == null || bytes.length < 12) {
            return new Payload(bytes == null ? new byte[0] : bytes, 0, 0, 0, false);
        }
        if (!isRiffWave(bytes)) {
            return new Payload(bytes, 0, 0, 0, false);
        }

        int sampleRate = 0;
        int channels = 0;
        int bitsPerSample = 0;
        byte[] pcm = null;

        int offset = 12;
        while (offset + 8 <= bytes.length) {
            String chunkId = new String(bytes, offset, 4, java.nio.charset.StandardCharsets.US_ASCII);
            int chunkSize = readIntLE(bytes, offset + 4);
            int body = offset + 8;
            if (chunkSize < 0 || body + chunkSize > bytes.length) {
                // 长度字段不可信，停止解析已取到的部分
                break;
            }
            if ("fmt ".equals(chunkId) && chunkSize >= 16) {
                channels = readShortLE(bytes, body + 2);
                sampleRate = readIntLE(bytes, body + 4);
                bitsPerSample = readShortLE(bytes, body + 14);
            } else if ("data".equals(chunkId)) {
                pcm = new byte[chunkSize];
                System.arraycopy(bytes, body, pcm, 0, chunkSize);
            }
            // chunk 按偶数字节对齐
            offset = body + chunkSize + (chunkSize % 2);
        }

        if (pcm == null) {
            return new Payload(new byte[0], sampleRate, channels, bitsPerSample, true);
        }
        return new Payload(pcm, sampleRate, channels, bitsPerSample, true);
    }

    /** 便捷入口：只关心载荷时使用。 */
    public static Optional<byte[]> extractPcm(byte[] bytes) {
        Payload payload = extract(bytes);
        return payload.isEmpty() ? Optional.empty() : Optional.of(payload.pcm());
    }

    private static boolean isRiffWave(byte[] bytes) {
        return bytes[0] == 'R' && bytes[1] == 'I' && bytes[2] == 'F' && bytes[3] == 'F'
                && bytes[8] == 'W' && bytes[9] == 'A' && bytes[10] == 'V' && bytes[11] == 'E';
    }

    private static int readIntLE(byte[] bytes, int offset) {
        if (offset + 4 > bytes.length) {
            return -1;
        }
        return (bytes[offset] & 0xFF)
                | ((bytes[offset + 1] & 0xFF) << 8)
                | ((bytes[offset + 2] & 0xFF) << 16)
                | ((bytes[offset + 3] & 0xFF) << 24);
    }

    private static int readShortLE(byte[] bytes, int offset) {
        if (offset + 2 > bytes.length) {
            return 0;
        }
        return (bytes[offset] & 0xFF) | ((bytes[offset + 1] & 0xFF) << 8);
    }
}
