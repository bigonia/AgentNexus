package com.zwbd.agentnexus.sdui.v2.transport.sink;

import com.zwbd.agentnexus.sdui.v2.protocol.BinaryDataType;
import com.zwbd.agentnexus.sdui.v2.transport.V2Contexts;
import com.zwbd.agentnexus.sdui.v2.transport.V2SessionBootstrapService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 能力 Schema 上传接收器。
 *
 * <p>04_PROTOCOL_MODEL.md §4：完整 Schema 属于"有界对象"，与音频、Canvas 等连续数据复用底层
 * 二进制帧封装但生命周期独立。终端不需要知道平台缓存状态，由平台决定是否请求完整内容。</p>
 */
@Slf4j
@Component
public class CapabilitySchemaBinarySink implements V2Contexts.BinarySink {

    private final V2SessionBootstrapService bootstrapService;

    public CapabilitySchemaBinarySink(V2SessionBootstrapService bootstrapService) {
        this.bootstrapService = bootstrapService;
    }

    @Override
    public BinaryDataType dataType() {
        return BinaryDataType.CAPABILITY_SCHEMA;
    }

    @Override
    public boolean onData(V2Contexts.BinaryContext context) {
        if (context.payload() == null || context.payload().length == 0) {
            log.warn("收到空的 Schema 上传: device={}", context.deviceId());
            return false;
        }
        bootstrapService.onSchemaUpload(context.deviceId(), context.payload());
        return true;
    }
}
