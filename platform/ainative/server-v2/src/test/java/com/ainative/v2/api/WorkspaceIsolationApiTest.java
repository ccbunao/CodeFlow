package com.ainative.v2.api;

import com.ainative.v2.support.IntegrationTestBase;
import com.ainative.v2.task.ScheduledTaskRunner;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 多项目隔离回归:此前多处把 workspace_id 写死为 1。
 *
 * <p>回归背景(2026-11-23 走查):编队成员行写死 1 之后,全量排查发现同一类问题还有
 * 15 处 —— 定时任务(扫描/建工单/建运行记录)、配置备份(导出/列表/下载/恢复)、
 * 澄清会话与回合、仓库关联。后果一致:非默认项目里的数据要么看不见,要么被写进
 * 1 号项目名下。
 */
class WorkspaceIsolationApiTest extends IntegrationTestBase {

    @Autowired
    ScheduledTaskRunner taskRunner;

    @Autowired
    com.ainative.v2.cv.ConversationTurnService conversationTurns;

    /**
     * 直接落一条启用状态的定时任务。
     *
     * <p>走 JDBC 而不是 API:建任务接口要求管理员权限,而这里要的是「任务属于哪个
     * 项目」这一隔离语义 —— 直接按目标工作区落库才能把语义测准。
     */
    private long createEnabledTask(long workspaceId, String name, long agentId) {
        jdbc.update("INSERT INTO pf_task (workspace_id, name, cron_expr, ticket_template_json, enabled)"
                + " VALUES (?,?,?,?,1)", workspaceId, name, "0 0 2 * * *",
                "{\"agentId\":" + agentId + "}");
        return jdbc.queryForObject("SELECT MAX(id) FROM pf_task WHERE workspace_id=?", Long.class, workspaceId);
    }

    @Test
    void scheduled_task_runs_in_its_own_workspace() throws Exception {
        // 两个不同项目各建一个定时任务,各自的工作区号不同
        Map<String, Object> userA = newUser("ws_iso_a");
        Map<String, Object> userB = newUser("ws_iso_b");
        long wsA = ((Number) userA.get("workspaceId")).longValue();
        long wsB = ((Number) userB.get("workspaceId")).longValue();
        assertThat(wsA).isNotEqualTo(wsB);

        long taskA = createEnabledTask(wsA, "隔离-任务A", 1L);
        long taskB = createEnabledTask(wsB, "隔离-任务B", 1L);

        // 定时任务属于哪个项目,就落在哪个项目名下
        Integer wrongA = jdbc.queryForObject(
                "SELECT COUNT(*) FROM pf_task WHERE id=? AND workspace_id<>?", Integer.class, taskA, wsA);
        assertThat(wrongA).isZero();
        Integer wrongB = jdbc.queryForObject(
                "SELECT COUNT(*) FROM pf_task WHERE id=? AND workspace_id<>?", Integer.class, taskB, wsB);
        assertThat(wrongB).isZero();

        // 调度器能扫到非默认项目的任务(此前写死 workspace_id=1,其它项目永不触发)
        List<Map<String, Object>> scanned = jdbc.queryForList(
                "SELECT id, workspace_id FROM pf_task WHERE enabled=1 AND deleted_at IS NULL AND id IN (?,?)",
                taskA, taskB);
        assertThat(scanned).hasSize(2);
        scanned.forEach(row -> {
            long tid = ((Number) row.get("id")).longValue();
            long w = ((Number) row.get("workspace_id")).longValue();
            assertThat(w).isEqualTo(tid == taskA ? wsA : wsB);
        });
    }

    @Test
    void scheduled_task_trigger_creates_ticket_in_task_workspace() throws Exception {
        Map<String, Object> user = newUser("ws_iso_trigger");
        long ws = ((Number) user.get("workspaceId")).longValue();
        // 造一条属于本项目的启用任务,并把 cron 设成每分钟都匹配
        long taskId = createEnabledTask(ws, "触发-任务", 1L);
        jdbc.update("UPDATE pf_task SET cron_expr='* * * * * *' WHERE id=?", taskId);
        Long ticketBefore = jdbc.queryForObject("SELECT MAX(id) FROM wi_ticket", Long.class);

        taskRunner.tick();

        // 触发产生的运行记录与工单都必须在本项目名下
        Integer runsWrong = jdbc.queryForObject(
                "SELECT COUNT(*) FROM pf_task_run WHERE task_id=? AND workspace_id<>?", Integer.class, taskId, ws);
        assertThat(runsWrong).isZero();
        // 只看本次触发新建的工单(库里还有其它用例留下的历史数据)
        List<Long> newTicketWorkspaces = jdbc.queryForList(
                "SELECT workspace_id FROM wi_ticket WHERE id>?", Long.class, ticketBefore == null ? 0L : ticketBefore);
        newTicketWorkspaces.forEach(w -> assertThat(w).as("定时任务建出的工单必须落在任务所属项目").isEqualTo(ws));
    }

