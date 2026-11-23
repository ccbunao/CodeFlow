package com.ainative.v2.api;

import com.ainative.v2.support.IntegrationTestBase;
import com.fasterxml.jackson.databind.JsonNode;
import com.ainative.v2.ws.BuiltinRosterProvisioner;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.List;
import java.util.Map;

import static com.ainative.v2.support.IntegrationTestBase.JSON;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 内置数字员工/编队 + 澄清会话前端契约(方向、回复中状态)的接口级回归。
 *
 * <p>回归背景(2026-11-23 走查):
 * <ul>
 *   <li>新建项目里数字员工和编队是空的 —— 只有一份脚本往 1 号项目手工灌过;</li>
 *   <li>会话详情把库里的角色(USER/ASSISTANT)直接当 direction 返回,前端只认 IN/INBOUND,
 *       用户发的消息被画成左侧的 AI 气泡;</li>
 *   <li>processingStatus 写死 null,前端永远判不出「AI 正在回复」,没有思考中状态。</li>
 * </ul>
 */
class BuiltinRosterAndConversationContractApiTest extends IntegrationTestBase {

    @Autowired
    BuiltinRosterProvisioner roster;

    /** 接口返回的 data 节点。 */
    private JsonNode data(org.springframework.test.web.servlet.MvcResult r) throws Exception {
        return dataOf(body(r));
    }

    @Test
    void new_workspace_comes_with_full_builtin_roster() throws Exception {
        var user = newUser("roster");
        String auth = bearer(user);

        var agents = mvc.perform(get("/api/agents").header("Authorization", auth))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode agentList = data(agents);
        assertThat(agentList.size()).isGreaterThanOrEqualTo(BuiltinRosterProvisioner.ROLES.size());
        for (BuiltinRosterProvisioner.Role role : BuiltinRosterProvisioner.ROLES) {
            List<JsonNode> matched = new java.util.ArrayList<>();
            agentList.forEach(a -> { if (role.code().equals(a.path("roleCode").asText())) matched.add(a); });
            assertThat(matched).as("内置员工 %s(%s) 必须恰好一个", role.name(), role.code()).hasSize(1);
            assertThat(matched.get(0).path("name").asText()).isEqualTo(role.name());
        }

        var squads = mvc.perform(get("/api/squads").header("Authorization", auth))
                .andExpect(status().isOk()).andReturn();
        JsonNode squadList = data(squads);
        List<String> squadNames = new java.util.ArrayList<>();
        squadList.forEach(s -> squadNames.add(s.path("name").asText()));
        assertThat(squadNames).containsAll(BuiltinRosterProvisioner.SQUADS.stream().map(BuiltinRosterProvisioner.Squad::name).toList());

        var sdlcs = mvc.perform(get("/api/sdlcs").header("Authorization", auth))
                .andExpect(status().isOk()).andReturn();
        List<String> sdlcNames = new java.util.ArrayList<>();
        data(sdlcs).forEach(s -> sdlcNames.add(s.path("name").asText()));
        assertThat(sdlcNames).contains("标准交付流程", "全链路交付流程");
    }

    @Test
    void builtin_squad_members_are_real_and_in_this_workspace() throws Exception {
        var user = newUser("roster_members");
        String auth = bearer(user);
        long workspaceId = ((Number) user.get("workspaceId")).longValue();

        JsonNode squadList = data(mvc.perform(get("/api/squads").header("Authorization", auth)).andReturn());
        long clarifySquadId = 0;
        for (JsonNode s : squadList) {
            if ("需求澄清编队".equals(s.path("name").asText())) clarifySquadId = s.path("id").asLong();
        }
        assertThat(clarifySquadId).as("需求澄清编队必须存在").isPositive();

        JsonNode members = data(mvc.perform(
                get("/api/squads/" + clarifySquadId + "/members").header("Authorization", auth)).andReturn());
        List<String> names = new java.util.ArrayList<>();
        members.forEach(m -> names.add(m.path("agentName").asText(m.path("name").asText())));
        assertThat(names).containsExactly("需求澄清师", "产品经理", "架构师");

        // 成员行必须挂在当前项目下(此前 addMember 把 workspace_id 写死成 1)
        Integer wrongWorkspace = jdbc.queryForObject(
                "SELECT COUNT(*) FROM sq_member WHERE squad_id=? AND workspace_id<>?",
                Integer.class, clarifySquadId, workspaceId);
        assertThat(wrongWorkspace).isZero();
    }

    @Test
    void roster_is_idempotent_and_respects_user_deletion() throws Exception {
        var user = newUser("roster_idem");
        long workspaceId = ((Number) user.get("workspaceId")).longValue();
        Integer agentsBefore = jdbc.queryForObject(
                "SELECT COUNT(*) FROM ag_agent WHERE workspace_id=? AND deleted_at IS NULL", Integer.class, workspaceId);
        Integer squadsBefore = jdbc.queryForObject(
                "SELECT COUNT(*) FROM sq_squad WHERE workspace_id=? AND deleted_at IS NULL", Integer.class, workspaceId);

        assertThat(roster.ensure(workspaceId)).as("第二次开户不应新建任何资产").isZero();
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM ag_agent WHERE workspace_id=? AND deleted_at IS NULL", Integer.class, workspaceId))
                .isEqualTo(agentsBefore);
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM sq_squad WHERE workspace_id=? AND deleted_at IS NULL", Integer.class, workspaceId))
                .isEqualTo(squadsBefore);

        // 用户删掉一个内置员工:再次开户不得把它复活
        jdbc.update("UPDATE ag_agent SET deleted_at=NOW() WHERE workspace_id=? AND role_code='SRE'", workspaceId);
        roster.ensure(workspaceId);
        Integer sreAlive = jdbc.queryForObject(
                "SELECT COUNT(*) FROM ag_agent WHERE workspace_id=? AND role_code='SRE' AND deleted_at IS NULL",
                Integer.class, workspaceId);
        assertThat(sreAlive).isZero();
    }

