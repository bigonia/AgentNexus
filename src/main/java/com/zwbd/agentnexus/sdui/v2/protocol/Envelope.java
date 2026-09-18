package com.zwbd.agentnexus.sdui.v2.protocol;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * v2 控制面三种最小信封。
 *
 * <p>对应 04_PROTOCOL_MODEL.md §5：协议只使用 request / result / event 三种结构，
 * 不增加通用 {@code type} / {@code topic} / {@code action} / 时间戳 / metadata 字段。</p>
 *
 * <pre>
 * 平台 → 终端，id + name  = Request
 * 终端 → 平台，id + ok    = Result
 * 终端 → 平台，name 无 id = Event
 * </pre>
 */
public sealed interface Envelope {

    /** 平台发起的请求。{@code id} 在一次连接内唯一，终端只返回一次最终结果。 */
    record Request(String id, String name, JsonNode body) implements Envelope {}

    /** 终端返回的最终结果。{@code ok=true} 时 {@code error} 必须为 null。 */
    record Result(String id, boolean ok, String error) implements Envelope {

        public static Result ok(String id) {
            return new Result(id, true, null);
        }

        public static Result failure(String id, String error) {
            return new Result(id, false, error);
        }
    }

    /** 终端主动上报的事件，不携带请求 {@code id}，平台不回复。 */
    record Event(String name, JsonNode body) implements Envelope {}
}
