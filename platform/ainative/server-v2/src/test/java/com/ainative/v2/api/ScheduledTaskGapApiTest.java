package com.ainative.v2.api;

import com.ainative.v2.support.IntegrationTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 定时任务三端点补齐(summary / preview / documents)+ 路由回归锁。
 *
 * 回归背景:GET /api/scheduled-tasks/{id} 曾用无约束 {id} 匹配,把字面量
 * summary/preview 当 id 转 long 失败 → 500。{id} 改为 {id:\d+} 后字面量路由可达。
 */
class ScheduledTaskGapApiTest extends IntegrationTestBase {

    /** 建一个定时任务,返回任务 id(默认 enabled=0,不干扰调度 tick)。 */
    private long createTask(Map<String, Object> user, String name) throws Exception {
        String resp = mvc.perform(post("/api/scheduled-tasks")
                        .header("Authorization", bearer(user))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(JSON.writeValueAsString(Map.of(
                                "name", name,
                                "cronExpr", "0 0 2 * * *",
                                "templateJson", "{\"agentId\":1}"))))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
        if (!JSON.readTree(resp).path("success").asBoolean(false)) {
            throw new IllegalStateException("createTask 失败: " + resp);
        }
        return JSON.readTree(resp).path("data").path("id").asLong();
    }

    @Test
    void summary_returns_five_metrics_and_not_swallowed_by_id_route() throws Exception {
        Map<String, Object> user = adminUser(); // 建任务接口仅管理员;summary 按其工作区统计
        long ws = ((Number) user.get("workspaceId")).longValue();
        long taskId = createTask(user, "汇总-任务");

        // 造运行数据:今天 1 次成功派发、10 天前 1 次失败(均已结束)
        jdbc.update("INSERT INTO pf_task_run (workspace_id, task_id, status, started_at, ended_at, result_json)"
                        + " VALUES (?,?, 'DISPATCHED', NOW(), NOW(), ?)", ws, taskId, "{\"ticketId\":1}");
        jdbc.update("INSERT INTO pf_task_run (workspace_id, task_id, status, started_at, ended_at)"
                        + " VALUES (?,?, 'FAILED', NOW() - INTERVAL 10 DAY, NOW() - INTERVAL 10 DAY)", ws, taskId);

        // 回归锁:summary 必须命中字面量路由返回 200,而不是被 {id} 吞掉转 long 失败成 500
        mvc.perform(get("/api/scheduled-tasks/summary").header("Authorization", bearer(user)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.running").isNumber())
                .andExpect(jsonPath("$.data.today").isNumber())
                .andExpect(jsonPath("$.data.success30d").isNumber())
                .andExpect(jsonPath("$.data.completed30d").isNumber())
                .andExpect(jsonPath("$.data.attention").isNumber())
                .andExpect(jsonPath("$.data.running").value(1))
                .andExpect(jsonPath("$.data.today").value(1))
                .andExpect(jsonPath("$.data.success30d").value(1))
                .andExpect(jsonPath("$.data.completed30d").value(2))
                .andExpect(jsonPath("$.data.attention").value(1));
    }

    @Test
    void preview_valid_cron_returns_future_times() throws Exception {
        Map<String, Object> user = newUser("stprev");
        // Quartz 风格 6 字段(秒 分 时 日 月 周),"?" 语义同 "*"
        mvc.perform(get("/api/scheduled-tasks/preview")
                        .header("Authorization", bearer(user))
                        .param("cronExpression", "0 0 9 * * ?")
                        .param("timezone", "Asia/Shanghai")
                        .param("count", "3"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.length()").value(3))
                .andExpect(jsonPath("$.data[0]").isString())
                .andExpect(jsonPath("$.data[0]").value(org.hamcrest.Matchers.containsString("T09:00")));
    }

    @Test
    void preview_invalid_cron_returns_10001_not_500() throws Exception {
        Map<String, Object> user = newUser("stbad");
        mvc.perform(get("/api/scheduled-tasks/preview")
                        .header("Authorization", bearer(user))
                        .param("cronExpression", "not-a-cron"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.code").value("10001"));
    }

    @Test
    void documents_nonexistent_task_returns_empty_array() throws Exception {
        Map<String, Object> user = newUser("stdoc0");
        mvc.perform(get("/api/scheduled-tasks/999999999/documents").header("Authorization", bearer(user)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data").isArray())
                .andExpect(jsonPath("$.data.length()").value(0));
    }

    @Test
    void documents_returns_artifacts_of_derived_tickets() throws Exception {
        Map<String, Object> user = adminUser(); // 建任务接口仅管理员
        long ws = ((Number) user.get("workspaceId")).longValue();
        long taskId = createTask(user, "产物-任务");

        // 运行 → 建单 → 产物,三张表用 ticket_id 串起来(与调度器真实链路同构)
        jdbc.update("INSERT INTO pf_task_run (workspace_id, task_id, status, started_at, ended_at, result_json)"
                + " VALUES (?,?, 'DISPATCHED', NOW(), NOW(), ?)", ws, taskId, "{\"ticketId\":42}");
        jdbc.update("INSERT INTO as_artifact (workspace_id, ticket_id, run_id, name, kind, size_bytes)"
                + " VALUES (?, 42, NULL, '需求说明.md', 'REQUIREMENT_DOC', 1024)", ws);
        // 干扰项:别的工单的产物不应被带出
        jdbc.update("INSERT INTO as_artifact (workspace_id, ticket_id, run_id, name, kind, size_bytes)"
                + " VALUES (?, 43, NULL, '无关产物.md', 'REQUIREMENT_DOC', 1)", ws);

        String resp = mvc.perform(get("/api/scheduled-tasks/" + taskId + "/documents")
                        .header("Authorization", bearer(user)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data").isArray())
                .andExpect(jsonPath("$.data.length()").value(1))
                .andReturn().getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
        var data = JSON.readTree(resp).path("data");
        assertThat(data.get(0).path("workitemId").asLong()).isEqualTo(42);
        assertThat(data.get(0).path("name").asText()).isEqualTo("需求说明.md");
        assertThat(data.get(0).path("type").asText()).isEqualTo("REQUIREMENT_DOC");
        assertThat(data.get(0).path("size").asLong()).isEqualTo(1024);
    }
}