    @Test
    void every_builtin_squad_and_flow_only_references_roles_that_exist() {
        // 定义自洽:编队/流程引用的角色码必须都在内置名单里,否则开户后会出现缺成员的编队
        for (BuiltinRosterProvisioner.Squad squad : BuiltinRosterProvisioner.SQUADS) {
            assertThat(BuiltinRosterProvisioner.roleCodes()).containsAll(squad.members());
        }
        for (BuiltinRosterProvisioner.Flow flow : BuiltinRosterProvisioner.FLOWS) {
            for (BuiltinRosterProvisioner.Step step : flow.steps()) {
                assertThat(BuiltinRosterProvisioner.roleCodes()).contains(step.roleCode());
            }
        }
        // 角色码唯一,判重才可靠
        assertThat(BuiltinRosterProvisioner.roleCodes()).doesNotHaveDuplicates();
    }

    private long createWorkitem(Map<String, Object> user, String title) throws Exception {
        var resp = mvc.perform(post("/api/workitems")
                        .header("Authorization", bearer(user))
                        .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                        .content(JSON.writeValueAsString(Map.of(
                                "workType", "REQ", "title", title, "contentMd", "澄清契约回归", "priority", 2))))
                .andExpect(status().isOk())
                .andReturn();
        return data(resp).path("id").asLong();
    }

    @Test
    void conversation_turns_use_in_out_direction_and_report_processing() throws Exception {
        var user = newUser("clarify_contract");
        String auth = bearer(user);
        long workitemId = createWorkitem(user, "澄清契约");

        JsonNode created = data(mvc.perform(post("/api/workitems/" + workitemId + "/clarification-conversations")
                .header("Authorization", auth)
                .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                .content("{\"agentId\":null}")).andExpect(status().isOk()).andReturn());
        long chatId = created.path("id").asLong();

        mvc.perform(post("/api/workitems/" + workitemId + "/clarification-conversations/" + chatId + "/turns")
                        .header("Authorization", auth)
                        .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                        .content("{\"content\":\"帮我澄清这个需求\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));

        JsonNode detail = data(mvc.perform(get(
                "/api/workitems/" + workitemId + "/clarification-conversations/" + chatId)
                .header("Authorization", auth)).andExpect(status().isOk()).andReturn());

        assertThat(detail.path("turns")).hasSize(1);
        JsonNode userTurn = detail.path("turns").get(0);
        // 用户消息必须是 IN(前端据此靠右显示);此前返回的是库里的 USER,被画成了 AI 气泡
        assertThat(userTurn.path("direction").asText()).isEqualTo("IN");
        // 没有执行器应答 → 回复中(前端据此显示「思考中」并轮询),不能是 null
        assertThat(detail.path("processingStatus").asText()).isEqualTo("PROCESSING");
        assertThat(detail.path("processingTurnId").asLong()).isEqualTo(userTurn.path("id").asLong());
    }

    @Test
    void answered_turn_clears_processing_status() throws Exception {
        var user = newUser("clarify_done");
        String auth = bearer(user);
        long workitemId = createWorkitem(user, "澄清已应答");
        long chatId = data(mvc.perform(post("/api/workitems/" + workitemId + "/clarification-conversations")
                .header("Authorization", auth).contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                .content("{\"agentId\":null}")).andExpect(status().isOk()).andReturn()).path("id").asLong();
        mvc.perform(post("/api/workitems/" + workitemId + "/clarification-conversations/" + chatId + "/turns")
                .header("Authorization", auth).contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                .content("{\"content\":\"你好\"}")).andExpect(status().isOk());

        // 模拟执行器应答落库:最后一条回合变成 ASSISTANT → 不再是回复中
        jdbc.update("UPDATE ag_chat_turn SET delivery_status='ANSWERED' WHERE chat_id=? AND role='USER'", chatId);
        jdbc.update("INSERT INTO ag_chat_turn (workspace_id, chat_id, seq, role, content_md) VALUES (?,?,2,'ASSISTANT','你好,请问…')",
                ((Number) user.get("workspaceId")).longValue(), chatId);

        JsonNode detail = data(mvc.perform(get(
                "/api/workitems/" + workitemId + "/clarification-conversations/" + chatId)
                .header("Authorization", auth)).andExpect(status().isOk()).andReturn());
        assertThat(detail.path("turns")).hasSize(2);
        assertThat(detail.path("turns").get(1).path("direction").asText()).isEqualTo("OUT");
        assertThat(detail.path("processingStatus").isNull() || detail.path("processingStatus").asText().equals("null"))
                .as("已应答后不应再处于回复中").isTrue();
    }

}
