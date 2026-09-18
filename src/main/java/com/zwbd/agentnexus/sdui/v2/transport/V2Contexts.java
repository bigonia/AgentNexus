package com.zwbd.agentnexus.sdui.v2.transport;

import com.fasterxml.jackson.databind.JsonNode;
import com.zwbd.agentnexus.sdui.v2.OperationResult;
import com.zwbd.agentnexus.sdui.v2.protocol.BinaryDataType;

import java.util.List;

/**
 * v2 接入层的三个扩展点。
 *
 * <p>路由不再使用旧的 topic → handler 映射，而是按**消息名**分发：平台请求按 {@code name} 找到
 * 一个 handler，终端事件按 {@code name} 找到 sink，二进制数据按 {@code dataType} 找到 sink。
 * 这样避免旧实现中"topic 即协议版本"的隐含耦合。</p>
 */
public final class V2Contexts {

    private V2Contexts() {}

    /** 终端主动发起的请求上下文。 */
    public record RequestContext(String deviceId, long generation, String requestId, JsonNode body) {}

    /** 终端主动上报的事件上下文。{@code name} 是实际收到的消息名，供多名称接收器区分语义。 */
    public record EventContext(String deviceId, long generation, String name, JsonNode body) {}

    /** 终端上传的二进制数据上下文。 */
    public record BinaryContext(String deviceId, long generation, byte[] payload) {}

    /** 平台请求处理器：终端 → 平台方向且需要平台返回结果的消息。 */
    public interface RequestHandler {

        String name();

        /** 返回结果会被编码为 {@code {id, ok, error?}} 回送给终端。 */
        OperationResult handle(RequestContext context);
    }

    /** 事件接收器：终端 → 平台方向且平台不回复的消息。 */
    public interface EventSink {

        List<String> names();

        void onEvent(EventContext context);
    }

    /** 二进制数据接收器：按数据类型注册，同一类型同一时刻最多一个活动生命周期。 */
    public interface BinarySink {

        BinaryDataType dataType();

        /** @return 是否消费了该数据；返回 false 表示当前没有活动生命周期，数据被丢弃 */
        boolean onData(BinaryContext context);
    }
}
