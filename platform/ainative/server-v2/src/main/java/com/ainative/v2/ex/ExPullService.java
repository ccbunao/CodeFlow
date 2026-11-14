package com.ainative.v2.ex;

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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 外部平台拉取同步:按绑定配置 GET 远端条目并落成工作项(幂等:同 external_id 不重复)。
 * 禅道族接口约定:GET {baseUrl}/api.php/v1/bugs?product={projectId},
 * Header Token: {credential}。返回数组或 {data:[...]},条目 {id,title,description,status}。
 */
@Service
public class ExPullService {
    private static long ws() {
        return com.ainative.v2.common.RequestContext.workspaceId();
    }

    private static final Logger log = LoggerFactory.getLogger(ExPullService.class);
    private final ExDao dao;
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper = new ObjectMapper();
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();

    public ExPullService(ExDao dao, JdbcTemplate jdbc) {
        this.dao = dao;
        this.jdbc = jdbc;
    }

    @Transactional
    public Map<String, Object> pull(long bindingId) {
        ExDao.Binding binding = dao.findBinding(bindingId).orElseThrow(() -> new RuntimeException("绑定不存在"));
        // 拉取可能由调度线程触发(无登录态,RequestContext 回落默认工作区),
        // 所有写操作必须以绑定自身的工作区为准,否则需求会落到错误项目。
        long bindingWorkspace = binding.workspaceId();
        String baseUrl = binding.configJson() == null ? null : extractBaseUrl(binding.configJson());
        if (baseUrl == null || baseUrl.isBlank()) {
            throw new RuntimeException("绑定缺少 baseUrl 配置");
        }
        // provider 分支:禅道(Token 头 + /api.php/v1)与 TAPD(Basic Auth + data 包装)形态不同
        String provider = providerOf(bindingId);
        JsonNode payload;
        if ("TAPD".equals(provider)) {
            // 真实 TAPD 契约:GET /bugs?workspace_id=&fields=&order=&page=&limit=
            // 响应 {"status":1,"data":[{"Bug":{...}}]} —— 条目按实体名嵌套,必须解包
            String url = baseUrl.replaceAll("/+$", "") + "/bugs"
                    + "?workspace_id=" + binding.projectId()
                    + "&fields=id,title,description,status,priority,reporter,current_owner,created,modified"
                    + "&order=modified%20desc&page=1&limit=" + TAPD_PAGE_LIMIT;
            payload = fetchBasic(url, credentialOf(bindingId));
        } else {
            String url = baseUrl.replaceAll("/+$", "") + "/api.php/v1/bugs?product=" + binding.projectId();
            payload = fetch(url, credentialOf(bindingId));
        }
        List<JsonNode> items = itemsOf(payload);
        int imported = 0;
        int skipped = 0;
        for (JsonNode item : items) {
            String externalId = item.path("id").asText("");
            if (externalId.isBlank()) {
                continue;
            }
            List<Long> existing = jdbc.queryForList(
                    "SELECT ticket_id FROM ex_item_link WHERE binding_id=? AND external_id=?",
                    Long.class, bindingId, externalId);
            if (!existing.isEmpty()) {
                skipped++;
                continue;
            }
            // Bug 用 title,Story 用 name;都缺时退回带 ID 的占位标题
            String title = item.path("title").asText("");
            if (title.isBlank()) {
                title = item.path("name").asText("");
            }
            if (title.isBlank()) {
                title = "外部条目 " + externalId;
            }
            String body = item.path("description").asText("");
            Long stateId = mappedState(bindingId, item.path("status").asText(""), bindingWorkspace);
            Long flowId = mappedFlow(bindingId, bindingWorkspace);
            Long ticketId = insertTicket(title, body, "ZENTAO", externalId, flowId, stateId, bindingWorkspace);
            jdbc.update("INSERT INTO ex_item_link (workspace_id, binding_id, external_id, ticket_id) VALUES (" + bindingWorkspace + ",?,?,?)",
                    bindingId, externalId, ticketId);
            jdbc.update("INSERT INTO wi_journal (workspace_id, ticket_id, event_type, actor_type, from_val, to_val) VALUES (" + bindingWorkspace + ",?, 'EXTERNAL_IMPORT', 'SYSTEM', NULL, ?)",
                    ticketId, externalId);
            imported++;
        }
        log.info("external pull bindingId={} imported={} skipped={}", bindingId, imported, skipped);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("bindingId", bindingId);
        out.put("imported", imported);
        out.put("skipped", skipped);
        out.put("total", items.size());
        return out;
    }

