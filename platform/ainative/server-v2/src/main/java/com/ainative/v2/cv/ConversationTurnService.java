package com.ainative.v2.cv;

import com.ainative.v2.agent.Persona;
import com.ainative.v2.agent.AgentService;
import com.ainative.v2.artifact.ClarificationArchiveService;
import com.ainative.v2.common.BizException;
import com.ainative.v2.daemon.ExecutorHub;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 澄清会话执行(与运行器 CONVERSATION_TURN 协议对齐):
 * 平台把用户输入下发为 CONVERSATION_TURN,运行器执行 claude 后回 CONVERSATION_TURN_ACK,
 * 平台把回复落 ag_chat_turn 并推进会话状态。
 */
@Service
public class ConversationTurnService {
    private static final Logger log = LoggerFactory.getLogger(ConversationTurnService.class);

    private final JdbcTemplate jdbc;
    private final ExecutorHub hub;
    private final ClarificationArchiveService archive;
    private final AgentService agentService;
    private final ObjectMapper mapper = new ObjectMapper();
    /** 回合幂等留底:chatId+turnId → 已落库的回复(turnId 在会话内唯一,跨会话会重复) */
    private final Map<String, Map<String, Object>> done = new ConcurrentHashMap<>();
    /** 会话级 clarify MCP 令牌明文缓存:chatId → token(明文只在签发时拿得到一次) */
    private final Map<Long, String> clarifyTokens = new ConcurrentHashMap<>();
    /** 流式事件序号:chatId:turnId → 已分配的最大 seq(前端按 dispatchAttempt+eventSeq 去重排序) */
    private final Map<String, java.util.concurrent.atomic.AtomicInteger> eventSeqs = new ConcurrentHashMap<>();

    private final com.ainative.v2.realtime.RealtimeHub realtime;

    /**
     * 回合方向的表达式:库里存的是角色(USER/ASSISTANT),前端契约是 IN/OUT。
     * 之前把 t.role 直接当 direction 返回,前端只认 IN/INBOUND → 用户消息也被画成
     * 左侧的 AI 气泡(2026-11-23 走查:用户发的与 AI 回的挤在同一侧)。
     */
    public static final String TURN_DIRECTION_SQL =
            "CASE WHEN t.role='USER' THEN 'IN' ELSE 'OUT' END AS direction";

    @org.springframework.beans.factory.annotation.Autowired
    public ConversationTurnService(JdbcTemplate jdbc, ExecutorHub hub, ClarificationArchiveService archive,
                                   AgentService agentService, com.ainative.v2.realtime.RealtimeHub realtime) {
        this.jdbc = jdbc;
        this.hub = hub;
        this.archive = archive;
        this.agentService = agentService;
        this.realtime = realtime;
    }

    public ConversationTurnService(JdbcTemplate jdbc, ExecutorHub hub, ClarificationArchiveService archive,
                                   AgentService agentService) {
        this(jdbc, hub, archive, agentService, new com.ainative.v2.realtime.RealtimeHub());
    }

    /** 频道名与前端 useClarificationEvents 的订阅口径一致。 */
    public static String conversationChannel(long chatId) {
        return "conversation:" + chatId;
    }

    /**
     * 是否有可接活的执行器在线(共享节点池口径,与选节点同一语义)。
     * 前端的「支持流式 / 支持取消」等能力位都由它推导。
     */
    public boolean anyExecutorOnline() {
        List<Long> online = jdbc.queryForList(
                "SELECT id FROM ag_executor WHERE status='ONLINE' AND deleted_at IS NULL ORDER BY id", Long.class);
        return online.stream().anyMatch(id -> id != null && hub.isOnline(id));
    }

