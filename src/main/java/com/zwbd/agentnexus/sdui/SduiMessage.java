package com.zwbd.agentnexus.sdui;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.Data;

/**
 * @Author: wnli
 * @Date: 2026/3/12 15:53
 * @Desc:
 */
@Data
public class SduiMessage {

    @JsonProperty("topic")
    private String topic;

    @JsonProperty("device_id")
    private String deviceId;

    // 使用 JsonNode 以兼容各种动态的 payload 结构 (对象、数组、甚至是基本类型)
    @JsonProperty("payload")
    private JsonNode payload;

}
