package com.ainative.v2.ex;

import com.ainative.integration.common.ExternalProjectBindingDO;
import com.ainative.integration.common.ExternalProjectBindingDao;
import com.ainative.integration.dto.SyncNowResult;
import com.ainative.integration.provider.ExternalPlatformConfig;
import com.ainative.integration.tapd.TapdWorkitemProvider;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 外部平台拉取同步:按绑定配置从远端拉条目并落成工作项(幂等:同 external_id 不重复)。
 *
 * <p>两类 provider:
 * <ul>
 *   <li>ZENTAO:GET {baseUrl}/api.php/v1/bugs?product={projectId},Token 头,
 *       条目 {id,title,description,status};仅新增。</li>
 *   <li>TAPD:同事 Alan Bao 的 v1 模块(com.ainative.integration.tapd)原样合入,
 *       拉取与入库都走他的 provider;external_id={workspace}:{bug|story}:{id},
 *       已存在则按远端更新,返回 imported+updated。</li>
 * </ul>
 *
 * <p>调度线程无登录态,所有写入一律以绑定自身 workspace 为准。
 */
@Service
public class ExPullService {
    private static final Logger log = LoggerFactory.getLogger(ExPullService.class);

    private final ExDao dao;
    private final JdbcTemplate jdbc;
    private final ExternalProjectBindingDao bindingDao;
    private final TapdWorkitemProvider tapdProvider;
    private final TapdInboundSyncService tapdInbound;
    private final ObjectMapper mapper = new ObjectMapper();
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();

    public ExPullService(ExDao dao, JdbcTemplate jdbc, ExternalProjectBindingDao bindingDao,
                         TapdWorkitemProvider tapdProvider, TapdInboundSyncService tapdInbound) {
        this.dao = dao;
        this.jdbc = jdbc;
        this.bindingDao = bindingDao;
        this.tapdProvider = tapdProvider;
        this.tapdInbound = tapdInbound;
    }

    @Transactional
    public Map<String, Object> pull(long bindingId) {
        ExDao.Binding binding = dao.findBinding(bindingId).orElseThrow(() -> new RuntimeException("绑定不存在"));
        // 拉取可能由调度线程触发(无登录态,RequestContext 回落默认工作区),
        // 所有写操作必须以绑定自身的工作区为准,否则需求会落到错误项目。
        long bindingWorkspace = binding.workspaceId();
        if (TapdWorkitemProvider.PROVIDER.equalsIgnoreCase(binding.provider())) {
            return pullTapd(binding, bindingWorkspace);
        }
        return pullZentao(binding, bindingWorkspace);
    }

    // ---------------------------------------------------------------- 禅道

    private Map<String, Object> pullZentao(ExDao.Binding binding, long bindingWorkspace) {
        long bindingId = binding.id();
        String baseUrl = binding.configJson() == null ? null : extractText(binding.configJson(), "baseUrl");
        if (baseUrl == null || baseUrl.isBlank()) {
            throw new RuntimeException("绑定缺少 baseUrl 配置");
        }
        String url = baseUrl.replaceAll("/+$", "") + "/api.php/v1/bugs?product=" + binding.projectId();
        JsonNode payload = fetch(url, credentialOf(bindingId));
        List<JsonNode> items = itemsOf(payload);
        int imported = 0;
        int skipped = 0;
        for (JsonNode item : items) {
            String externalId = item.path("id").asText("");
            if (externalId.isBlank()) {
                continue;
            }
            if (linkedTicket(bindingId, externalId) != null) {
                skipped++;
                continue;
            }
            String title = item.path("title").asText("外部条目 " + externalId);
            String body = item.path("description").asText("");
            Long stateId = mappedState(bindingId, item.path("status").asText(""), bindingWorkspace);
            Long flowId = mappedFlow(bindingId, bindingWorkspace);
            Long ticketId = insertTicket(title, body, "REQ", 2, "ZENTAO", externalId, flowId, stateId, bindingWorkspace);
            insertLink(bindingId, externalId, ticketId, bindingWorkspace);
            journal(bindingWorkspace, ticketId, "EXTERNAL_IMPORT", externalId);
            imported++;
        }
        Map<String, Object> out = summary(bindingId, imported, 0, skipped, items.size());
        recordImport(bindingWorkspace, bindingId, imported, 0, items.size());
        log.info("external pull zentao bindingId={} imported={} skipped={}", bindingId, imported, skipped);
        return out;
    }

