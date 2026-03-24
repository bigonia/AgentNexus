package com.zwbd.agentnexus.ai.web.controller;

import com.zwbd.agentnexus.ai.entity.Conversation;
import com.zwbd.agentnexus.ai.service.ConversationService;
import com.zwbd.agentnexus.common.web.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.ai.chat.messages.Message;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * @Author: wnli
 * @Date: 2025/11/24 16:35
 * @Desc:
 */
@RestController
@RequestMapping("/api/ai/conversations")
@RequiredArgsConstructor
@Tag(name = "Conversation API", description = "manager history")
public class ConversationController {

    private final ConversationService conversationService;

    /**
     * 1. 获取当前用户的会话列表
     * GET /api/ai/conversations?userId=1001
     */
    @GetMapping
    public ApiResponse<List<Conversation>> listConversations() {
        return ApiResponse.success(conversationService.getUserConversations());
    }

    /**
     * 2. 创建新会话
     * POST /api/ai/conversations
     */
    @PostMapping
    public ApiResponse<Conversation> createConversation(@RequestBody Map<String, String> body) {
        String title = body.getOrDefault("title", "New Chat");
        return ApiResponse.success(conversationService.createConversation(title));
    }

    /**
     * 3. 获取某个会话的具体消息历史（用于点击列表后回显）
     * GET /api/ai/conversations/{conversationId}/messages
     */
    @GetMapping("/{conversationId}/messages")
    public ApiResponse<List<Message>> getMessages(@PathVariable String conversationId) {
        return ApiResponse.success(conversationService.getConversationMessages(conversationId));
    }

    /**
     * 4. 删除会话
     * DELETE /api/ai/conversations/{conversationId}
     */
    @DeleteMapping("/{conversationId}")
    public ApiResponse deleteConversation(@PathVariable String conversationId) {
        conversationService.deleteConversation(conversationId);
        return ApiResponse.success();
    }

    /**
     * 5. 重命名会话
     * PATCH /api/ai/conversations/{conversationId}/title
     */
    @PatchMapping("/{conversationId}/title")
    public ResponseEntity<Void> updateTitle(@PathVariable String conversationId, @RequestBody Map<String, String> body) {
        String newTitle = body.get("title");
        conversationService.updateTitle(conversationId, newTitle);
        return ResponseEntity.ok().build();
    }
}