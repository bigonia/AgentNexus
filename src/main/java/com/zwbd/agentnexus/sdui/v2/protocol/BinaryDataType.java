package com.zwbd.agentnexus.sdui.v2.protocol;

/**
 * v2 二进制数据类别。
 *
 * <p>04_PROTOCOL_MODEL.md §8.1 要求 Binary Frame 携带"最小数据类型标识"，但未定义取值。
 * 这里给出平台侧首期取值，终端升级后需要对齐（缺口 G3）。</p>
 *
 * <p>首期通过"同类数据不并发"约束时序，因此不需要 stream_id / transfer_id。</p>
 */
public enum BinaryDataType {

    /** 音频样本，双向。 */
    AUDIO(1),

    /** 全屏图片数据（调色板索引矩阵）。 */
    IMAGE(2),

    /** Matrix Canvas 单帧。 */
    CANVAS(3),

    /** 能力 Schema（终端 → 平台）。 */
    CAPABILITY_SCHEMA(4),

    /** 其他资源（字体等）。 */
    RESOURCE(5);

    private final int code;

    BinaryDataType(int code) {
        this.code = code;
    }

    public int code() {
        return code;
    }

    public static BinaryDataType fromCode(int code) {
        for (BinaryDataType type : values()) {
            if (type.code == code) {
                return type;
            }
        }
        throw new ProtocolException(ProtocolErrors.INVALID_ENVELOPE, "unknown binary data type: " + code);
    }
}
