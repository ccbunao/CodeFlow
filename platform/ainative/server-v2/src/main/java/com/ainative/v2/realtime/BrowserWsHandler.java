package com.ainative.v2.realtime;

import com.ainative.v2.auth.JwtService;
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

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 浏览器实时端点 /ws(与前端 shared/realtime/client.ts 同一契约)。
 *
 * <p>WebSocket 握手带不了 Authorization 头,所以鉴权走带内帧:连上后第一帧
 * <pre>{ "type": "auth", "token": "&lt;accessToken&gt;" }</pre>
 * 鉴权通过前只接受 auth 帧;令牌无效直接以 1008 关闭。
 *
 * <p>频道按「会话所属工作区 = 令牌里的工作区」授权:conversation:&lt;chatId&gt;
 * 只放给该会话所在工作区的成员,跨工作区的订阅静默忽略(不回错误,不泄露会话是否存在)。
 */
@Component
public class BrowserWsHandler extends TextWebSocketHandler {
    private static final Logger log = LoggerFactory.getLogger(BrowserWsHandler.class);
    private static final Pattern CONVERSATION_CHANNEL = Pattern.compile("^conversation:(\\d+)$");

    private final RealtimeHub hub;
    private final JwtService jwt;
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper = new ObjectMapper();

    public BrowserWsHandler(RealtimeHub hub, JwtService jwt, JdbcTemplate jdbc) {
        this.hub = hub;
        this.jwt = jwt;
        this.jdbc = jdbc;
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
        // 未鉴权先入表:订阅要等 auth 帧之后才生效(见 handleTextMessage)
        session.getAttributes().put("authed", Boolean.FALSE);
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) throws Exception {
        JsonNode frame;
        try {
            frame = mapper.readTree(message.getPayload());
        } catch (Exception e) {
            return; // 畸形帧直接丢弃
        }
        String type = frame.path("type").asText("");
        if ("auth".equals(type)) {
            JwtService.TokenClaims claims = jwt.verifyClaims(frame.path("token").asText(""));
            if (claims == null || claims.userId() == null) {
                session.close(CloseStatus.POLICY_VIOLATION.withReason("invalid token"));
                return;
            }
            session.getAttributes().put("authed", Boolean.TRUE);
            session.getAttributes().put("workspaceId", claims.workspaceId());
            return;
        }
        if (!Boolean.TRUE.equals(session.getAttributes().get("authed"))) {
            return;
        }
        String channel = frame.path("channel").asText("");
        if ("subscribe".equals(type)) {
            if (canSubscribe(channel, session)) {
                hub.subscribe(channel, session);
                log.info("realtime subscribed channel={} session={} subscribers={}",
                        channel, session.getId(), hub.subscriberCount(channel));
            } else {
                // 订阅被拒必须留痕:静默忽略会让「浏览器连着但收不到事件」无从排查
                log.warn("realtime subscribe rejected channel={} session={} workspaceId={} authed={}",
                        channel, session.getId(), session.getAttributes().get("workspaceId"),
                        session.getAttributes().get("authed"));
            }
        } else if ("unsubscribe".equals(type)) {
            hub.unsubscribeChannel(channel, session);
        }
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        hub.unsubscribeAll(session);
    }

    /** 频道授权:目前只开放澄清会话频道,且必须属于令牌所在工作区。 */
    boolean canSubscribe(String channel, WebSocketSession session) {
        Matcher m = CONVERSATION_CHANNEL.matcher(channel);
        if (!m.matches()) {
            return false;
        }
        Object workspaceId = session.getAttributes().get("workspaceId");
        if (!(workspaceId instanceof Long wid)) {
            return false;
        }
        List<Long> rows = jdbc.queryForList(
                "SELECT id FROM ag_chat WHERE id=? AND workspace_id=? AND deleted_at IS NULL",
                Long.class, Long.parseLong(m.group(1)), wid);
        return !rows.isEmpty();
    }
}
