package com.zwbd.agentnexus.document.etl.advisor;

import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.StreamAdvisor;
import org.springframework.ai.chat.client.advisor.api.StreamAdvisorChain;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.document.Document;
import org.springframework.ai.rag.advisor.RetrievalAugmentationAdvisor;
import org.springframework.util.CollectionUtils;
import reactor.core.publisher.Flux;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * @Author: wnli
 * @Date: 2025/10/14 16:31
 * @Desc:
 */
@Deprecated
@Slf4j
public class DocumentCaptureStreamAdvisor implements StreamAdvisor {

    @Override
    public Flux<ChatClientResponse> adviseStream(ChatClientRequest request, StreamAdvisorChain chain) {
        // 调用链下一个 advisor，获取原始流
        Flux<ChatClientResponse> responseFlux = chain.nextStream(request);

        // 使用 map 操作处理每个响应块，注入文档到 ChatResponse 元数据
        return responseFlux.map(response -> {
            response.chatResponse().hasToolCalls();
            // 从 context (adviseContext) 提取文档
            @SuppressWarnings("unchecked")
            List<Document> documents = (List<Document>) response.context()

                    .getOrDefault(RetrievalAugmentationAdvisor.DOCUMENT_CONTEXT, List.of());

            if (!CollectionUtils.isEmpty(documents)) {
                log.info("从 adviseContext 捕获 " + documents.size() + " 个文档");

                // 获取当前 ChatResponse 的元数据副本
                ChatResponse currentChatResponse = response.chatResponse();
                if (currentChatResponse != null) {
                    Map<String, Object> newMetadata = new HashMap<>((Map) currentChatResponse.getMetadata());
                    newMetadata.put("retriever.documents", new ArrayList<>(documents)); // 复制列表避免副作用

                    // 构建更新后的 ChatResponse（仅修改元数据，内容不变）
                    ChatResponse updatedChatResponse = ChatResponse.builder()
                            .from(currentChatResponse)  // 复制现有内容
                            .metadata(ChatResponseMetadata.builder().metadata(newMetadata).build())
                            .build();

                    // 使用 mutate 更新 ChatClientResponse 的 chatResponse 组件
                    return response.mutate()
                            .chatResponse(updatedChatResponse)
                            .build();
                }
            } else {
                log.info("adviseContext 中未找到文档");
            }
            // 如果无文档或响应为空，直接返回原响应
            return response;
        });
    }

    @Override
    public String getName() {
        return "DocumentCaptureStreamAdvisor";
    }

    @Override
    public int getOrder() {
        return 1;
    }
}