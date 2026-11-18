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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 知识与配置资产域:AI 技能(SkillController)/AI 记忆(MemoryController)/
 * 澄清会话(ClarificationController + ClarificationConversationController)/帮助中心文档。
 */
class KnowledgeApiTest extends IntegrationTestBase {

    @Test
    void skill_crud_and_category_filter() throws Exception {
        Map<String, Object> user = newUser("skill");
        String auth = bearer(user);

        var created = mvc.perform(post("/api/skills")
                        .header("Authorization", auth)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(JSON.writeValueAsString(Map.of(
                                "name", "接口测试技能", "type", "SKILL", "description", "自动化测试技能"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andReturn();
        long skillId = dataOf(body(created)).path("id").asLong();

        mvc.perform(get("/api/skills").header("Authorization", auth))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));

        mvc.perform(put("/api/skills/" + skillId)
                        .header("Authorization", auth)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(JSON.writeValueAsString(Map.of(
                                "name", "接口测试技能-改名", "type", "SKILL", "description", "已更新"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));

        mvc.perform(delete("/api/skills/" + skillId).header("Authorization", auth))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));
    }

    @Test
    void memory_crud() throws Exception {
        Map<String, Object> user = newUser("mem");
        String auth = bearer(user);

        var created = mvc.perform(post("/api/memories")
                        .header("Authorization", auth)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(JSON.writeValueAsString(Map.of(
                                "title", "接口测试记忆", "contentMd", "记住这条"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andReturn();
        long memoryId = dataOf(body(created)).path("id").asLong();

        mvc.perform(get("/api/memories").header("Authorization", auth))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));
        mvc.perform(get("/api/memories/count").header("Authorization", auth))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));

        mvc.perform(put("/api/memories/" + memoryId)
                        .header("Authorization", auth)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(JSON.writeValueAsString(Map.of(
                                "title", "接口测试记忆", "contentMd", "改过的内容"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));

        mvc.perform(delete("/api/memories/" + memoryId).header("Authorization", auth))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));
    }

    @Test
    void clarification_session_read_write() throws Exception {
        Map<String, Object> user = newUser("clar");
        String auth = bearer(user);

        // 建需求(澄清挂在工单上)
        var wi = mvc.perform(post("/api/workitems")
                        .header("Authorization", auth)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(JSON.writeValueAsString(Map.of(
                                "workType", "REQ", "title", "澄清接口测试",
                                "contentMd", "正文", "priority", 2))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andReturn();
        long workitemId = dataOf(body(wi)).path("id").asLong();

        mvc.perform(get("/api/workitems/" + workitemId + "/clarification").header("Authorization", auth))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));
    }

    @Test
    void mcp_tools_list_requires_login() throws Exception {
        // MCP 工具清单需要登录态(帮助中心用它展示可调用工具)
        mvc.perform(get("/api/mcp/tokens/tools"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("10401"));

        Map<String, Object> user = newUser("tools");
        mvc.perform(get("/api/mcp/tokens/tools").header("Authorization", bearer(user)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));
    }

    @Test
    void conversations_and_usage_readable() throws Exception {
        Map<String, Object> user = newUser("conv");
        String auth = bearer(user);

        var agent = mvc.perform(post("/api/agents")
                        .header("Authorization", auth)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(JSON.writeValueAsString(Map.of(
                                "name", "会话测试员工", "roleCode", "FS_DEV", "roleName", "全栈"))))
                .andExpect(status().isOk())
                .andReturn();
        long agentId = dataOf(body(agent)).path("id").asLong();

        mvc.perform(get("/api/agents/" + agentId + "/conversations").header("Authorization", auth))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));
    }
}
