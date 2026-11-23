package com.ainative.v2.daemon;

import com.ainative.v2.dispatch.DispatchDao;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.List;
import java.util.Map;

/**
 * 执行器 WS 协议(上行帧):HEARTBEAT / TASK_ACK / TASK_PROGRESS / TASK_RESULT / TASK_HANDOFF。
 * 鉴权:查询参数 executorId + token,与 ag_executor.token_hash 比对。
 */
@Component
public class ExecutorWsHandler extends TextWebSocketHandler {
    private static final Logger log = LoggerFactory.getLogger(ExecutorWsHandler.class);

    private final ExecutorHub hub;
    private final JdbcTemplate jdbc;
    private final DispatchDao dispatchDao;
    private final com.ainative.v2.cv.ConversationTurnService conversationTurns;
    private final ObjectMapper mapper = new ObjectMapper();

    public ExecutorWsHandler(ExecutorHub hub, JdbcTemplate jdbc, DispatchDao dispatchDao,
                             com.ainative.v2.cv.ConversationTurnService conversationTurns) {
        this.hub = hub;
        this.jdbc = jdbc;
        this.dispatchDao = dispatchDao;
        this.conversationTurns = conversationTurns;
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) throws Exception {
        Map<String, String> params = parseQuery(session.getUri());
        String executorIdRaw = params.get("executorId");
        String token = params.get("token");
        if (executorIdRaw == null || token == null) {
            session.close(CloseStatus.NOT_ACCEPTABLE.withReason("missing executorId or token"));
            return;
        }
        long executorId;
        try {
            executorId = Long.parseLong(executorIdRaw);
        } catch (NumberFormatException e) {
            session.close(CloseStatus.NOT_ACCEPTABLE.withReason("bad executorId"));
            return;
        }
        if (!tokenMatches(executorId, token)) {
            session.close(CloseStatus.POLICY_VIOLATION.withReason("invalid token"));
            return;
        }
        session.getAttributes().put("executorId", executorId);
        jdbc.update("UPDATE ag_executor SET status='ONLINE', last_heartbeat=NOW() WHERE id=?", executorId);
        hub.register(executorId, session);
        // 连接建立先按不健康处理(宁缺毋滥):待首个 HEARTBEAT 帧上报自检结果后刷新。
        // 老 daemon 不上报 healthy 字段 → 始终视为不健康,仅在无健康节点时才轮到它们。
        hub.markHealthy(executorId, false);
        // 上线即补投:运行器离线期间用户发的对话回合已落库为 PENDING,这里重放。
        // 执行器是共享节点池,不按注册名下的 agent 过滤积压(帧里带会话自己的 agentId)。
        try {
            conversationTurns.replayPending(executorId);
        } catch (Exception e) {
            log.warn("conversation replay on connect failed executorId={} error={}", executorId, e.getMessage());
        }
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        Object id = session.getAttributes().get("executorId");
        if (id instanceof Long executorId) {
            hub.unregister(executorId, session);
            jdbc.update("UPDATE ag_executor SET status='OFFLINE' WHERE id=?", executorId);
            log.info("executor disconnected executorId={} status={}", executorId, status);
        }
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) {
        Object id = session.getAttributes().get("executorId");
        if (!(id instanceof Long executorId)) {
            return;
        }
        try {
            JsonNode frame = mapper.readTree(message.getPayload());
            String type = frame.path("type").asText("");
            if ("CONVERSATION_TURN_EVENT".equals(type)) {
                log.info("executor frame received type={} executorId={}", type, executorId);
            }
            switch (type) {
                case "HEARTBEAT" -> {
                    // healthy 缺省 false:老 daemon 不上报自检结果,视作不健康
                    hub.markHealthy(executorId, frame.path("healthy").asBoolean(false));
                    jdbc.update(
                        "UPDATE ag_executor SET status='ONLINE', last_heartbeat=NOW() WHERE id=?", executorId);
                }
                case "TASK_ACK" -> log.info("executor ack executorId={} dispatchId={}",
                        executorId, frame.path("dispatchId").asLong());
                case "TASK_PROGRESS" -> onProgress(frame);
                case "TASK_RESULT" -> onResult(frame);
                case "TASK_HANDOFF" -> log.info("executor handoff executorId={} dispatchId={} to={}",
                        executorId, frame.path("dispatchId").asLong(), frame.path("to").asText(""));
                case "CONVERSATION_TURN_ACK" -> {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> payload = mapper.convertValue(frame, Map.class);
                    conversationTurns.handleAck(payload);
                }
                // 流式事件(文字/思考增量、工具调用):只转发给在线浏览器,不落库
                case "CONVERSATION_TURN_EVENT" -> {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> payload = mapper.convertValue(frame, Map.class);
                    conversationTurns.handleTurnEvent(payload);
                }
                default -> log.debug("executor frame ignored type={}", type);
            }
        } catch (Exception e) {
            log.warn("executor frame error executorId={} error={}", executorId, e.getMessage());
        }
    }

    private void onProgress(JsonNode frame) {
        long dispatchId = frame.path("dispatchId").asLong();
        String eventType = frame.path("resultSummary").asText("");
        dispatchDao.appendEvent(dispatchId, nextSeq(dispatchId),
                eventType == null || eventType.isBlank() ? "progress" : eventType,
                frame.hasNonNull("stepId") ? frame.path("stepId").asText() : null,
                frame.toString());
    }

    private void onResult(JsonNode frame) {
        long dispatchId = frame.path("dispatchId").asLong();
        boolean success = frame.path("success").asBoolean(false);
        String summary = frame.path("resultSummary").asText("");
        String error = frame.path("error").asText("");
        dispatchDao.appendEvent(dispatchId, nextSeq(dispatchId), success ? "run.succeeded" : "run.failed", null, frame.toString());
        dispatchDao.finishRun(dispatchId, success ? "SUCCEEDED" : "FAILED", success ? null : error);
        log.info("dispatch finished dispatchId={} success={} summary={}", dispatchId, success,
                summary == null ? "" : summary.substring(0, Math.min(80, summary.length())));
    }

    private int nextSeq(long dispatchId) {
        List<DispatchDao.Event> events = dispatchDao.eventsOf(dispatchId, 500);
        return events.isEmpty() ? 1 : events.get(events.size() - 1).seq() + 1;
    }

    private boolean tokenMatches(long executorId, String token) {
        List<String> hashes = jdbc.queryForList("SELECT token_hash FROM ag_executor WHERE id=? AND deleted_at IS NULL",
                String.class, executorId);
        if (hashes.isEmpty() || hashes.get(0) == null) {
            return false;
        }
        return hashes.get(0).equals(sha256(token));
    }

    static String sha256(String value) {
        return TokenHash.sha256(value);
    }

    private static Map<String, String> parseQuery(URI uri) {
        Map<String, String> out = new java.util.HashMap<>();
        String query = uri == null ? null : uri.getRawQuery();
        if (query == null) {
            return out;
        }
        for (String pair : query.split("&")) {
            int idx = pair.indexOf('=');
            if (idx > 0) {
                out.put(java.net.URLDecoder.decode(pair.substring(0, idx), StandardCharsets.UTF_8),
                        java.net.URLDecoder.decode(pair.substring(idx + 1), StandardCharsets.UTF_8));
            }
        }
        return out;
    }
}
