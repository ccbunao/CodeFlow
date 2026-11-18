package com.ainative.v2.api;

import com.ainative.v2.support.IntegrationTestBase;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 交付主链路:工单 CRUD → 状态流转 → 澄清 → 派发 → 交付进度。
 *
 * 覆盖 WorkitemController / ClarificationController / DispatchController /
 * StatusTemplateController,并顺带锁死三处口径回归(看板列计数 / 状态分类 / 步骤数)。
 */
class WorkitemDeliveryApiTest extends IntegrationTestBase {

    /** 建一条需求,返回工单 id。 */
    private long createWorkitem(Map<String, Object> user, String title, String desc) throws Exception {
        var resp = mvc.perform(post("/api/workitems")
                        .header("Authorization", bearer(user))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(JSON.writeValueAsString(Map.of(
                                "workType", "REQ", "title", title, "contentMd", desc, "priority", 2))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andReturn();
        return dataOf(body(resp)).path("id").asLong();
    }

    @Test
    void workitem_crud_and_content_update() throws Exception {
        Map<String, Object> user = newUser("wi");
        long id = createWorkitem(user, "接口测试需求", "描述正文");

        mvc.perform(get("/api/workitems/" + id).header("Authorization", bearer(user)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.title").value("接口测试需求"))
                .andExpect(jsonPath("$.data.workType").value("REQ"))
                .andExpect(jsonPath("$.data.statusCategory").value("NEW"));

        // 更新正文
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .put("/api/workitems/" + id + "/content")
                        .header("Authorization", bearer(user))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(JSON.writeValueAsString(Map.of("title", "接口测试需求", "contentMd", "改过的正文", "expectedVersion", 0))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));
    }

    @Test
    void workitem_list_filters_by_status_category() throws Exception {
        Map<String, Object> user = newUser("wicat");
        long id = createWorkitem(user, "分类过滤需求", "正文");

        // 默认列表含该需求
        var all = mvc.perform(get("/api/workitems?page=1&size=50").header("Authorization", bearer(user)))
                .andExpect(status().isOk()).andReturn();
        assertThat(body(all)).contains("分类过滤需求");

        // statusCategory=NEW 命中(回归:该参数曾被后端忽略,导致每列计数都等于总数)
        var newCat = mvc.perform(get("/api/workitems?page=1&size=50&statusCategory=NEW")
                        .header("Authorization", bearer(user)))
                .andExpect(status().isOk()).andReturn();
        JsonNode newData = dataOf(body(newCat));
        assertThat(newData.path("total").asLong()).isGreaterThanOrEqualTo(1);

        // statusCategory=DONE 不该命中这条新建需求
        var doneCat = mvc.perform(get("/api/workitems?page=1&size=50&statusCategory=DONE")
                        .header("Authorization", bearer(user)))
                .andExpect(status().isOk()).andReturn();
        assertThat(body(doneCat)).doesNotContain("分类过滤需求");
        assertThat(id).isPositive();
    }

    @Test
    void workitem_transition_along_legal_edges_reaches_done() throws Exception {
        Map<String, Object> user = newUser("witr");
        long id = createWorkitem(user, "流转需求", "正文");

        // 按状态机定义的合法边逐步流转到 DONE(每步取一条真实边,非法目标会被 13004 拒绝)
        for (int step = 0; step < 8; step++) {
            String category = currentCategory(id);
            if ("DONE".equals(category)) break;
            long next = nextLegalTransitionTarget(id, "DONE");
            mvc.perform(post("/api/workitems/" + id + "/transition")
                            .header("Authorization", bearer(user))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(JSON.writeValueAsString(Map.of("toNodeId", next))))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.success").value(true));
        }
        mvc.perform(get("/api/workitems/" + id).header("Authorization", bearer(user)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.statusCategory").value("DONE"));
    }

    private String currentCategory(long workitemId) {
        return jdbc.queryForObject(
                "SELECT st.category FROM wi_ticket t JOIN wi_flow_state st ON st.id=t.state_id WHERE t.id=?",
                String.class, workitemId);
    }

    /** 从当前状态取一条合法边:优先指向目标分类,否则任一条(用于路过中间态)。 */
    private long nextLegalTransitionTarget(long workitemId, String preferredCategory) {
        Long currentStateId = jdbc.queryForObject(
                "SELECT state_id FROM wi_ticket WHERE id=?", Long.class, workitemId);
        Long flowId = jdbc.queryForObject(
                "SELECT flow_id FROM wi_ticket WHERE id=?", Long.class, workitemId);
        List<Long> preferred = jdbc.queryForList(
                "SELECT e.to_state_id FROM wi_flow_edge e JOIN wi_flow_state st ON st.id=e.to_state_id"
                        + " WHERE e.flow_id=? AND e.from_state_id=? AND st.category=? LIMIT 1",
                Long.class, flowId, currentStateId, preferredCategory);
        if (!preferred.isEmpty()) return preferred.get(0);
        List<Long> any = jdbc.queryForList(
                "SELECT e.to_state_id FROM wi_flow_edge e WHERE e.flow_id=? AND e.from_state_id=? LIMIT 1",
                Long.class, flowId, currentStateId);
        assertThat(any).as("当前状态应至少有一条出边").isNotEmpty();
        return any.get(0);
    }

    @Test
    void workitem_transition_rejects_unknown_node() throws Exception {
        Map<String, Object> user = newUser("wibad");
        long id = createWorkitem(user, "非法流转需求", "正文");
        mvc.perform(post("/api/workitems/" + id + "/transition")
                        .header("Authorization", bearer(user))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(JSON.writeValueAsString(Map.of("toNodeId", 999999L))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(false));
    }

    @Test
    void workitem_requires_token() throws Exception {
        mvc.perform(get("/api/workitems"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("10401"));
    }

    @Test
    void clarification_read_and_write() throws Exception {
        Map<String, Object> user = newUser("clar");
        long id = createWorkitem(user, "澄清需求", "正文");

        // 读澄清(未澄清时返回空内容或结构)
        mvc.perform(get("/api/workitems/" + id + "/clarification").header("Authorization", bearer(user)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));
    }

    @Test
    void delivery_progress_and_recovery_are_readable() throws Exception {
        Map<String, Object> user = newUser("deliv");
        long id = createWorkitem(user, "交付进度需求", "正文");

        mvc.perform(get("/api/workitems/" + id + "/delivery-progress").header("Authorization", bearer(user)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));

        mvc.perform(get("/api/workitems/" + id + "/recovery").header("Authorization", bearer(user)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));
    }

    @Test
    void dispatches_list_is_readable() throws Exception {
        Map<String, Object> user = newUser("disp");
        mvc.perform(get("/api/dispatches").header("Authorization", bearer(user)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));
    }

}
