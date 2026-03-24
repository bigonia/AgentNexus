package com.zwbd.agentnexus.common.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * @Author: wnli
 * @Date: 2025/9/16 9:11
 * @Desc:
 */
@Configuration
public class CommonConfig {

    public static ObjectMapper objectMapper;

    @Autowired
    public void setObjectMapper(ObjectMapper objectMapper) {
        CommonConfig.objectMapper = objectMapper;
    }

    @Bean
    ExecutorService init(){
        return Executors.newFixedThreadPool(10);
    }

    

}
