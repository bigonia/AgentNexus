package com.zwbd.agentnexus.security;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * @Author: wnli
 * @Date: 2025/10/15 15:16
 * @Desc:
 */
@Configuration
public class SpringDocConfig {

    @Bean
    public OpenAPI customOpenAPI() {
        final String securitySchemeName = "bearerAuth";
        return new OpenAPI()
                // 1. 定义认证方案
                .components(
                        new Components()
                                .addSecuritySchemes(securitySchemeName,
                                        new SecurityScheme()
                                                .name(securitySchemeName)
                                                .type(SecurityScheme.Type.HTTP) // 类型为HTTP
                                                .scheme("bearer") // 具体的scheme为bearer
                                                .bearerFormat("JWT") // 格式为JWT
                                )
                )
                // 2. 将认证方案应用到所有API
                .addSecurityItem(new SecurityRequirement().addList(securitySchemeName))
                // (可选) 添加一些基本的API信息
                .info(new Info().title("AI base").version("v1.0"));
    }
}
