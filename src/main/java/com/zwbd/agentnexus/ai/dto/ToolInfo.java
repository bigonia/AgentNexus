package com.zwbd.agentnexus.ai.dto;

import lombok.AllArgsConstructor;
import lombok.Data;

/**
 * @Author: wnli
 * @Date: 2026/1/6 17:04
 * @Desc:
 */
@AllArgsConstructor
@Data
public class ToolInfo {

    private String name;
    private String description;
    private String inputSchema;
//    private boolean enable;

}