    // ---------------------------------------------------------------- TAPD

    /**
     * TAPD 拉取:完全走同事的 v1 provider(分页/窗口/字段映射都在他的代码里),
     * 入库交给 v2 适配的 TapdInboundSyncService。
     */
    private Map<String, Object> pullTapd(ExDao.Binding binding, long bindingWorkspace) {
        long bindingId = binding.id();
        ExternalProjectBindingDO tapdBinding = bindingDao.findById(bindingId);
        if (tapdBinding == null) {
            throw new RuntimeException("TAPD 绑定不存在");
        }
        ExternalPlatformConfig config = tapdInbound.configOf(tapdBinding);
        Date last = tapdBinding.getLastSuccessAt();
        // v1 语义:上次成功时间回退 60s 再拉,防边界漏单
        Date since = last == null ? null : new Date(last.getTime() - 60_000);
        var page = tapdProvider.searchProject(config, tapdBinding.getExternalProjectId(), since, null);
        SyncNowResult synced = tapdInbound.syncWorkitems(tapdBinding, page.getItems(), 0L);
        bindingDao.markSyncSuccess(bindingId, bindingWorkspace, new Date());
        int imported = synced.getImported();
        int updated = synced.getUpdated();
        int total = page.getItems() == null ? 0 : page.getItems().size();
        Map<String, Object> out = summary(bindingId, imported, updated, 0, total);
        recordImport(bindingWorkspace, bindingId, imported, updated, total);
        log.info("external pull tapd bindingId={} imported={} updated={}", bindingId, imported, updated);
        return out;
    }

    // ---------------------------------------------------------------- 公共

    private Long linkedTicket(long bindingId, String externalId) {
        List<Long> existing = jdbc.queryForList(
                "SELECT ticket_id FROM ex_item_link WHERE binding_id=? AND external_id=?",
                Long.class, bindingId, externalId);
        return existing.isEmpty() ? null : existing.get(0);
    }

    private void insertLink(long bindingId, String externalId, long ticketId, long workspaceId) {
        jdbc.update("INSERT INTO ex_item_link (workspace_id, binding_id, external_id, ticket_id) VALUES (?,?,?,?)",
                workspaceId, bindingId, externalId, ticketId);
    }

    private void journal(long workspaceId, long ticketId, String eventType, String externalId) {
        jdbc.update("INSERT INTO wi_journal (workspace_id, ticket_id, event_type, actor_type, from_val, to_val)"
                        + " VALUES (?,?,?, 'SYSTEM', NULL, ?)",
                workspaceId, ticketId, eventType, externalId);
    }

    /** 记录本轮拉取,供调度器按分钟判重 + 连接页展示同步结果。 */
    private void recordImport(long workspaceId, long bindingId, int imported, int updated, int total) {
        try {
            jdbc.update("INSERT INTO ex_import_record (workspace_id, binding_id, imported, detail_json) VALUES (?,?,?,?)",
                    workspaceId, bindingId, imported,
                    mapper.writeValueAsString(Map.of("imported", imported, "updated", updated, "total", total)));
        } catch (Exception e) {
            log.warn("record external import failed binding={} error={}", bindingId, e.getMessage());
        }
    }

    private Map<String, Object> summary(long bindingId, int imported, int updated, int skipped, int total) {        Map<String, Object> out = new LinkedHashMap<>();
        out.put("bindingId", bindingId);
        out.put("imported", imported);
        out.put("updated", updated);
        out.put("skipped", skipped);
        out.put("total", total);
        return out;
    }

    private JsonNode readJson(String configJson) {
        try {
            JsonNode node = mapper.readTree(configJson == null ? "{}" : configJson);
            return node == null ? mapper.createObjectNode() : node;
        } catch (Exception e) {
            return mapper.createObjectNode();
        }
    }

