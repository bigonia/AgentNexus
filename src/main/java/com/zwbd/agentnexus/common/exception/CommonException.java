package com.zwbd.agentnexus.common.exception;

import lombok.Data;

/**
 * @Author: wnli
 * @Date: 2025/9/11 10:59
 * @Desc:
 */
@Data
public class CommonException extends RuntimeException{

    public CommonException(String message) {
        super(message);
    }

    public CommonException(String message, Throwable cause) {
        super(message, cause);
    }

    public CommonException(Throwable cause) {
        super(cause);
    }

    public CommonException(String message, Throwable cause, boolean enableSuppression, boolean writableStackTrace) {
        super(message, cause, enableSuppression, writableStackTrace);
    }
}
