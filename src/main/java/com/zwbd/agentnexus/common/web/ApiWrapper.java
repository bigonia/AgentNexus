package com.zwbd.agentnexus.common.web;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * @Author: wnli
 * @Date: 2025/11/13 11:59
 * @Desc:
 * 自定义注解，用于标记 Controller 方法
 * 标有此注解的方法，其返回值将不会被 GlobalResponseAdvice 自动包装
 */
@Target({ElementType.METHOD,ElementType.TYPE}) // 注解作用于方法上
@Retention(RetentionPolicy.RUNTIME) // 运行时保留
public @interface ApiWrapper {
}
