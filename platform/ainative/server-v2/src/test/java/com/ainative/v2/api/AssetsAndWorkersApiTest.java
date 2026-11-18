package com.ainative.v2.api;

import com.ainative.v2.support.IntegrationTestBase;
import static org.assertj.core.api.Assertions.assertThat;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

import java.util.Map;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 配置资产与执行域:仓库(AssetController)/AI 数字员工(AgentController)/
 * 交付流程(SdlcController,含步骤数回归)/编队(SquadController)。
 */
class AssetsAndWorkersApiTest extends IntegrationTestBase {

    @Test
    void repo_crud_and_conclusion() throws Exception {
        Map<String, Object> user = newUser("repo");
        String auth = bearer(user);
        // URL 带唯一后缀:仓库唯一键是 (workspace_id, url),重复用例/重跑都不撞键
        String repoUrl = "https://git.example.com/demo-" + System.nanoTime() + ".git";

        var created = mvc.perform(post("/api/repos")
                        .header("Authorization", auth)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(JSON.writeValueAsString(Map.of(
                                "name", "接口测试仓库",
                                "url", repoUrl,
                                "provider", "gitlab",
                                "defaultBranch", "main"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andReturn();
        long repoId = dataOf(body(created)).path("id").asLong();

        mvc.perform(get("/api/repos").header("Authorization", auth))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));

        mvc.perform(put("/api/repos/" + repoId)
                        .header("Authorization", auth)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(JSON.writeValueAsString(Map.of(
                                "name", "接口测试仓库-改名",
                                "url", repoUrl,
                                "provider", "gitlab",
                                "defaultBranch", "main"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));

        mvc.perform(delete("/api/repos/" + repoId).header("Authorization", auth))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));
    }

    @Test
    void agent_create_list_delete() throws Exception {
        Map<String, Object> user = newUser("agent");
        String auth = bearer(user);

        var created = mvc.perform(post("/api/agents")
                        .header("Authorization", auth)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(JSON.writeValueAsString(Map.of(
                                "name", "接口测试数字员工",
                                "roleCode", "FS_DEV",
                                "roleName", "前后端全栈开发",
                                "soulMd", "负责接口测试"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andReturn();
        long agentId = dataOf(body(created)).path("id").asLong();

        mvc.perform(get("/api/agents").header("Authorization", auth))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));

        mvc.perform(get("/api/agents/" + agentId).header("Authorization", auth))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.name").value("接口测试数字员工"));

        mvc.perform(delete("/api/agents/" + agentId).header("Authorization", auth))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));
    }

    @Test
    void sdlc_create_then_stepCount_matches_steps() throws Exception {
        // 回归锁:templateShape 曾用不存在的 steps key 把 service 算好的 stepCount 覆盖成 0。
        // 这里自建模板 + 两步,断言 /sdlcs/page 返回的 stepCount 与步骤数一致。
        Map<String, Object> user = newUser("sdlc");
        String auth = bearer(user);

        var created = mvc.perform(post("/api/sdlcs")
                        .header("Authorization", auth)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(JSON.writeValueAsString(Map.of(
                                "name", "接口测试交付流程",
                                "description", "两步验证"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andReturn();
        long sdlcId = dataOf(body(created)).path("id").asLong();

        for (String stepName : new String[]{"第一步", "第二步"}) {
            mvc.perform(post("/api/sdlcs/" + sdlcId + "/steps")
                            .header("Authorization", auth)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(JSON.writeValueAsString(Map.of("name", stepName, "kind", "WORK"))))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.success").value(true));
        }

        var page = mvc.perform(get("/api/sdlcs/page?page=1&size=50").header("Authorization", auth))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andReturn();
        JsonNode data = dataOf(body(page));
        JsonNode list = data.path("list");
        boolean matched = false;
        for (JsonNode row : list) {
            if (row.path("id").asLong() == sdlcId) {
                assertThat(row.path("stepCount").asInt()).as("自建两步模板的 stepCount").isEqualTo(2);
                matched = true;
            }
        }
        assertThat(matched).as("列表应包含自建模板").isTrue();
    }

    @Test
    void squad_create_list_delete() throws Exception {
        Map<String, Object> user = newUser("squad");
        String auth = bearer(user);

        var created = mvc.perform(post("/api/squads")
                        .header("Authorization", auth)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(JSON.writeValueAsString(Map.of("name", "接口测试编队"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andReturn();
        long squadId = dataOf(body(created)).path("id").asLong();

        mvc.perform(get("/api/squads").header("Authorization", auth))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));

        mvc.perform(delete("/api/squads/" + squadId).header("Authorization", auth))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));
    }
}
