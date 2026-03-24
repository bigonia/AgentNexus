package com.zwbd.agentnexus.common.web;

import lombok.Data;

/**
 * @Author: wnli
 * @Date: 2025/11/13 12:00
 * @Desc:
 */
@Data
public class ApiResponse<T> {

    private int code;
    private String message;
    private T data;

    /**
     * 成功的响应（带数据）
     */
    public static <T> ApiResponse<T> success(T data) {
        ApiResponse<T> response = new ApiResponse<>();
        response.setCode(20000); // 2000 代表成功
        response.setMessage("Success");
        response.setData(data);
        return response;
    }

    public static <T> ApiResponse<T> ok(T data) {
        ApiResponse<T> response = new ApiResponse<>();
        response.setCode(20000); // 2000 代表成功
        response.setMessage("Success");
        response.setData(data);
        return response;
    }

    /**
     * 成功的响应（不带数据，例如删除操作）
     */
    public static <Void> ApiResponse<Void> success() {
        ApiResponse<Void> response = new ApiResponse<>();
        response.setCode(20000);
        response.setMessage("Success");
        return response;
    }

    /**
     * 错误的响应（使用自定义错误码和消息）
     */
    public static <T> ApiResponse<T> error(int code, String message) {
        ApiResponse<T> response = new ApiResponse<>();
        response.setCode(code);
        response.setMessage(message);
        return response;
    }

    public static <T> ApiResponse<T> error(String message) {
        ApiResponse<T> response = new ApiResponse<>();
        response.setCode(50000);
        response.setMessage(message);
        return response;
    }

}
