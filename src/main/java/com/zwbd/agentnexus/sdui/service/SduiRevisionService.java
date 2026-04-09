package com.zwbd.agentnexus.sdui.service;

import org.springframework.stereotype.Service;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

@Service
public class SduiRevisionService {

    private final ConcurrentHashMap<String, AtomicInteger> revisions = new ConcurrentHashMap<>();

    public int nextRevision(String deviceId, String pageId) {
        String key = deviceId + ":" + pageId;
        return revisions.computeIfAbsent(key, k -> new AtomicInteger(0)).incrementAndGet();
    }
}

