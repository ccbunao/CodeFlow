package com.ainative.v2.dispatch;

import com.ainative.integration.common.ExternalProjectBindingDO;
import com.ainative.integration.common.ExternalProjectBindingDao;
import com.ainative.integration.provider.ExternalComment;
import com.ainative.integration.provider.ExternalPlatformConfig;
import com.ainative.integration.provider.ExternalPlatformException;
import com.ainative.integration.tapd.TapdWorkitemProvider;
import com.ainative.v2.common.ApiResult;
import com.ainative.v2.common.RequestContext;
import com.ainative.v2.common.BizException;
import com.ainative.v2.ex.TapdInboundSyncService;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 运行器回写交付备注(数字员工结论):执行器令牌鉴权(免 Bearer),
 * 备注落 wi_note,并触发事件供外部平台回写。
 */
@RestController
@RequestMapping("/api/daemon/dispatches")
public class DaemonCommentController {
    private static final String JSON_BODY_PREFIX = "{\"content\":";
    private static final String JSON_BODY_SUFFIX = "}";
    private static final Logger log = LoggerFactory.getLogger(DaemonCommentController.class);
    private final JdbcTemplate jdbc;
    private final ExternalProjectBindingDao bindingDao;
    private final TapdWorkitemProvider tapdProvider;
    private final TapdInboundSyncService tapdInbound;
    private final com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();

    public DaemonCommentController(JdbcTemplate jdbc, ExternalProjectBindingDao bindingDao,
                                    TapdWorkitemProvider tapdProvider, TapdInboundSyncService tapdInbound) {
        this.jdbc = jdbc;
        this.bindingDao = bindingDao;
        this.tapdProvider = tapdProvider;
        this.tapdInbound = tapdInbound;
    }

    @PostMapping("/{dispatchId}/comments")
    public ApiResult<Map<String, Object>> comment(@PathVariable long dispatchId,
                                                 @RequestParam(name = "token", required = false) String token,
                                                 @RequestBody Map<String, Object> body) {
        if (!executorTokenMatches(dispatchId, token)) {
            throw new BizException("10403", "执行器令牌无效");
        }
        String content = body.get("contentMd") == null ? "" : String.valueOf(body.get("contentMd"));
        Long ticketId = ticketIdOf(dispatchId);
        if (ticketId == null) {
            throw new BizException("14004", "派发不存在");
        }
        long agentId = agentIdOf(dispatchId);
        // 工作区以 rt_run 记录为准(daemon 通道无 Bearer,请求上下文会回落到默认工作区)
        long workspaceId = workspaceOf(dispatchId);
        jdbc.update("INSERT INTO wi_note (workspace_id, ticket_id, author_type, author_id, body_md) VALUES (?,?, 'AGENT', ?, ?)",
                workspaceId, ticketId, agentId, content);
        jdbc.update("INSERT INTO wi_journal (workspace_id, ticket_id, event_type, actor_type, actor_id, to_val) VALUES (?,?, 'COMMENT', 'AGENT', ?, ?)",
                workspaceId, ticketId, agentId, content.length() > 80 ? content.substring(0, 80) : content);
        writebackToExternal(dispatchId, ticketId, workspaceId, content);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("dispatchId", dispatchId);
        out.put("ticketId", ticketId);
        out.put("agentId", agentId);
        return ApiResult.ok(out);
    }

    private Long ticketIdOf(long dispatchId) {
        try {
            return jdbc.queryForObject("SELECT ticket_id FROM rt_run WHERE id=?", Long.class, dispatchId);
        } catch (Exception e) {
            return null;
        }
    }

    private Long agentIdOf(long dispatchId) {
        try {
            return jdbc.queryForObject("SELECT agent_id FROM rt_run WHERE id=?", Long.class, dispatchId);
        } catch (Exception e) {
            return 0L;
        }
    }

