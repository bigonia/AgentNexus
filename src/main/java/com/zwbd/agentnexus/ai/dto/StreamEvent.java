package com.zwbd.agentnexus.ai.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * 定义了在流式响应中发送的事件结构。
 *
 * @param type 事件类型 (e.g., "CONTEXT", "TEXT", "END")
 * @param data 事件携带的数据。对于 CONTEXT，它是一个 DocumentChunkDTO 列表；对于 TEXT，它是一个字符串。
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record StreamEvent(
        EventType type,
        Object data
) {
    public enum EventType {
        /**
         * 会话信息事件，在流开始时发送，包含 sessionId。
         */
        SESSION_INFO,
        /**
         * 上下文事件，在流开始时发送，包含检索到的文档。
         */
        CONTEXT,

        /**
         * 工具执行
         */
        TOOL_EXECUTION,
        /**
         * 文本事件，流式传输 LLM 生成的回答文本。
         */
        TEXT,
        /**
         * 结束事件，标志着流的结束。
         */
        END
    }
}
