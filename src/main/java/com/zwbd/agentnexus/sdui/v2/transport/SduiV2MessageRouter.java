package com.zwbd.agentnexus.sdui.v2.transport;

import com.zwbd.agentnexus.sdui.v2.protocol.BinaryDataType;
import com.zwbd.agentnexus.sdui.v2.protocol.BinaryFrameCodecV2;
import com.zwbd.agentnexus.sdui.v2.protocol.Envelope;
import com.zwbd.agentnexus.sdui.v2.protocol.EnvelopeCodec;
import com.zwbd.agentnexus.sdui.v2.protocol.ProtocolException;
import com.zwbd.agentnexus.sdui.v2.protocol.ProtocolErrors;
import com.zwbd.agentnexus.sdui.v2.session.DeviceTenantContext;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/**
 * v2 接入层路由。
 *
 * <p>替代旧的 {@code MessageRouter}：旧实现按 topic 字符串查 handler，且 inbound 与 outbound
 * 共用一套 topic 命名（{@code cmd/control} / {@code cmd/control_ack}），导致协议版本与消息语义混在
 * 同一个字段里。v2 按**消息名**与**数据类型**分发，与 04_PROTOCOL_MODEL.md §5 的三种信封一一对应。</p>
 *
 * <p>本类不做业务语义：只判定信封类型、找到扩展点、回写结果、记录丢弃。</p>
 */
@Slf4j
@Component
public class SduiV2MessageRouter {

    private final EnvelopeCodec codec;
    private final DeviceSender sender;
    private final PlatformRequestService requestService;
    private final DeviceTenantContext tenantContext;
    private final Map<String, V2Contexts.RequestHandler> requestHandlers = new HashMap<>();
    private final Map<String, V2Contexts.EventSink> eventSinks = new HashMap<>();
    private final Map<BinaryDataType, V2Contexts.BinarySink> binarySinks = new HashMap<>();

    private final AtomicLong rejectedEnvelopes = new AtomicLong();
    private final AtomicLong droppedBinaryFrames = new AtomicLong();

    public SduiV2MessageRouter(EnvelopeCodec codec,
                               DeviceSender sender,
                               PlatformRequestService requestService,
                               DeviceTenantContext tenantContext,
                               List<V2Contexts.RequestHandler> requestHandlerList,
                               List<V2Contexts.EventSink> eventSinkList,
                               List<V2Contexts.BinarySink> binarySinkList) {
        this.codec = codec;
        this.sender = sender;
        this.requestService = requestService;
        this.tenantContext = tenantContext;
        for (V2Contexts.RequestHandler handler : requestHandlerList) {
            V2Contexts.RequestHandler previous = requestHandlers.put(handler.name(), handler);
            if (previous != null) {
                throw new IllegalStateException("重复注册的请求处理器: " + handler.name());
            }
            log.info("v2 请求路由已注册: {} -> {}", handler.name(), handler.getClass().getSimpleName());
        }
        for (V2Contexts.EventSink sink : eventSinkList) {
            for (String name : sink.names()) {
                V2Contexts.EventSink previous = eventSinks.put(name, sink);
                if (previous != null) {
                    throw new IllegalStateException("重复注册的事件接收器: " + name);
                }
                log.info("v2 事件路由已注册: {} -> {}", name, sink.getClass().getSimpleName());
            }
        }
        for (V2Contexts.BinarySink sink : binarySinkList) {
            V2Contexts.BinarySink previous = binarySinks.put(sink.dataType(), sink);
            if (previous != null) {
                throw new IllegalStateException("重复注册的二进制接收器: " + sink.dataType());
            }
            log.info("v2 二进制路由已注册: {} -> {}", sink.dataType(), sink.getClass().getSimpleName());
        }
    }

    // ── 文本帧 ──────────────────────────────────────────────────────────────

    public void onText(String deviceId, long generation, String json) {
        Envelope envelope;
        try {
            envelope = codec.decode(json);
        } catch (ProtocolException e) {
            rejectedEnvelopes.incrementAndGet();
            log.warn("拒绝无法解析的控制报文: device={}, error={}, payload={}", deviceId, e.code(), abbreviate(json));
            return;
        }

        if (envelope instanceof Envelope.Result result) {
            requestService.onResult(deviceId, result.id(), result.ok(), result.error());
            return;
        }
        if (envelope instanceof Envelope.Request request) {
            handleRequest(deviceId, generation, request);
            return;
        }
        if (envelope instanceof Envelope.Event event) {
            handleEvent(deviceId, generation, event);
        }
    }

