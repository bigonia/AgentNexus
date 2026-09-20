package com.zwbd.agentnexus.sdui.v2.sim;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zwbd.agentnexus.sdui.v2.capability.CapabilityHash;
import com.zwbd.agentnexus.sdui.v2.capability.CapabilitySchemaV2;
import com.zwbd.agentnexus.sdui.v2.protocol.BinaryDataType;
import com.zwbd.agentnexus.sdui.v2.protocol.BinaryFrameCodecV2;
import com.zwbd.agentnexus.sdui.v2.protocol.Envelope;
import com.zwbd.agentnexus.sdui.v2.protocol.EnvelopeCodec;
import com.zwbd.agentnexus.sdui.v2.protocol.V2Names;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * LCD_085 模拟终端（测试桩）。
 *
 * <p>为什么需要它：终端重构与平台侧升级并行，平台侧不能等真实固件才能验证协议。这个测试桩按
 * {@code docs/sdui/TERMINAL_CONTRACT.md} 定义的信封、二进制帧头与能力 Schema 形态
 * 构造上行报文、解析下行报文，使平台侧在无设备条件下也能做端到端回归。</p>
 *
 * <p>它只实现协议形态，不模拟终端的业务行为（不执行响应序列、不做音频编解码）。</p>
 */
public final class SimulatedLcd085Device {

    /**
     * 一个典型 LCD_085 固件的能力 Schema。
     *
     * <p>两侧动作都要声明：{@code binding} 侧是终端能在本地响应序列里就地执行的动作，
     * {@code request} 侧是平台可以主动下发的动作。二者命名空间不同（例如本地绑定是
     * {@code display.section.show}，平台请求是 {@code display.section}），且方向不可互换——
     * 只声明一侧就必须在另一侧不可达。早先的桩只声明了 {@code binding} 侧，导致"平台可达性"
     * 这条判定在测试里无从验证。</p>
     */
    public static final CapabilitySchemaV2 SCHEMA = new CapabilitySchemaV2(
            "2",
            "1",
            "LCD_085",
            List.of(
                    new CapabilitySchemaV2.TriggerSpec("button.ok", "physical", true, 4),
                    new CapabilitySchemaV2.TriggerSpec("button.up", "physical", true, 4),
                    new CapabilitySchemaV2.TriggerSpec("button.down", "physical", true, 4),
                    new CapabilitySchemaV2.TriggerSpec("platform.trigger", "platform", true, 4)
            ),
            List.of(
                    // ── 本地绑定额 ──
                    new CapabilitySchemaV2.ActionSpec("audio.record.start", List.of(), List.of("binding")),
                    new CapabilitySchemaV2.ActionSpec("audio.record.stop", List.of(), List.of("binding")),
                    new CapabilitySchemaV2.ActionSpec("audio.record.toggle", List.of(), List.of("binding")),
                    new CapabilitySchemaV2.ActionSpec("rgb.effect.set", List.of(
                            new CapabilitySchemaV2.ParamSpec("r", "int", true, 0, 255, null),
                            new CapabilitySchemaV2.ParamSpec("g", "int", true, 0, 255, null),
                            new CapabilitySchemaV2.ParamSpec("b", "int", true, 0, 255, null)
                    ), List.of("binding")),
                    new CapabilitySchemaV2.ActionSpec("display.section.show", List.of(
                            new CapabilitySchemaV2.ParamSpec("sectionId", "string", true, null, null, null)
                    ), List.of("binding")),
                    new CapabilitySchemaV2.ActionSpec("prompt.play", List.of(
                            new CapabilitySchemaV2.ParamSpec("preset", "enum", true, null, null,
                                    List.of("start", "stop", "error"))
                    ), List.of("binding")),

                    // ── 平台请求侧 ──
                    new CapabilitySchemaV2.ActionSpec("capability.get", List.of(), List.of("request")),
                    new CapabilitySchemaV2.ActionSpec("display.section", List.of(
                            new CapabilitySchemaV2.ParamSpec("section", "object", true, null, null, null)
                    ), List.of("request")),
                    new CapabilitySchemaV2.ActionSpec("display.image.begin", List.of(), List.of("request")),
                    new CapabilitySchemaV2.ActionSpec("display.image.end", List.of(), List.of("request")),
                    new CapabilitySchemaV2.ActionSpec("display.canvas.open", List.of(), List.of("request")),
                    new CapabilitySchemaV2.ActionSpec("display.canvas.close", List.of(), List.of("request")),
                    new CapabilitySchemaV2.ActionSpec("audio.start", List.of(), List.of("request")),
                    new CapabilitySchemaV2.ActionSpec("audio.stop", List.of(), List.of("request")),
                    new CapabilitySchemaV2.ActionSpec("audio.abort", List.of(), List.of("request")),
                    new CapabilitySchemaV2.ActionSpec("system.volume.set", List.of(
                            new CapabilitySchemaV2.ParamSpec("value", "int", true, 0, 100, null)
                    ), List.of("request")),
                    new CapabilitySchemaV2.ActionSpec("system.brightness.set", List.of(
                            new CapabilitySchemaV2.ParamSpec("value", "int", true, 0, 100, null)
                    ), List.of("request")),
                    new CapabilitySchemaV2.ActionSpec("system.reboot", List.of(), List.of("request")),
                    new CapabilitySchemaV2.ActionSpec("system.provisioning.start", List.of(), List.of("request")),
                    new CapabilitySchemaV2.ActionSpec("business.update", List.of(), List.of("request")),
                    new CapabilitySchemaV2.ActionSpec("business.reset", List.of(), List.of("request")),
                    new CapabilitySchemaV2.ActionSpec("business.trigger", List.of(
                            new CapabilitySchemaV2.ParamSpec("token", "string", true, null, null, null)
                    ), List.of("request"))
            ),
            new CapabilitySchemaV2.Surface(
                    new CapabilitySchemaV2.ScreenSpec(128, 128, false),
                    new CapabilitySchemaV2.UiSpec(
                            List.of("status_section", "metrics_section", "text_section", "menu_section", "chart_section"),
                            List.of("section", "image", "canvas")),
                    new CapabilitySchemaV2.ImageSpec(32768, 16),
                    new CapabilitySchemaV2.CanvasSpec(List.of("16x16", "32x32", "64x64", "128x128"),
                            List.of(1, 2, 4), 10),
                    new CapabilitySchemaV2.AudioSpec(List.of("pcm_s16le"), 16000, 65536)
            ));

