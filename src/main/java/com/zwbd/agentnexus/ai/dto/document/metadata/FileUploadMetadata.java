package com.zwbd.agentnexus.ai.dto.document.metadata;

import com.fasterxml.jackson.annotation.JsonIgnore;
import lombok.Data;

/**
 * @Author: wnli
 * @Date: 2025/10/21 10:33
 * @Desc:
 */
@Data
public class FileUploadMetadata extends BaseMetadata {

    @JsonIgnore
    private String filePath;

}
