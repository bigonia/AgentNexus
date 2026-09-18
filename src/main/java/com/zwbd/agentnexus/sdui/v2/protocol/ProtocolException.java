package com.zwbd.agentnexus.sdui.v2.protocol;

/**
 * LCD_085 v2 协议异常。
 *
 * <p>承载一个稳定的协议错误名（见 {@link ProtocolErrors}）。错误名会被写入
 * {@code Result.error} 并透传给终端与平台调用方，因此必须是稳定常量而不是自由文本。</p>
 */
public class ProtocolException extends RuntimeException {

    private final String code;

    public ProtocolException(String code) {
        super(code);
        this.code = code;
    }

    public ProtocolException(String code, String message) {
        super(message);
        this.code = code;
    }

    public ProtocolException(String code, String message, Throwable cause) {
        super(message, cause);
        this.code = code;
    }

    public String code() {
        return code;
    }
}