    /**
     * 会话当前是否「AI 正在回复」:最后一条回合是用户消息且尚未被应答(PENDING/SENT)。
     *
     * <p>10 分钟窗口:执行器中途挂掉时回合会永远停在 SENT,不加窗口会让输入框被
     * 永久禁用;超时后按「无人应答」处理,用户可以重发。
     *
     * @return 回合 id;不处于回复中返回 null
     */
    public Long processingTurnIdOf(long chatId) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT t.id, t.role, t.delivery_status, t.gmt_create FROM ag_chat_turn t"
                        + " WHERE t.chat_id=? AND t.deleted_at IS NULL ORDER BY t.id DESC LIMIT 1", chatId);
        if (rows.isEmpty()) {
            return null;
        }
        Map<String, Object> last = rows.get(0);
        boolean awaiting = "USER".equals(String.valueOf(last.get("role")))
                && ("PENDING".equals(String.valueOf(last.get("delivery_status")))
                    || "SENT".equals(String.valueOf(last.get("delivery_status"))));
        if (!awaiting) {
            return null;
        }
        Integer fresh = jdbc.queryForObject(
                "SELECT COUNT(*) FROM ag_chat_turn WHERE id=? AND gmt_create > NOW() - INTERVAL 10 MINUTE",
                Integer.class, ((Number) last.get("id")).longValue());
        return fresh != null && fresh > 0 ? ((Number) last.get("id")).longValue() : null;
    }

    /**
     * 运行器上报的流式事件(文字增量/思考增量/工具调用):编号后实时推给订阅该
     * 会话的浏览器。增量不落库 —— 一轮回复几百个 delta,落库只会撑爆事件表;
     * 落库的权威内容仍以 ACK 里的完整回复为准。
     */
    public void handleTurnEvent(Map<String, Object> frame) {
        long chatId = num(frame.get("conversationId"));
        long turnId = num(frame.get("turnId"));
        String eventType = String.valueOf(frame.getOrDefault("eventType", ""));
        if (chatId == 0 || turnId == 0 || eventType.isBlank()) {
            return;
        }
        int seq = eventSeqs.computeIfAbsent(chatId + ":" + turnId, k -> new java.util.concurrent.atomic.AtomicInteger())
                .incrementAndGet();
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("conversationId", chatId);
        payload.put("turnId", turnId);
        payload.put("dispatchAttempt", frame.getOrDefault("dispatchAttempt", 0));
        payload.put("eventSeq", seq);
        payload.put("eventType", eventType);
        payload.put("payload", frame.get("payload"));
        int delivered = realtime.publish(conversationChannel(chatId), "CONVERSATION_TURN_EVENT", payload);
        // 增量事件是体验增强:没有订阅者(浏览器没开面板)不算错,但要留痕便于排查
        if (delivered == 0) {
            log.info("conversation turn event has no subscriber chatId={} turnId={} type={}",
                    chatId, turnId, eventType);
        }
    }

    /** 回合终结(成功或失败):推一条 status 事件,前端据此收起流式区并拉取落库的回复。 */
    private void publishTurnStatus(long chatId, long turnId, String state) {
        eventSeqs.remove(chatId + ":" + turnId);
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("conversationId", chatId);
        payload.put("turnId", turnId);
        payload.put("eventSeq", Integer.MAX_VALUE);
        payload.put("eventType", "status");
        payload.put("payload", Map.of("state", state));
        realtime.publish(conversationChannel(chatId), "CONVERSATION_TURN_EVENT", payload);
    }

    /** 用户发送消息:落库 USER 回合 → 下发给在线执行器;无在线执行器时排队等待重放。 */
    @Transactional
    public Map<String, Object> send(long chatId, long agentId, long userId, String contentMd) {
        if (contentMd == null || contentMd.isBlank()) {
            throw new BizException("10001", "消息不能为空");
        }
        Integer seq = jdbc.queryForObject(
                "SELECT COALESCE(MAX(seq),0)+1 FROM ag_chat_turn WHERE chat_id=?", Integer.class, chatId);
        int nextSeq = seq == null ? 1 : seq;
        jdbc.update("INSERT INTO ag_chat_turn (workspace_id, chat_id, seq, role, content_md, delivery_status) VALUES (?,?,?,'USER',?, 'PENDING')",
                workspaceId(), chatId, nextSeq, contentMd);
        jdbc.update("INSERT INTO ag_chat_turn_event (workspace_id, turn_id, event_type, detail_json) VALUES (?,?,'USER_MESSAGE',?)",
                workspaceId(), nextSeq, null);

        // 找到该智能体的在线执行器并下发
        // DB 里的 ONLINE 可能已是僵尸(运行器进程已退出),逐个用 hub 校验真实连接
        Long executorId = onlineExecutorOf(agentId);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("conversationId", chatId);
        out.put("turnId", nextSeq);
        out.put("seq", nextSeq);
        if (executorId == null) {
            // 运行器离线不等于消息丢失:回合已落库为 PENDING,执行器上线后由
            // replayPending 重投;接口返回成功,前端展示"等待运行器"而不是报错。
            out.put("status", "QUEUED");
            log.info("conversation turn queued (no online executor) chatId={} seq={}", chatId, nextSeq);
            return out;
        }
        Map<String, Object> frame = conversationFrame(chatId, nextSeq, agentId, contentMd, userId);
        boolean sent = hub.send(executorId, frame);
        if (!sent) {
            out.put("status", "QUEUED");
            log.info("conversation turn queued (send failed) chatId={} seq={}", chatId, nextSeq);
            return out;
        }
        markDelivery(chatId, nextSeq, "SENT");
        out.put("status", "SENT");
        log.info("conversation turn sent chatId={} turnId={} executorId={}", chatId, nextSeq, executorId);
        return out;
    }

    /**
     * 执行器上线后重放积压回合(全部智能体共享节点池、最近 20 条 PENDING)。
     * 返回成功投递条数。失败不影响上线流程,下次上线继续重试。
     *
     * <p>帧里的 agentId 必须取会话自己的(c.agent_id),不能取执行器注册名下的
     * agent —— 运行节点是共享池,重放别的数字员工的会话时若带上执行器的
     * agentId,MCP 令牌与人格都会装错人。
     */
    public int replayPending(long executorId) {
        List<Map<String, Object>> pending;
        try {
            pending = jdbc.queryForList(
                    "SELECT t.chat_id, t.seq, t.content_md, c.agent_id FROM ag_chat_turn t"
                            + " JOIN ag_chat c ON c.id=t.chat_id"
                            + " WHERE t.role='USER' AND t.delivery_status='PENDING' AND t.deleted_at IS NULL"
                            + " ORDER BY t.id LIMIT 20");
        } catch (Exception e) {
            log.warn("conversation replay query failed executorId={} error={}", executorId, e.getMessage());
            return 0;
        }
        int sent = 0;
        for (Map<String, Object> row : pending) {
            long chatId = num(row.get("chat_id"));
            int seq = ((Number) row.get("seq")).intValue();
            String content = String.valueOf(row.get("content_md"));
            long chatAgentId = num(row.get("agent_id"));
            try {
                if (hub.send(executorId, conversationFrame(chatId, seq, chatAgentId, content, 0L))) {
                    markDelivery(chatId, seq, "SENT");
                    sent++;
                } else {
                    break;
                }
            } catch (Exception e) {
                log.warn("conversation replay failed chatId={} seq={} error={}", chatId, seq, e.getMessage());
                break;
            }
        }
        if (sent > 0) {
            log.info("conversation replay sent executorId={} count={}", executorId, sent);
        }
        return sent;
    }

    /** 单测可见(public):下发帧里必须带 mcpToken,否则运行器无法注册 ainative MCP、数字员工读不了需求。 */
    public Map<String, Object> conversationFrame(long chatId, int seq, long agentId, String contentMd, long userId) {
        Map<String, Object> frame = new LinkedHashMap<>();
        frame.put("type", "CONVERSATION_TURN");
        frame.put("conversationId", chatId);
        frame.put("turnId", seq);
        frame.put("agentId", agentId);
        frame.put("content", contentMd);
        frame.put("dispatchAttempt", 0);
        frame.put("systemPrompt", systemPrompt(agentId));
        frame.put("mcpToken", clarifyMcpToken(chatId, userId));
        return frame;
    }

    /**
     * 会话级 clarify MCP 令牌:同一 chatId 复用同一枚令牌。
     *
     * <p>明文只在签发那一刻拿得到(库里只有 sha256),所以缓存在内存;服务重启缓存丢失时,
     * 按名字约定 <code>clarify-&lt;chatId&gt;</code> 找到旧令牌撤销后重新签发 ——
     * 保证令牌表里每个会话至多一枚活跃 clarify 令牌,不会无限膨胀。
     * 会话删除时由 {@link #revokeClarifyToken(long)} 撤销。
     */
    public String clarifyMcpToken(long chatId, long userId) {
        String cached = clarifyTokens.get(chatId);
        if (cached != null) {
            return cached;
        }
        long uid = userId > 0 ? userId : chatUserId(chatId);
        String name = "clarify-" + chatId;
        return clarifyTokens.compute(chatId, (id, prev) -> {
            if (prev != null) {
                return prev;
            }
            try {
                agentService.listMcpTokens(uid > 0 ? uid : null).stream()
                        .filter(t -> name.equals(String.valueOf(t.get("name"))))
                        .forEach(t -> agentService.revokeMcpToken(((Number) t.get("id")).longValue()));
                Map<String, Object> token = agentService.createMcpToken(uid > 0 ? uid : 1L, name);
                return String.valueOf(token.get("token"));
            } catch (Exception e) {
                // 签发失败不阻断回合:运行器只是拿不到 MCP 工具,下一轮重试
                log.warn("clarify mcp token issue failed chatId={} error={}", id, e.getMessage());
                return null;
            }
        });
    }

    /** 会话结束(删除)时撤销该会话的 clarify 令牌。 */
    public void revokeClarifyToken(long chatId) {
        String cached = clarifyTokens.remove(chatId);
        if (cached == null) {
            return;
        }
        long uid = chatUserId(chatId);
        String name = "clarify-" + chatId;
        try {
            agentService.listMcpTokens(uid > 0 ? uid : null).stream()
                    .filter(t -> name.equals(String.valueOf(t.get("name"))))
                    .forEach(t -> agentService.revokeMcpToken(((Number) t.get("id")).longValue()));
        } catch (Exception e) {
            log.warn("clarify mcp token revoke failed chatId={} error={}", chatId, e.getMessage());
        }
    }

    private long chatUserId(long chatId) {
        try {
            List<Long> rows = jdbc.queryForList("SELECT user_id FROM ag_chat WHERE id=?", Long.class, chatId);
            return rows.isEmpty() || rows.get(0) == null ? 0L : rows.get(0);
        } catch (Exception e) {
            return 0L;
        }
    }

    private Long onlineExecutorOf(long agentId) {
        List<Long> bound = jdbc.queryForList(
                "SELECT id FROM ag_executor WHERE agent_id=? AND status='ONLINE' AND deleted_at IS NULL ORDER BY id",
                Long.class, agentId);
        List<Long> shared = jdbc.queryForList(
                "SELECT id FROM ag_executor WHERE status='ONLINE' AND deleted_at IS NULL ORDER BY id",
                Long.class);
        return pickExecutor(mergeExecutorCandidates(bound, shared), hub::isOnline, hub::isHealthy);
    }

    /**
     * 候选节点 = 绑定该智能体的执行器优先,其后是其余在线执行器(共享节点池,去重保序)。
     *
     * <p>回归背景:运行节点是机器级共享池 —— daemon 注册在某个 agent 名下,但
     * CONVERSATION_TURN 帧自带 agentId,能替任意数字员工干活。按 agent_id 严格
     * 过滤会让绑定到其他数字员工的澄清会话永远 QUEUED(UI 走查实测:会话绑定
     * 「需求澄清师」而 daemon 注册在 agent 1,服务端日志 conversation turn
     * queued (no online executor),回合无人投递)。
     */
    public static List<Long> mergeExecutorCandidates(List<Long> agentBound, List<Long> allOnline) {
        LinkedHashMap<Long, Boolean> merged = new LinkedHashMap<>();
        for (Long id : agentBound) {
            if (id != null) {
                merged.put(id, Boolean.TRUE);
            }
        }
        for (Long id : allOnline) {
            if (id != null) {
                merged.putIfAbsent(id, Boolean.FALSE);
            }
        }
        return new ArrayList<>(merged.keySet());
    }

    /**
     * 选节点:优先「自检健康(healthy=true)」的在线执行器;只有当没有任何健康节点在线时,
     * 才回退旧逻辑(按 id 升序取第一个在线者)。
     *
     * <p>回归背景:桌面侧会自动拉起低 id 的旧 daemon,其进程环境缺 ANTHROPIC 鉴权,
     * 接到澄清回合只会回复 "Not logged in"。healthy 由 daemon 启动自检后随心跳上报,
     * 不上报该标志的老 daemon 一律视作不健康(宁缺毋滥)。
     */
    public static Long pickExecutor(List<Long> orderedIds, java.util.function.LongPredicate isOnline,
                                    java.util.function.LongPredicate isHealthy) {
        Long fallback = null;
        for (Long id : orderedIds) {
            if (id == null || !isOnline.test(id)) {
                continue;
            }
            if (isHealthy.test(id)) {
                return id;
            }
            if (fallback == null) {
                fallback = id;
            }
        }
        return fallback;
    }

    private void markDelivery(long chatId, int seq, String status) {
        jdbc.update("UPDATE ag_chat_turn SET delivery_status=? WHERE chat_id=? AND seq=? AND role='USER'",
                status, chatId, seq);
    }

    /** 收到运行器 ACK:落 ASSISTANT 回合。 */
    @Transactional
    public void handleAck(Map<String, Object> frame) {
        long chatId = num(frame.get("conversationId"));
        long turnId = num(frame.get("turnId"));
        String status = String.valueOf(frame.getOrDefault("status", "FAILED"));
        String reply = String.valueOf(frame.getOrDefault("replyMarkdown", ""));
        String error = String.valueOf(frame.getOrDefault("error", ""));
        if (chatId == 0 || turnId == 0) {
            return;
        }
        String key = chatId + ":" + turnId;
        if (done.containsKey(key)) {
            log.info("conversation ack replayed chatId={} turnId={}", chatId, turnId);
            return;
        }
        markDelivery(chatId, (int) turnId, "ANSWERED");
        Integer seq = jdbc.queryForObject(
                "SELECT COALESCE(MAX(seq),0)+1 FROM ag_chat_turn WHERE chat_id=?", Integer.class, chatId);
        int replySeq = seq == null ? 1 : seq;
        String content = "SUCCESS".equals(status) ? reply : "对话失败:" + error;
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update(con -> {
            var ps = con.prepareStatement(
                    "INSERT INTO ag_chat_turn (workspace_id, chat_id, seq, role, content_md) VALUES (?,?,?,'ASSISTANT',?)",
                    java.sql.Statement.RETURN_GENERATED_KEYS);
            ps.setLong(1, workspaceId());
            ps.setLong(2, chatId);
            ps.setInt(3, replySeq);
            ps.setString(4, content);
            return ps;
        }, keyHolder);
        Number replyTurnId = keyHolder.getKey();
        if ("SUCCESS".equals(status) && replyTurnId != null) {
            // 澄清会话的产出要进需求产物区:每轮结论归档成产物,交付时可直接引用。
            archive.archive(chatId, replyTurnId.longValue(), replySeq, agentIdOf(chatId), reply);
        }
        Object usage = frame.get("usage");
        if (usage instanceof Map<?, ?> u && u.get("input_tokens") != null) {
            long in = num(u.get("input_tokens"));
            long out = num(u.get("output_tokens"));
            jdbc.update("INSERT INTO ag_chat_usage (workspace_id, chat_id, tokens_in, tokens_out) VALUES (?,?,?,?)",
                    workspaceId(), chatId, in, out);
        }
        Map<String, Object> record = new LinkedHashMap<>();
        record.put("status", status);
        record.put("replyMarkdown", reply);
        done.put(key, record);
        log.info("conversation ack chatId={} turnId={} status={}", chatId, turnId, status);
        // ACK 落库后再推终结事件:前端收到后重新拉详情,拿到的一定是已落库的回复。
        // 必须等事务提交 —— 提前推的话前端重拉读到的是提交前的快照,回复气泡会闪空。
        String state = "SUCCESS".equals(status) ? "completed" : "error";
        if (org.springframework.transaction.support.TransactionSynchronizationManager.isSynchronizationActive()) {
            org.springframework.transaction.support.TransactionSynchronizationManager.registerSynchronization(
                    new org.springframework.transaction.support.TransactionSynchronization() {
                        @Override
                        public void afterCommit() {
                            publishTurnStatus(chatId, turnId, state);
                        }
                    });
        } else {
            publishTurnStatus(chatId, turnId, state);
        }
    }

    /** 会话下发的系统提示词:带数字员工人设(SOUL.md/AGENT.md),不是通用助手口吻。 */
    private String systemPrompt(long agentId) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT role_name, soul_md, agent_md FROM ag_agent WHERE id=?", agentId);
        if (rows.isEmpty()) {
            return Persona.systemPrompt("", "", "");
        }
        Map<String, Object> a = rows.get(0);
        return Persona.systemPrompt((String) a.get("role_name"),
                (String) a.get("soul_md"), (String) a.get("agent_md"));
    }

    private long agentIdOf(long chatId) {
        List<Long> rows = jdbc.queryForList("SELECT agent_id FROM ag_chat WHERE id=?", Long.class, chatId);
        return rows.isEmpty() || rows.get(0) == null ? 0L : rows.get(0);
    }

    private long workspaceId() {
        return com.ainative.v2.common.RequestContext.workspaceId();
    }

    private static long num(Object v) {
        if (v instanceof Number n) {
            return n.longValue();
        }
        try {
            return v == null ? 0 : Long.parseLong(String.valueOf(v));
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}
