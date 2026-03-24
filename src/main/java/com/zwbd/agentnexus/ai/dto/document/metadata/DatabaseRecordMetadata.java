package com.zwbd.agentnexus.ai.dto.document.metadata;

import lombok.Data;

/**
 * @Author: wnli
 * @Date: 2025/10/21 10:50
 * @Desc:
 */
@Data
public class DatabaseRecordMetadata extends BaseMetadata {

    String schema;

    String table;

    String template;

}