    private static String text(JsonNode node, String field, String fallback) {
        String value = node.path(field).asText(null);
        return value == null || value.isBlank() ? fallback : value;
    }

    private String extractText(String configJson, String field) {
        return text(readJson(configJson), field, null);
    }

    private static Integer toInt(Object value) {
        return value instanceof Number n ? n.intValue() : null;
    }

    private String credentialOf(long bindingId) {
        List<String> rows = jdbc.queryForList("SELECT credential_ref FROM ex_binding WHERE id=?", String.class, bindingId);
        return rows.isEmpty() || rows.get(0) == null ? "" : rows.get(0);
    }

    private JsonNode fetch(String url, String token) {
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofSeconds(15))
                    .header("Token", token)
                    .header("Accept", "application/json")
                    .GET()
                    .build();
            HttpResponse<String> resp = http.send(request, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() / 100 != 2) {
                throw new RuntimeException("外部平台返回 HTTP " + resp.statusCode());
            }
            return mapper.readTree(resp.body());
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException("外部平台请求失败: " + e.getMessage(), e);
        }
    }

    private static List<JsonNode> itemsOf(JsonNode payload) {
        List<JsonNode> items = new ArrayList<>();
        JsonNode array = payload.isArray() ? payload : payload.path("data");
        if (array.isArray()) {
            array.forEach(items::add);
        } else if (array.has("items") && array.get("items").isArray()) {
            array.get("items").forEach(items::add);
        }
        return items;
    }

    private Long mappedState(long bindingId, String externalStatus, long workspaceId) {
        List<Long> rows = jdbc.queryForList(
                "SELECT state_id FROM ex_status_map WHERE binding_id=? AND external_status=?",
                Long.class, bindingId, externalStatus);
        if (rows.isEmpty()) {
            List<Long> fallback = jdbc.queryForList(
                    "SELECT s.id FROM wi_flow_state s WHERE s.workspace_id=" + workspaceId
                            + " AND s.category='NEW' ORDER BY s.sort_no LIMIT 1", Long.class);
            return fallback.isEmpty() ? null : fallback.get(0);
        }
        return rows.get(0);
    }

    private Long mappedFlow(long bindingId, long workspaceId) {
        List<Long> rows = jdbc.queryForList(
                "SELECT f.id FROM wi_flow f JOIN ex_status_map m ON m.state_id IN (SELECT id FROM wi_flow_state WHERE flow_id=f.id) WHERE m.binding_id=? LIMIT 1",
                Long.class, bindingId);
        if (!rows.isEmpty()) {
            return rows.get(0);
        }
        List<Long> fallback = jdbc.queryForList(
                "SELECT id FROM wi_flow WHERE workspace_id=" + workspaceId
                        + " AND deleted_at IS NULL ORDER BY is_default DESC, id LIMIT 1", Long.class);
        return fallback.isEmpty() ? null : fallback.get(0);
    }

    private Long insertTicket(String title, String body, String type, int priority, String source,
                              String externalId, Long flowId, Long stateId, long workspaceId) {
        org.springframework.jdbc.support.KeyHolder keyHolder = new org.springframework.jdbc.support.GeneratedKeyHolder();
        jdbc.update(con -> {
            java.sql.PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO wi_ticket (workspace_id, title, body_md, type, priority, flow_id, state_id, external_source, external_id, version) VALUES (" + workspaceId + ",?,?,?,?,?,?,?,?, 0)",
                    java.sql.PreparedStatement.RETURN_GENERATED_KEYS);
            ps.setString(1, title);
            ps.setString(2, body);
            ps.setString(3, type);
            ps.setInt(4, priority);
            if (flowId == null) ps.setNull(5, java.sql.Types.BIGINT); else ps.setLong(5, flowId);
            if (stateId == null) ps.setNull(6, java.sql.Types.BIGINT); else ps.setLong(6, stateId);
            ps.setString(7, source);
            ps.setString(8, externalId);
            return ps;
        }, keyHolder);
        Number key = keyHolder.getKey();
        return key == null ? 0 : key.longValue();
    }
}