    private String providerOf(long bindingId) {
        List<String> rows = jdbc.queryForList("SELECT provider FROM ex_binding WHERE id=?", String.class, bindingId);
        return rows.isEmpty() ? "ZENTAO" : rows.get(0);
    }

    private String extractBaseUrl(String configJson) {
        try {
            JsonNode node = mapper.readTree(configJson);
            return node.path("baseUrl").asText(null);
        } catch (Exception e) {
            return null;
        }
    }

    private String credentialOf(long bindingId) {
        List<String> rows = jdbc.queryForList("SELECT credential_ref FROM ex_binding WHERE id=?", String.class, bindingId);
        return rows.isEmpty() || rows.get(0) == null ? "" : rows.get(0);
    }

    /** TAPD 形态:Basic Auth(clientKey:accessSecret)。credential_ref 存 "user:secret"。 */
    private JsonNode fetchBasic(String url, String credential) {
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofSeconds(15))
                    .header("Authorization", "Basic "
                            + java.util.Base64.getEncoder().encodeToString(
                                    credential.getBytes(java.nio.charset.StandardCharsets.UTF_8)))
                    .header("Accept", "application/json")
                    .GET().build();
            HttpResponse<String> resp = http.send(request, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() / 100 != 2) {
                throw new RuntimeException("外部平台返回 HTTP " + resp.statusCode());
            }
            return mapper.readTree(resp.body());
        } catch (java.io.IOException | InterruptedException e) {
            throw new RuntimeException("外部平台请求失败:" + e.getMessage(), e);
        }
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

    private static final int TAPD_PAGE_LIMIT = 50;

    /**
     * 解析外部条目。
     * 禅道:{data:[{...}]};TAPD:{status:1,data:[{"Bug":{...}}]} —— 条目按实体名
     * (Bug/Story)嵌套,此处统一解包成扁平条目,后续字段读取对两种 provider 一致。
     */
    private static List<JsonNode> itemsOf(JsonNode payload) {
        List<JsonNode> items = new ArrayList<>();
        JsonNode array = payload.isArray() ? payload : payload.path("data");
        if (array.isArray()) {
            array.forEach(row -> {
                JsonNode nested = row.path("Bug");
                if (nested.isMissingNode() || nested.isNull()) {
                    nested = row.path("Story");
                }
                items.add(nested.isMissingNode() || nested.isNull() ? row : nested);
            });
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
                    "SELECT s.id FROM wi_flow_state s JOIN wi_flow f ON f.id=s.flow_id"
                            + " WHERE s.workspace_id=" + workspaceId + " AND s.category='NEW' AND f.deleted_at IS NULL"
                            + " ORDER BY s.sort_no LIMIT 1", Long.class);
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
                "SELECT id FROM wi_flow WHERE workspace_id=" + workspaceId + " AND deleted_at IS NULL ORDER BY is_default DESC, id LIMIT 1", Long.class);
        return fallback.isEmpty() ? null : fallback.get(0);
    }

    private Long insertTicket(String title, String body, String source, String externalId, Long flowId, Long stateId, long workspaceId) {
        org.springframework.jdbc.support.KeyHolder keyHolder = new org.springframework.jdbc.support.GeneratedKeyHolder();
        jdbc.update(con -> {
            java.sql.PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO wi_ticket (workspace_id, title, body_md, type, priority, flow_id, state_id, external_source, external_id, version) VALUES (" + workspaceId + ",?,?, 'REQ', 2,?,?,?,?, 0)",
                    java.sql.PreparedStatement.RETURN_GENERATED_KEYS);
            ps.setString(1, title);
            ps.setString(2, body);
            if (flowId == null) ps.setNull(3, java.sql.Types.BIGINT); else ps.setLong(3, flowId);
            if (stateId == null) ps.setNull(4, java.sql.Types.BIGINT); else ps.setLong(4, stateId);
            ps.setString(5, source);
            ps.setString(6, externalId);
            return ps;
        }, keyHolder);
        Number key = keyHolder.getKey();
        return key == null ? 0 : key.longValue();
    }
}