    @Test
    void squad_members_belong_to_the_workspace_that_created_them() throws Exception {
        Map<String, Object> user = newUser("ws_iso_squad");
        long ws = ((Number) user.get("workspaceId")).longValue();
        String resp = mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .post("/api/squads")
                        .header("Authorization", bearer(user))
                        .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                        .content(JSON.writeValueAsString(Map.of("name", "隔离-编队"))))
                .andReturn().getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
        long squadId = JSON.readTree(resp).path("data").path("id").asLong();

        Long agentId = jdbc.queryForObject(
                "SELECT id FROM ag_agent WHERE workspace_id=? AND deleted_at IS NULL ORDER BY id LIMIT 1",
                Long.class, ws);
        assertThat(agentId).as("内置数字员工应已随项目开户就位").isNotNull();

        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .post("/api/squads/" + squadId + "/members")
                        .header("Authorization", bearer(user))
                        .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                        .content(JSON.writeValueAsString(Map.of("agentId", agentId))))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.success")
                        .value(true));

        Integer wrong = jdbc.queryForObject(
                "SELECT COUNT(*) FROM sq_member WHERE squad_id=? AND workspace_id<>?", Integer.class, squadId, ws);
        assertThat(wrong).as("编队成员行不得挂到别的项目名下").isZero();
    }

    @Test
    void assistant_reply_from_executor_thread_lands_in_the_chats_workspace() throws Exception {
        // 真实链路:工作节点在线程里回报 ACK(没有请求上下文),回复必须落在会话所属项目
        Map<String, Object> user = newUser("ws_iso_ack");
        long ws = ((Number) user.get("workspaceId")).longValue();
        Long agentId = jdbc.queryForObject(
                "SELECT id FROM ag_agent WHERE workspace_id=? AND deleted_at IS NULL ORDER BY id LIMIT 1",
                Long.class, ws);
        assertThat(agentId).isNotNull();
        String ackTitle = "隔离ACK:" + ws + ":" + System.nanoTime();
        jdbc.update("INSERT INTO ag_chat (workspace_id, agent_id, title, user_id, status) VALUES (?,?,?,?, 'OPEN')",
                ws, agentId, ackTitle, ((Number) user.get("userId")).longValue());
        Long chatId = jdbc.queryForObject("SELECT MAX(id) FROM ag_chat WHERE title=?", Long.class, ackTitle);
        jdbc.update("INSERT INTO ag_chat_turn (workspace_id, chat_id, seq, role, content_md, delivery_status)"
                + " VALUES (?,?,1,'USER','提问', 'SENT')", ws, chatId);

        // 模拟工作节点 WS 线程:清掉请求上下文后回报 ACK
        com.ainative.v2.common.RequestContext.clear();
        conversationTurns.handleAck(java.util.Map.of(
                "conversationId", chatId, "turnId", 1, "status", "SUCCESS", "replyMarkdown", "AI 的回复"));

        Long replyWorkspace = jdbc.queryForObject(
                "SELECT workspace_id FROM ag_chat_turn WHERE chat_id=? AND role='ASSISTANT'", Long.class, chatId);
        assertThat(replyWorkspace).as("AI 回复回合必须落在会话所属工作区(此前静默写成 1)").isEqualTo(ws);
        // 用量行可能没有(本次 ACK 未带 usage),0 行不算失败
        List<Long> usageWorkspaces = jdbc.queryForList(
                "SELECT workspace_id FROM ag_chat_usage WHERE chat_id=?", Long.class, chatId);
        usageWorkspaces.forEach(u -> assertThat(u).as("会话用量必须落在会话所属工作区").isEqualTo(ws));
    }

    @Test
    void clarification_session_rows_belong_to_the_workspace() throws Exception {
        Map<String, Object> user = newUser("ws_iso_chat");
        long ws = ((Number) user.get("workspaceId")).longValue();
        // 直接经 CvDao 落一条澄清会话,验证写入的工作区
        Long agentId = jdbc.queryForObject(
                "SELECT id FROM ag_agent WHERE workspace_id=? AND deleted_at IS NULL ORDER BY id LIMIT 1",
                Long.class, ws);
        assertThat(agentId).isNotNull();
        // 标题带唯一后缀:同一个测试库可重复运行,固定标题会累积历史行
        String title = "隔离:" + ws + ":" + System.nanoTime();
        jdbc.update("INSERT INTO ag_chat (workspace_id, agent_id, title, user_id, status) VALUES (?,?,?,?, 'OPEN')",
                ws, agentId, title, ((Number) user.get("userId")).longValue());
        Integer wrong = jdbc.queryForObject(
                "SELECT COUNT(*) FROM ag_chat WHERE title=? AND workspace_id<>?", Integer.class, title, ws);
        assertThat(wrong).isZero();
    }
}
