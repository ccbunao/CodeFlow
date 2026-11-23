package com.ainative.v2.realtime;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 面向浏览器的实时频道中心:按频道名把事件推给已订阅的在线会话。
 *
 * <p>帧契约与前端 shared/realtime/client.ts 一致:
 * <pre>{ "channel": "...", "type": "...", "payload": {...}, "timestamp": 123 }</pre>
 * 订阅关系只存内存 —— 浏览器端本来就是重连后全量重订阅,不需要落库。
 */
@Component
public class RealtimeHub {
    private static final Logger log = LoggerFactory.getLogger(RealtimeHub.class);
    private final ObjectMapper mapper = new ObjectMapper();

    /** 频道名 → 已订阅的会话。 */
    private final Map<String, Set<WebSocketSession>> channels = new ConcurrentHashMap<>();

    public void subscribe(String channel, WebSocketSession session) {
        channels.computeIfAbsent(channel, k -> ConcurrentHashMap.newKeySet()).add(session);
    }

    public void unsubscribeChannel(String channel, WebSocketSession session) {
        Set<WebSocketSession> sessions = channels.get(channel);
        if (sessions != null) {
            sessions.remove(session);
        }
    }

    /** 会话断开:从它出现的所有频道摘除(浏览器重连会重新订阅)。 */
    public void unsubscribeAll(WebSocketSession session) {
        channels.values().forEach(set -> set.remove(session));
        channels.keySet().removeIf(k -> channels.get(k).isEmpty());
    }

    /** 向频道内所有在线会话推送事件,返回成功送达的会话数。 */
    public int publish(String channel, String type, Object payload) {
        Set<WebSocketSession> sessions = channels.get(channel);
        if (sessions == null || sessions.isEmpty()) {
            return 0;
        }
        Map<String, Object> frame = new LinkedHashMap<>();
        frame.put("channel", channel);
        frame.put("type", type);
        frame.put("payload", payload);
        frame.put("timestamp", System.currentTimeMillis());
        String text;
        try {
            text = mapper.writeValueAsString(frame);
        } catch (IOException e) {
            log.warn("realtime serialize failed channel={} type={} error={}", channel, type, e.getMessage());
            return 0;
        }
        int delivered = 0;
        for (WebSocketSession session : sessions) {
            try {
                synchronized (session) {
                    if (session.isOpen()) {
                        session.sendMessage(new TextMessage(text));
                        delivered++;
                    }
                }
            } catch (Exception e) {
                // 推送失败只影响这一个会话:摘掉即可,不能让其余订阅者跟着丢事件
                sessions.remove(session);
                log.debug("realtime send failed channel={} session={} error={}",
                        channel, session.getId(), e.getMessage());
            }
        }
        if (delivered == 0) {
            log.info("realtime publish no subscriber channel={} type={}", channel, type);
        }
        return delivered;
    }

    public int subscriberCount(String channel) {
        Set<WebSocketSession> sessions = channels.get(channel);
        return sessions == null ? 0 : sessions.size();
    }
}
