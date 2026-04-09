package com.zwbd.agentnexus.sdui;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zwbd.agentnexus.common.web.GlobalContext;
import com.zwbd.agentnexus.sdui.repo.SduiDeviceRepository;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.util.UriComponentsBuilder;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@Component
public class MessageRouter {

    private static final String SPACE_ID_QUERY_PARAM = "spaceId";

    private final ObjectMapper objectMapper;
    private final SduiDeviceRepository deviceRepository;
    private final List<TopicHandler> handlers;
    private final Map<String, TopicHandler> handlerMap = new HashMap<>();

    public MessageRouter(ObjectMapper objectMapper, SduiDeviceRepository deviceRepository, List<TopicHandler> handlers) {
        this.objectMapper = objectMapper;
        this.deviceRepository = deviceRepository;
        this.handlers = handlers;
    }

    @PostConstruct
    public void init() {
        for (TopicHandler handler : handlers) {
            handlerMap.put(handler.getSupportedTopic(), handler);
            log.info("Registered SDUI route: {} -> {}", handler.getSupportedTopic(), handler.getClass().getSimpleName());
        }
    }

    public void routeMessage(WebSocketSession session, String jsonPayload) {
        try {
            SduiMessage message = objectMapper.readValue(jsonPayload, SduiMessage.class);
            if (message.getTopic() == null || message.getDeviceId() == null) {
                log.warn("Invalid SDUI message (missing topic or device_id): {}", jsonPayload);
                return;
            }

            TopicHandler handler = handlerMap.get(message.getTopic());
            if (handler == null) {
                log.debug("No handler found for topic [{}], ignored", message.getTopic());
                return;
            }

            String spaceId = resolveSpaceId(session, message);
            if (!StringUtils.hasText(spaceId)) {
                spaceId = GlobalContext.DEFAULT_SPACE_ID;
            }

            GlobalContext.set(GlobalContext.KEY_SPACE_ID, spaceId);
            try {
                handler.handle(session, message);
            } finally {
                GlobalContext.clear();
            }
        } catch (Exception e) {
            log.error("Failed to route SDUI message. Payload: {}", jsonPayload, e);
        }
    }

    private String resolveSpaceId(WebSocketSession session, SduiMessage message) {
        String fromBinding = deviceRepository.findById(message.getDeviceId())
                .map(d -> d.getOwnerSpaceId())
                .filter(StringUtils::hasText)
                .orElse(null);
        if (StringUtils.hasText(fromBinding)) {
            return fromBinding.trim();
        }

        if (StringUtils.hasText(message.getSpaceId())) {
            return message.getSpaceId().trim();
        }

        if (session.getUri() != null) {
            var queryParams = UriComponentsBuilder.fromUri(session.getUri()).build().getQueryParams();
            String fromQuery = queryParams.getFirst(SPACE_ID_QUERY_PARAM);
            if (StringUtils.hasText(fromQuery)) {
                return fromQuery.trim();
            }

            String fromLegacyQuery = queryParams.getFirst(GlobalContext.KEY_SPACE_ID);
            if (StringUtils.hasText(fromLegacyQuery)) {
                return fromLegacyQuery.trim();
            }
        }

        String fromHeader = session.getHandshakeHeaders().getFirst("X-Space-Id");
        if (StringUtils.hasText(fromHeader)) {
            return fromHeader.trim();
        }

        return null;
    }
}
