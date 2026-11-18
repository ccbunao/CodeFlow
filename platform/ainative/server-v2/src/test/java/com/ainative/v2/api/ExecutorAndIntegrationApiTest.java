package com.ainative.v2.api;

import com.ainative.v2.support.IntegrationTestBase;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 运行节点与外部连接域:
 * - 运行节点(ExecutorController / ExecutorOpsController):注册、心跳、启动命令、配置读写
 * - 外部集成(ExternalIntegrationController / TapdIntegrationController / ZentaoController)
 * - MCP 令牌(McpTokenController):创建只展示一次完整 URL 的契约
 */
class ExecutorAndIntegrationApiTest extends IntegrationTestBase {

    @Test
    void executor_register_heartbeat_and_launch_config() throws Exception {
        Map<String, Object> user = newUser("exec");
        String auth = bearer(user);

        // 先建一个数字员工(运行节点归属它)
        var agent = mvc.perform(post("/api/agents")
                        .header("Authorization", auth)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(JSON.writeValueAsString(Map.of(
                                "name", "运行节点归属员工", "roleCode", "FS_DEV", "roleName", "全栈"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andReturn();
        long agentId = dataOf(body(agent)).path("id").asLong();

        var created = mvc.perform(post("/api/agents/" + agentId + "/executors")
                        .header("Authorization", auth)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(JSON.writeValueAsString(Map.of(
                                "name", "接口测试运行节点", "clientKind", "CLAUDE_CODE"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andReturn();
        JsonNode data = dataOf(body(created));
        long executorId = data.path("id").asLong();
        // 令牌只在创建时返回一次
        assertThat(data.has("token")).as("创建时应返回一次性令牌").isTrue();

        // 列表与详情
        mvc.perform(get("/api/executors").header("Authorization", auth))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));
        mvc.perform(get("/api/executors/" + executorId).header("Authorization", auth))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));

        // 心跳
        mvc.perform(post("/api/executors/" + executorId + "/heartbeat")
                        .header("Authorization", auth)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(JSON.writeValueAsString(Map.of("status", "ONLINE"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));

        // 启动配置读(launch-config)——回归:曾因 model_policy 列缺失整接口 500
        mvc.perform(get("/api/executors/" + executorId + "/launch-config").header("Authorization", auth))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));

        // 启动配置写:memoryMode 落库并回读(回归:曾只写流水不落表,前端永远判"未配置")
        var put = mvc.perform(put("/api/executors/" + executorId + "/launch-config")
                        .header("Authorization", auth)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(JSON.writeValueAsString(Map.of(
                                "maxTasks", 5, "heartbeatSec", 30, "logLevel", "info",
                                "model", "auto", "memoryMode", "platform"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andReturn();
        assertThat(body(put)).as("保存后应回读 memoryMode").contains("memoryMode");

        // 生成启动命令(POST)
        var cmd = mvc.perform(post("/api/executors/" + executorId + "/launch-command")
                        .header("Authorization", auth)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(JSON.writeValueAsString(Map.of("os", "posix", "mode", "service"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andReturn();
        assertThat(body(cmd)).as("启动命令应含 connect 参数").contains("connect");
    }

    @Test
    void external_integration_bindings_readable() throws Exception {
        Map<String, Object> user = newUser("ext");
        String auth = bearer(user);
        // 外部绑定列表(未绑定时返回空列表)
        mvc.perform(get("/api/integrations/zentao/bindings").header("Authorization", auth))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));
        mvc.perform(get("/api/integrations/tapd/bindings").header("Authorization", auth))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));
    }

    @Test
    void mcp_token_created_once_and_listed() throws Exception {
        Map<String, Object> user = newUser("mcp");
        String auth = bearer(user);

        var created = mvc.perform(post("/api/mcp/tokens")
                        .header("Authorization", auth)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(JSON.writeValueAsString(Map.of("name", "接口测试令牌"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andReturn();
        JsonNode data = dataOf(body(created));
        assertThat(data.has("token") || data.has("url")).as("创建时应返回一次性完整地址").isTrue();

        // 列表里只有前缀,没有完整令牌
        var list = mvc.perform(get("/api/mcp/tokens").header("Authorization", auth))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andReturn();
        String listBody = body(list);
        assertThat(listBody).as("列表不应回显完整令牌").doesNotContain(data.path("token").asText(""));
    }

    @Test
    void scheduled_task_runs_readable() throws Exception {
        Map<String, Object> user = newUser("sched");
        mvc.perform(get("/api/scheduled-task-runs").header("Authorization", bearer(user)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));
    }

    @Test
    void dashboard_overview_counts_match_workitems() throws Exception {
        // 数字口径回归:dashboard 的 byLifecycle 合计必须等于工单总数
        Map<String, Object> user = newUser("dash");
        String auth = bearer(user);

        var items = mvc.perform(get("/api/workitems?page=1&size=100").header("Authorization", auth))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode list = dataOf(body(items));
        long total = list.path("total").asLong();

        var overview = mvc.perform(get("/api/dashboard/overview").header("Authorization", auth))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode inv = dataOf(body(overview)).path("inventory").path("byLifecycle");
        long[] sum = {0L};
        inv.elements().forEachRemaining(v -> sum[0] += v.asLong());
        assertThat(sum[0]).as("dashboard byLifecycle 合计应等于工单总数").isEqualTo(total);
    }
}
