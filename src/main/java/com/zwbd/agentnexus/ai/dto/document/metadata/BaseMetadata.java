package com.zwbd.agentnexus.ai.dto.document.metadata;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zwbd.agentnexus.common.web.GlobalContext;
import com.zwbd.agentnexus.document.entity.DocMode;
import lombok.Data;

import java.util.Map;

/**
 * @Author: wnli
 * @Date: 2025/10/21 10:31
 * @Desc: 为了兼容不同业务定义抽象类，除了基础属性外还包括每个业务的独特属性，作为最终document的metadata存储
 */
@Data
public abstract class BaseMetadata {

    private String sourceId;

    private String sourceName;

    private DocumentType documentType;

    @JsonIgnore
    private DocMode docMode;

//    @JsonIgnore
    private String sourceSystem;

    @JsonIgnore
    private static final ObjectMapper mapper = new ObjectMapper();

    public Map<String, Object> toMap() {
        // 临时创建忽略Null值的ObjectMapper（避免影响全局配置）
        ObjectMapper nonNullMapper = mapper.copy()
                .setSerializationInclusion(JsonInclude.Include.NON_NULL);

        Map<String, Object> map = nonNullMapper.convertValue(this, new TypeReference<>() {});
        // 增加业务空间标识
        map.put(GlobalContext.KEY_SPACE_ID, GlobalContext.getSpaceId());
        return map;
    }

}