    private void handleRequest(String deviceId, long generation, Envelope.Request request) {
        V2Contexts.RequestHandler handler = requestHandlers.get(request.name());
        if (handler == null) {
            log.debug("没有处理器，回送 unknown_name: device={}, name={}", deviceId, request.name());
            respond(deviceId, codec.encodeResult(request.id(), false, ProtocolErrors.UNKNOWN_NAME));
            return;
        }
        try {
            // 处理器可能读写租户表（artifact、业务配置、能力缓存落库等），必须先按设备归属建立租户
            var result = tenantContext.callWith(deviceId, () ->
                    handler.handle(new V2Contexts.RequestContext(deviceId, generation, request.id(), request.body())));
            respond(deviceId, codec.encodeResult(request.id(), result.ok(), result.error()));
        } catch (ProtocolException e) {
            log.warn("请求处理失败: device={}, name={}, error={}", deviceId, request.name(), e.code());
            respond(deviceId, codec.encodeResult(request.id(), false, e.code()));
        } catch (RuntimeException e) {
            log.error("请求处理异常: device={}, name={}", deviceId, request.name(), e);
            respond(deviceId, codec.encodeResult(request.id(), false, ProtocolErrors.UNSUPPORTED));
        }
    }

    private void handleEvent(String deviceId, long generation, Envelope.Event event) {
        V2Contexts.EventSink sink = eventSinks.get(event.name());
        if (sink == null) {
            log.debug("没有接收器，事件被忽略: device={}, name={}", deviceId, event.name());
            return;
        }
        try {
            // platform.interaction 会驱动工作流运行、读部署记录并写运行记录，全部是租户表
            tenantContext.runWith(deviceId, () ->
                    sink.onEvent(new V2Contexts.EventContext(deviceId, generation, event.name(), event.body())));
        } catch (ProtocolException e) {
            log.warn("事件处理失败: device={}, name={}, error={}", deviceId, event.name(), e.code());
        } catch (RuntimeException e) {
            log.error("事件处理异常: device={}, name={}", deviceId, event.name(), e);
        }
    }

    private void respond(String deviceId, com.fasterxml.jackson.databind.JsonNode result) {
        if (!sender.sendControl(deviceId, result)) {
            log.warn("结果回送失败: device={}", deviceId);
        }
    }

    // ── 二进制帧 ────────────────────────────────────────────────────────────

    public void onBinary(String deviceId, long generation, byte[] rawFrame) {
        BinaryFrameCodecV2.DecodedFrame frame;
        try {
            frame = BinaryFrameCodecV2.decode(rawFrame, Integer.MAX_VALUE);
        } catch (ProtocolException e) {
            rejectedEnvelopes.incrementAndGet();
            log.warn("拒绝无法解析的二进制帧: device={}, error={}", deviceId, e.code());
            return;
        }

        V2Contexts.BinarySink sink = binarySinks.get(frame.dataType());
        if (sink == null) {
            droppedBinaryFrames.incrementAndGet();
            log.debug("没有接收器，二进制帧被丢弃: device={}, dataType={}", deviceId, frame.dataType());
            return;
        }
        try {
            // 上行音频会落 artifact（租户表），同样需要设备归属租户
            boolean consumed = tenantContext.callWith(deviceId, () ->
                    sink.onData(new V2Contexts.BinaryContext(deviceId, generation, frame.payload())));
            if (!consumed) {
                // 04§8.1：没有活动生命周期时收到对应 Binary 数据应拒绝或丢弃
                droppedBinaryFrames.incrementAndGet();
                log.debug("无活动生命周期，二进制帧被丢弃: device={}, dataType={}", deviceId, frame.dataType());
            }
        } catch (ProtocolException e) {
            droppedBinaryFrames.incrementAndGet();
            log.warn("二进制数据处理失败: device={}, dataType={}, error={}", deviceId, frame.dataType(), e.code());
        } catch (RuntimeException e) {
            droppedBinaryFrames.incrementAndGet();
            log.error("二进制数据处理异常: device={}, dataType={}", deviceId, frame.dataType(), e);
        }
    }

    public long rejectedEnvelopes() {
        return rejectedEnvelopes.get();
    }

    public long droppedBinaryFrames() {
        return droppedBinaryFrames.get();
    }

    public boolean hasRequestHandler(String name) {
        return requestHandlers.containsKey(name);
    }

    private static String abbreviate(String json) {
        if (json == null) {
            return "null";
        }
        return json.length() <= 200 ? json : json.substring(0, 200) + "...";
    }
}