    private final String deviceId;
    private final EnvelopeCodec codec;
    private final ObjectMapper objectMapper;
    private final List<Envelope> receivedFromPlatform = new ArrayList<>();

    public SimulatedLcd085Device(String deviceId, ObjectMapper objectMapper) {
        this.deviceId = deviceId;
        this.objectMapper = objectMapper;
        this.codec = new EnvelopeCodec(objectMapper);
    }

    public String deviceId() {
        return deviceId;
    }

    public String capabilityHash() {
        return CapabilityHash.compute(SCHEMA, objectMapper);
    }

    /** 构造握手用的连接查询参数。 */
    public String connectionQuery() {
        return "?deviceId=" + deviceId + "&protocol_version=2&capability_hash=" + capabilityHash();
    }

    // ── 上行：终端 → 平台 ────────────────────────────────────────────────────

    public String helloRequest(String requestId) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("deviceId", deviceId);
        body.put("protocolVersion", "2");
        body.put("capabilityHash", capabilityHash());
        return codec.toJson(codec.encodeRequest(requestId, V2Names.DEVICE_HELLO, body));
    }

    /** 构造对平台请求的结果回执。 */
    public String result(String requestId, boolean ok, String error) {
        return codec.toJson(codec.encodeResult(requestId, ok, error));
    }

    /** 构造业务交互上报事件。 */
    public String interactionReport(String reportToken) {
        return codec.toJson(codec.encodeEvent(V2Names.PLATFORM_INTERACTION, Map.of("token", reportToken)));
    }

    /** 构造音频生命周期事件。 */
    public String audioEvent(String name, String reason) {
        Map<String, Object> body = new LinkedHashMap<>();
        if (reason != null) {
            body.put("reason", reason);
        }
        return codec.toJson(codec.encodeEvent(name, body));
    }

    /** 构造能力 Schema 上传的二进制帧。 */
    public byte[] capabilitySchemaFrame() {
        try {
            byte[] payload = objectMapper.writeValueAsBytes(SCHEMA);
            return BinaryFrameCodecV2.encode(BinaryDataType.CAPABILITY_SCHEMA, payload, 65_536);
        } catch (Exception e) {
            throw new IllegalStateException("无法序列化模拟 Schema", e);
        }
    }

    /** 构造上行 PCM 帧。 */
    public byte[] audioFrame(int byteCount) {
        return BinaryFrameCodecV2.encode(BinaryDataType.AUDIO, new byte[byteCount], 65_536);
    }

    // ── 下行：平台 → 终端 ────────────────────────────────────────────────────

    /** 解析一条平台下发的 JSON 报文，并记录。 */
    public Envelope receive(String json) {
        Envelope envelope = codec.decode(json);
        receivedFromPlatform.add(envelope);
        return envelope;
    }

    public List<Envelope> receivedFromPlatform() {
        return List.copyOf(receivedFromPlatform);
    }

    public List<Envelope.Request> receivedRequests() {
        return receivedFromPlatform.stream()
                .filter(Envelope.Request.class::isInstance)
                .map(Envelope.Request.class::cast)
                .toList();
    }

    /** 最近一条收到的请求，按名查找。 */
    public Envelope.Request lastRequestNamed(String name) {
        List<Envelope.Request> matches = receivedRequests().stream()
                .filter(request -> request.name().equals(name))
                .toList();
        return matches.isEmpty() ? null : matches.get(matches.size() - 1);
    }

    public void reset() {
        receivedFromPlatform.clear();
    }
}
