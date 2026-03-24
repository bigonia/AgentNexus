package com.zwbd.agentnexus.ai.tools;

import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.i18n.LocaleContextHolder;

import java.time.LocalDateTime;

/**
 * @Author: wnli
 * @Date: 2025/10/23 11:48
 * @Desc:
 */
@Slf4j
@Configuration
public class CommonTools {

    @Tool(description = "Get the current date and time in the user's timezone")
    public String getCurrentDateTime() {
        log.info("getCurrentDateTime");
        return LocalDateTime.now().atZone(LocaleContextHolder.getTimeZone().toZoneId()).toString();
    }

    @Tool(description = "Get the weather of input city")
    public String queryWeather(@ToolParam(description = "city name") String city) {
        log.info("queryWeather");
        return city + ":晴天";
    }

}
