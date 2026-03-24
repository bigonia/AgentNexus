package com.zwbd.agentnexus.ai.web.controller;

import com.zwbd.agentnexus.ai.dto.ChatRequest;
import com.zwbd.agentnexus.ai.dto.StreamEvent;
import com.zwbd.agentnexus.ai.service.ChatClientService;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.messages.Message;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Flux;

import java.util.List;

/**
 * @Author: wnli
 * @Date: 2025/10/15 14:33
 * @Desc:
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/chat-client")
@RequiredArgsConstructor
@Tag(name = "Chat Client API", description = "chat with spring ai")
public class ChatClientController {

    @Autowired
    private ChatClientService chatClientService;

    @PostMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<StreamEvent> chatStream(@Valid @RequestBody ChatRequest request) {
        return chatClientService.chat(request);
    }

    @GetMapping(value = "/history/{id}")
    public List<Message> history(@PathVariable String id) {
        List<Message> historyChat = chatClientService.getHistoryChat(id);
        return historyChat;
    }




}
