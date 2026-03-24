package com.zwbd.agentnexus.document.dto;

import lombok.Data;

/**
 * @Author: wnli
 * @Date: 2025/12/3 11:13
 * @Desc:
 */
@Data
public class GenerateScriptRequest {

    private Long docId;          // 可选：用于后端自动采样
    private String sampleData;   // 可选：前端手动选择的采样内容
    private String requirement;  // 必选：用户的清洗目标 (如 "去除手机号")

}
