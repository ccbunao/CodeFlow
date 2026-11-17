package com.ainative.v2.statemachine;

import com.ainative.v2.common.ApiResult;
import com.ainative.v2.common.BizException;
import com.ainative.v2.common.RequestContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 状态机模板只读接口(挂载在 wi_flow* 上);编辑能力随平台运维域补齐。 */
@RestController
@RequestMapping("/api/status-templates")
public class StatusTemplateController {
    private static long ws() {
        return com.ainative.v2.common.RequestContext.workspaceId();
    }

    private final JdbcTemplate jdbc;

    public StatusTemplateController(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 状态模板按需求类型分域(REQ/TASK/BUG):前端三个页签各看各的。 */
    @GetMapping
    public ApiResult<List<Map<String, Object>>> list(@RequestParam(required = false) String workType) {
        RequestContext.requireUserId();
        if (workType == null || workType.isBlank()) {
            return ApiResult.ok(jdbc.queryForList(
                    "SELECT id, name, work_type AS workType, is_default AS isDefault, status FROM wi_flow WHERE workspace_id=" + ws() + " AND deleted_at IS NULL ORDER BY is_default DESC, id"));
        }
        return ApiResult.ok(jdbc.queryForList(
                "SELECT id, name, work_type AS workType, is_default AS isDefault, status FROM wi_flow WHERE workspace_id=" + ws()
                        + " AND work_type=? AND deleted_at IS NULL ORDER BY is_default DESC, id", workType.trim().toUpperCase()));
    }

    @GetMapping("/{id}")
    public ApiResult<Map<String, Object>> detail(@PathVariable long id) {
        RequestContext.requireUserId();
        List<Map<String, Object>> flows = jdbc.queryForList(
                "SELECT id, name, is_default AS isDefault, status FROM wi_flow WHERE id=? AND deleted_at IS NULL", id);
        if (flows.isEmpty()) {
            throw new BizException("13003", "状态模板不存在");
        }
        Map<String, Object> out = new LinkedHashMap<>(flows.get(0));
        out.put("nodes", jdbc.queryForList(
                "SELECT id, name, category, sort_no AS sortNo FROM wi_flow_state WHERE flow_id=? ORDER BY sort_no", id));
        out.put("transitions", jdbc.queryForList(
                "SELECT from_state_id AS fromNodeId, to_state_id AS toNodeId FROM wi_flow_edge WHERE flow_id=?", id));
        return ApiResult.ok(out);
    }
}
