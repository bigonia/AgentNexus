package com.zwbd.agentnexus.sdui.v2;

/**
 * v2 内部操作的二元结果。
 *
 * <p>用于没有平台请求上下文（因此没有 {@code id}）的内部动作，例如本地音频状态切换、
 * 显示主视图切换。{@code error} 取值来自 {@code ProtocolErrors}。</p>
 */
public record OperationResult(boolean ok, String error) {

    private static final OperationResult SUCCESS = new OperationResult(true, null);

    public static OperationResult success() {
        return SUCCESS;
    }

    public static OperationResult failure(String error) {
        return new OperationResult(false, error);
    }
}