    /**
     * 执行器令牌鉴权:库里存的是 sha256(base64url),运行器传明文。
     *
     * <p>身份以令牌本身为准(令牌 → 执行器),再校验该执行器有权回写这条运行:
     * 运行记录的智能体与执行器绑定的智能体一致,或运行明确记录了该执行器。
     * 之所以不直接按 rt_run.executor_id 关联 —— 该字段是派发时的快照,
     * 执行器轮换后会与真正握有令牌的执行器不一致,导致合法回写被误拒。
     */
    private boolean executorTokenMatches(long dispatchId, String token) {
        if (token == null || token.isBlank()) {
            return false;
        }
        try {
            List<Map<String, Object>> executors = jdbc.queryForList(
                    "SELECT id, agent_id FROM ag_executor WHERE token_hash=? AND deleted_at IS NULL",
                    com.ainative.v2.daemon.TokenHash.sha256(token));
            if (executors.isEmpty()) {
                return false;
            }
            long executorId = num(executors.get(0).get("id"));
            long executorAgent = num(executors.get(0).get("agent_id"));
            List<Map<String, Object>> runs = jdbc.queryForList(
                    "SELECT agent_id, executor_id FROM rt_run WHERE id=?", dispatchId);
            if (runs.isEmpty()) {
                return false;
            }
            long runAgent = num(runs.get(0).get("agent_id"));
            long runExecutor = num(runs.get(0).get("executor_id"));
            return runAgent == executorAgent || runExecutor == executorId;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * 多源回写:工单挂在外部绑定(禅道/TAPD 等)上时,把交付备注推送回源平台。
     * 走异步线程,失败只记录不阻断回执 —— 与 v1 的 outbox 语义一致(最终一致)。
     */
    private void writebackToExternal(long dispatchId, long ticketId, long workspaceId, String content) {
        Thread.startVirtualThread(() -> {
            try {
                List<Map<String, Object>> links = jdbc.queryForList(
                        "SELECT l.binding_id, l.external_id, b.provider, b.project_id, b.config_json, b.credential_ref"
                                + " FROM ex_item_link l JOIN ex_binding b ON b.id=l.binding_id"
                                + " WHERE l.ticket_id=? AND l.deleted_at IS NULL", ticketId);
                if (links.isEmpty()) {
                    return;
                }
                Map<String, Object> link = links.get(0);
                String provider = String.valueOf(link.get("provider"));
                String externalId = String.valueOf(link.get("external_id"));
                long bindingId = ((Number) link.get("binding_id")).longValue();
                String outcome;
                if ("TAPD".equalsIgnoreCase(provider)) {
                    // TAPD:走同事的 provider(createComment → form 提交,entry_type=bug|stories)
                    // marker 形态必须与他的正则一致:<!-- an-op:<24位hex> -->(括号是分组语法,非字面量)
                    String marker = "<!-- an-op:" + UUID.randomUUID().toString().replace("-", "").substring(0, 24) + " -->";
                    String body = "[CodeFlow 交付备注] " + content + " " + marker;
                    outcome = pushTapd(bindingId, externalId, body, marker, workspaceId, ticketId);
                } else {
                    outcome = pushZentao(link, externalId, "[CodeFlow 交付备注] " + content);
                }
                // ex_comment_link 无 binding_id 列:绑定与外部单号并入 external_comment_id 留痕
                jdbc.update("INSERT INTO ex_comment_link (workspace_id, ticket_id, note_id, external_comment_id) VALUES (?,?,?,?)",
                        workspaceId, ticketId, 0, "BIND-" + bindingId + "|EXT-" + externalId + "|" + outcome);
                log.info("external writeback dispatch={} ticket={} provider={} outcome={}",
                        dispatchId, ticketId, provider, outcome);
            } catch (Exception e) {
                log.warn("external writeback failed ticket={} error={}", ticketId, e.getMessage());
            }
        });
    }

    /**
     * TAPD 回写:同事的 provider 负责 form 提交与 Comment id 校验;
     * 结果未知(5xx)时落 outbox,交给他的 TapdCommentReadback 读回确认。
     */
    private String pushTapd(long bindingId, String externalId, String body, String marker,
                            long workspaceId, long ticketId) {
        try {
            ExternalProjectBindingDO binding = bindingDao.findById(bindingId);
            if (binding == null) {
                return "FAILED-BINDING";
            }
            ExternalPlatformConfig config = tapdInbound.configOf(binding);
            String author = binding.getWritebackStaffId();
            ExternalComment comment = tapdProvider.createComment(config, externalId, author, body);
            return "PUSHED-" + comment.getExternalId();
        } catch (ExternalPlatformException e) {
            if (!e.isTerminal()) {
                // 结果未知:可能已落库,先记 outbox,读回确认后再决定是否补发
                recordUnknownWriteback(workspaceId, ticketId, bindingId, externalId, marker, e.getMessage());
                return "UNKNOWN-" + abbreviate(e.getMessage());
            }
            return "FAILED-" + abbreviate(e.getMessage());
        }
    }

    /** 结果未知的回写落 outbox,等待 TapdCommentReadback 读回确认。 */
    private void recordUnknownWriteback(long workspaceId, long ticketId, long bindingId,
                                        String externalId, String marker, String error) {
        try {
            String payload = mapper.writeValueAsString(Map.of(
                    "marker", marker,
                    "externalWorkitemId", externalId,
                    "ticketId", String.valueOf(ticketId),
                    "lastError", error == null ? "" : error));
            jdbc.update("INSERT INTO rt_outbox (workspace_id, aggregate_type, aggregate_id, event_type,"
                            + " payload_json, status, retry_count) VALUES (?, 'TAPD_BINDING', ?, 'COMMENT_CREATE', ?, 'PENDING', 0)",
                    workspaceId, bindingId, payload);
        } catch (Exception e) {
            log.warn("record tapd outbox failed ticket={} error={}", ticketId, e.getMessage());
        }
    }

    /** 禅道回写:POST {baseUrl}/api.php/v1/bugs/{id}/comments,Token 头,JSON 体。 */
    private String pushZentao(Map<String, Object> link, String externalId, String body) {
        String baseUrl = "";
        try {
            baseUrl = mapper.readTree(String.valueOf(link.get("config_json"))).path("baseUrl").asText("");
        } catch (Exception ignored) {
        }
        if (baseUrl.isBlank()) {
            return "SKIPPED-NO-BASEURL";
        }
        try {
            java.net.http.HttpRequest request = java.net.http.HttpRequest.newBuilder(URI.create(
                            baseUrl.replaceAll("/+$", "") + "/api.php/v1/bugs/" + externalId + "/comments"))
                    .timeout(Duration.ofSeconds(10))
                    .header("Content-Type", "application/json")
                    .header("Token", String.valueOf(link.get("credential_ref")))
                    .POST(java.net.http.HttpRequest.BodyPublishers.ofString(
                            JSON_BODY_PREFIX + mapper.writeValueAsString(body) + JSON_BODY_SUFFIX))
                    .build();
            var resp = java.net.http.HttpClient.newHttpClient()
                    .send(request, java.net.http.HttpResponse.BodyHandlers.ofString());
            return resp.statusCode() / 100 == 2 ? "PUSHED-" + resp.statusCode() : "FAILED-" + resp.statusCode();
        } catch (Exception e) {
            return "UNKNOWN-" + abbreviate(e.getMessage());
        }
    }

    private static String abbreviate(String value) {
        String text = value == null ? "" : value;
        return text.length() > 60 ? text.substring(0, 60) : text;
    }

    private long workspaceOf(long dispatchId) {
        try {
            Long ws = jdbc.queryForObject("SELECT workspace_id FROM rt_run WHERE id=?", Long.class, dispatchId);
            return ws == null ? RequestContext.workspaceId() : ws;
        } catch (Exception e) {
            return RequestContext.workspaceId();
        }
    }

    private long num(Object v) {
        return v instanceof Number n ? n.longValue() : 0L;
    }
}
