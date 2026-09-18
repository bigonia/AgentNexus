package com.zwbd.agentnexus.sdui.v2.transport.sink;

/**
 * 上行音频数据的下游消费点。
 *
 * <p>v2 音频通道只负责协议与生命周期（{@code audio.start} → Binary → {@code audio.stop/abort}），
 * 不承担 PCM 的业务用途。旧实现把 PCM 直接送进 {@code AudioRecordSessionManager} 并串接
 * 转码与 STT，v2 这里留出显式接缝，避免协议层与业务处理再次耦合在同一个类里。</p>
 *
 * <p><b>TODO(lcd085-refactor)</b>：终端采用 v2 音频后，需要提供一个把
 * {@code AudioRecordSessionManager} / {@code AudioConversionService} / STT 链接起来的实现，
 * 替换当前的空实现。见 {@code 11_FEATURE_MATRIX.md} 4.8~4.11。</p>
 */
public interface AudioUplinkConsumer {

    /** 收到一段上行 PCM。 */
    void onAudioChunk(String deviceId, byte[] pcm);

    /**
     * 上行音频结束。
     *
     * @param reason 正常结束为 {@code stopped}，异常终止为 {@code buffer_full} 等
     */
    void onStreamEnded(String deviceId, String reason);
}
