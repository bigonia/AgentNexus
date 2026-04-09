package com.zwbd.agentnexus.sdui;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.Data;

@Data
public class SduiMessage {

    @JsonProperty("topic")
    private String topic;

    @JsonProperty("device_id")
    private String deviceId;

    @JsonProperty("space_id")
    private String spaceId;

    @JsonProperty("payload")
    private JsonNode payload;
}
