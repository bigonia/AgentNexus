package com.zwbd.agentnexus.common.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.info.License;
import io.swagger.v3.oas.models.parameters.Parameter;
import io.swagger.v3.oas.models.servers.Server;
import org.springdoc.core.customizers.OperationCustomizer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

/**
 * @Author: wnli
 * @Date: 2025/9/16 12:15
 * @Desc: Swagger configuration for API documentation
 * 
 * This configuration sets up OpenAPI 3.0 documentation for the RAG framework,
 * providing interactive API documentation and testing capabilities.
 */
@Configuration
public class SwaggerConfig {

    @Value("${server.port:8080}")
    private String serverPort;

    @Value("${server.servlet.context-path:}")
    private String contextPath;

    /**
     * Configure OpenAPI documentation
     */
//    @Bean
    public OpenAPI customOpenAPI() {
        return new OpenAPI()
                .info(new Info()
                        .title("Universal RAG Framework API")
                        .description("A comprehensive RAG (Retrieval-Augmented Generation) framework providing " +
                                "intelligent question-answering capabilities with document ingestion and vector search.")
                        .version("1.0.0")
                        .contact(new Contact()
                                .name("Development Team")
                                .email("dev@zwbd.com")
                                .url("https://github.com/zwbd/universal-rag-framework"))
                        .license(new License()
                                .name("MIT License")
                                .url("https://opensource.org/licenses/MIT")))
                .servers(List.of(
                        new Server()
                                .url("http://localhost:" + serverPort + contextPath)
                                .description("Local Development Server"),
                        new Server()
                                .url("https://api.example.com" + contextPath)
                                .description("Production Server")
                ));
    }

    /**
     * 核心配置：全局 Operation 定制器
     * 作用：自动为所有 API 接口在 Swagger UI 上添加 X-Space-Id 和 X-User-Id 的输入框
     * 原理：扫描所有 Controller 方法，向 OpenAPI 文档注入 Header 参数定义
     */
    @Bean
    public OperationCustomizer globalHeaderCustomizer() {
        return (operation, handlerMethod) -> {
            // 添加 X-Space-Id 输入框
            operation.addParametersItem(new Parameter()
                    .in("header") // 指定参数位置为 HTTP Header
                    .name("X-Space-Id")
                    .description("【隔离参数】业务空间 ID (不填默认为 'default')")
                    .required(false) // 设置为 false，因为拦截器有兜底逻辑
                    .schema(new io.swagger.v3.oas.models.media.StringSchema()._default("default")));

            // 添加 X-User-Id 输入框
//            operation.addParametersItem(new Parameter()
//                    .in("header")
//                    .name("X-User-Id")
//                    .description("【审计参数】当前操作用户 ID")
//                    .required(false)
//                    .schema(new io.swagger.v3.oas.models.media.StringSchema()));
//
//            // 添加 X-Trace-Id 输入框
//            operation.addParametersItem(new Parameter()
//                    .in("header")
//                    .name("X-Trace-Id")
//                    .description("【链路追踪】Trace ID")
//                    .required(false)
//                    .schema(new io.swagger.v3.oas.models.media.StringSchema()));

            return operation;
        };
    }

}