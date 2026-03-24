package com.zwbd.agentnexus.common.web;

import com.zwbd.agentnexus.common.exception.CommonException;
import org.springframework.boot.autoconfigure.web.servlet.error.BasicErrorController;
import org.springframework.core.MethodParameter;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyAdvice;

/**
 * @Author: wnli
 * @Date: 2025/11/13 11:59
 * @Desc:
 */
//@RestControllerAdvice(annotations = ApiWrapper.class)
public class GlobalResponseAdvice implements ResponseBodyAdvice<Object> {

    @Override
    public boolean supports(MethodParameter returnType, Class converterType) {

        // 检查是否是 BasicErrorController，如果是，不包装
        //    这样 Spring Security 转发过来的 /error 响应就能保持原样
        if (returnType.getDeclaringClass().equals(BasicErrorController.class)) {
            return false;
        }

        // 1. 检查方法上是否有 @NoApiWrapper 注解
//        if (returnType.hasMethodAnnotation(ApiWrapper.class)) {
//            return false; // 如果有，则“不支持”包装，直接放行
//        }
//
//        // 2. 检查类上是否有 @NoApiWrapper 注解 (可选，增加灵活性)
//        if (returnType.getDeclaringClass().isAnnotationPresent(ApiWrapper.class)) {
//            return false;
//        }

        // 3. 检查是否已经是 ApiResponse 类型，防止重复包装
        if (returnType.getParameterType().isAssignableFrom(ApiResponse.class)) {
            return false;
        }

        // 4. 检查是否是 Springdoc/Swagger 的接口
        String packageName = returnType.getDeclaringClass().getPackage().getName();
        if (packageName.contains("springfox") || packageName.contains("swagger") || packageName.contains("org.springframework.boot.actuate")) {
            return false;
        }

        // 其他情况都需要包装
        return true;
    }

    @Override
    public Object beforeBodyWrite(Object body, MethodParameter returnType, MediaType selectedContentType, Class<? extends HttpMessageConverter<?>> selectedConverterType, ServerHttpRequest request, ServerHttpResponse response) {
        if (body == null) {
            return ApiResponse.success();
        }
        if (body instanceof ApiResponse) {
            return body;
        }
        return ApiResponse.success(body);
    }

    /**
     * 处理您 @Valid 注解触发的参数校验异常
     */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST) // HTTP 状态码设为 400
    public ApiResponse<Object> handleValidationExceptions(MethodArgumentNotValidException ex) {
        // 从异常中获取第一条校验错误信息
        String message = ex.getBindingResult().getAllErrors().get(0).getDefaultMessage();
        return ApiResponse.error(4000, message); // 4000 是自定义的业务错误码
    }

    /**
     * 处理自定义的业务异常
     */
    @ExceptionHandler(CommonException.class) // 假设您有一个 BusinessException
    @ResponseStatus(HttpStatus.OK) // 业务异常，HTTP 状态码通常还是 200
    public ApiResponse<Object> handleBusinessException(CommonException ex) {
        return ApiResponse.error(40000, ex.getMessage());
    }

    /**
     * 处理所有未被捕获的其它异常（兜底）
     */
    @ExceptionHandler(Exception.class)
    @ResponseStatus(HttpStatus.INTERNAL_SERVER_ERROR) // HTTP 状态码设为 500
    public ApiResponse<Object> handleAllExceptions(Exception ex) {
        // 在生产环境中，不应暴露详细的 ex.getMessage()
        return ApiResponse.error(5000, "服务器内部错误，请联系管理员");
    }
}
