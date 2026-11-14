package com.ainative.v2.ex;

import com.ainative.v2.common.ApiResult;
import com.ainative.v2.common.RequestContext;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * TAPD 集成端点(前端 TapdIntegrationPanel 契约):
 * 绑定列表/连接测试/保存/启停;拉取由 ExternalPullScheduler 按 provider 分支自动执行。
 * 凭证:apiUser:apiPassword 存 credential_ref(Basic Auth);baseUrl 存 config_json。
 */
@RestController
@RequestMapping("/api/integrations/tapd")
public class TapdController {
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper = new ObjectMapper();
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(8)).build();

    public TapdController(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public record BindingRequest(String projectId, String apiUser, String apiPassword,
                                 String commentAuthor, String baseUrl, Boolean enabled) {}

    @GetMapping("/bindings")
    public ApiResult<List<Map<String, Object>>> list() {
        List<Long> ids = jdbc.queryForList(
                "SELECT id FROM ex_binding WHERE workspace_id=? AND provider='TAPD' AND deleted_at IS NULL ORDER BY id DESC",
                Long.class, RequestContext.workspaceId());
        List<Map<String, Object>> out = new ArrayList<>();
        for (Long id : ids) {
            out.add(view(id));
        }
        return ApiResult.ok(out);
    }

    @PostMapping("/test")
    public ApiResult<Map<String, Object>> test(@RequestBody BindingRequest req) {
        Map<String, Object> out = new LinkedHashMap<>();
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(
                            (req.baseUrl() == null || req.baseUrl().isBlank() ? "https://api.tapd.cn"
                                    : req.baseUrl().replaceAll("/+$", "")) + "/bugs?workspace_id="
                                    + (req.projectId() == null ? "" : req.projectId())
                                    + "&fields=id&page=1&limit=1"))
                    .timeout(Duration.ofSeconds(8))
                    .header("Authorization", "Basic " + Base64.getEncoder().encodeToString(
                            ((req.apiUser() == null ? "" : req.apiUser()) + ":"
                                    + (req.apiPassword() == null ? "" : req.apiPassword()))
                                    .getBytes(java.nio.charset.StandardCharsets.UTF_8)))
                    .header("Accept", "application/json")
                    .GET().build();
            HttpResponse<String> resp = http.send(request, HttpResponse.BodyHandlers.ofString());
            out.put("success", resp.statusCode() == 200);
            out.put("message", resp.statusCode() == 200 ? "连接成功" : "TAPD 返回 HTTP " + resp.statusCode());
        } catch (Exception e) {
            out.put("success", false);
            out.put("message", "连接失败:" + e.getMessage());
        }
        return ApiResult.ok(out);
    }

    @PostMapping
    public ApiResult<Map<String, Object>> create(@RequestBody BindingRequest req) {
        long ws = RequestContext.workspaceId();
        jdbc.update("INSERT INTO ex_binding (workspace_id, provider, project_id, name, credential_ref, config_json, status)"
                        + " VALUES (?, 'TAPD', ?, ?, ?, ?, 'ACTIVE')",
                ws, req.projectId(), "TAPD 项目 " + req.projectId(),
                (req.apiUser() == null ? "" : req.apiUser()) + ":" + (req.apiPassword() == null ? "" : req.apiPassword()),
                configJson(req));
        Long id = jdbc.queryForObject("SELECT MAX(id) FROM ex_binding WHERE workspace_id=? AND provider='TAPD'", Long.class, ws);
        return ApiResult.ok(view(id));
    }

    @PutMapping("/{id}/enabled")
    public ApiResult<Map<String, Object>> setEnabled(@PathVariable long id, @RequestBody Map<String, Object> body) {
        RequestContext.requireUserId();
        boolean enabled = Boolean.TRUE.equals(body.get("enabled"));
        jdbc.update("UPDATE ex_binding SET status=? WHERE id=?", enabled ? "ACTIVE" : "DISABLED", id);
        return ApiResult.ok(view(id));
    }

    /** 手动触发一次拉取(自动轮询按 pollInterval 也会跑)。 */
    @PostMapping("/{id}/pull")
    public ApiResult<Map<String, Object>> pull(@PathVariable long id,
                                               com.ainative.v2.ex.ExPullService pullService) {
        RequestContext.requireUserId();
        return ApiResult.ok(pullService.pull(id));
    }

    /** 必须传 JSON 文本:传 Map 会被驱动做 Java 序列化,读回时无法解析。 */
    private String configJson(BindingRequest req) {
        try {
            return mapper.writeValueAsString(Map.of(
                    "baseUrl", req.baseUrl() == null ? "https://api.tapd.cn" : req.baseUrl(),
                    "apiUser", req.apiUser() == null ? "" : req.apiUser(),
                    "commentAuthor", req.commentAuthor() == null ? "" : req.commentAuthor()));
        } catch (Exception e) {
            return "{}";
        }
    }

    private Map<String, Object> view(long id) {
        Map<String, Object> raw = jdbc.queryForMap(
                "SELECT id, project_id, name, status, config_json, credential_ref FROM ex_binding WHERE id=?", id);
        String baseUrl = "https://api.tapd.cn";
        try {
            baseUrl = mapper.readTree(String.valueOf(raw.get("config_json"))).path("baseUrl").asText("https://api.tapd.cn");
        } catch (Exception ignored) {
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", id);
        out.put("projectId", String.valueOf(raw.get("project_id")));
        out.put("enabled", "ACTIVE".equals(raw.get("status")));
        out.put("baseUrl", baseUrl);
        out.put("lastSuccessAt", null);
        out.put("syncFailed", false);
        return out;
    }
}
