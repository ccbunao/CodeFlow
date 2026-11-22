package com.ainative.v2.pf;

import com.ainative.v2.common.ApiResult;
import com.ainative.v2.common.BizException;
import com.ainative.v2.common.RequestContext;
import com.ainative.v2.task.CronExpression;
import org.springframework.jdbc.core.JdbcTemplate;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 定时任务运维:立即执行 / 健康 / 运行历史(挂在 /api/scheduled-tasks/{id} 下)。
 * 文档管理(md_ 文档引用)与多轮工单派生在文档域落地后接入。
 */
@Tag(name = "执行与自动化", description = "定时任务触发、运行记录与健康度")
@RestController
public class ScheduledTaskOpsController {
    private final JdbcTemplate jdbc;
    private final com.ainative.v2.task.ScheduledTaskRunner runner;

    public ScheduledTaskOpsController(JdbcTemplate jdbc, com.ainative.v2.task.ScheduledTaskRunner runner) {
        this.jdbc = jdbc;
        this.runner = runner;
    }

    private long ws() {
        return RequestContext.workspaceId();
    }

    private void require(long id) {
        if (jdbc.queryForList("SELECT 1 FROM pf_task WHERE id=? AND workspace_id=? AND deleted_at IS NULL",
                id, ws()).isEmpty()) {
            throw new BizException("21001", "定时任务不存在");
        }
    }

    // ---------------------------------------------------------------- 汇总 / 预览 / 产物

    /**
     * 定时任务汇总指标。口径(以 pf_task / pf_task_run 现有列为准):
     * running=启用中任务数(pf_task.enabled=1);today=今日触发的运行数(按库端当天);
     * success30d=近 30 天成功派发的运行数(status='DISPATCHED',即成功建单并进入派发链路);
     * completed30d=近 30 天已结束的运行数(ended_at 非空,含成败);
     * attention=近 30 天失败运行数 + 超时未处理(仍 RUNNING 且已超 1 小时)。
     */
    @GetMapping("/api/scheduled-tasks/summary")
    @Operation(summary = "定时任务·汇总指标(running/today/success30d/completed30d/attention)。")
    public ApiResult<Map<String, Object>> summary(
            @RequestParam(required = false) String status,
            @RequestParam(required = false) Long squadId,
            @RequestParam(required = false) String keyword) {
        RequestContext.requireUserId();
        // 任务侧过滤:status ACTIVE→启用 / PAUSED→停用;keyword→名称模糊;
        // squadId 在 pf_task 无对应列,预留参数暂不参与过滤。
        StringBuilder taskWhere = new StringBuilder(" WHERE workspace_id=? AND deleted_at IS NULL");
        List<Object> taskArgs = new ArrayList<>();
        taskArgs.add(ws());
        if ("ACTIVE".equalsIgnoreCase(status)) {
            taskWhere.append(" AND enabled=1");
        } else if ("PAUSED".equalsIgnoreCase(status)) {
            taskWhere.append(" AND enabled=0");
        }
        if (keyword != null && !keyword.isBlank()) {
            taskWhere.append(" AND name LIKE ?");
            taskArgs.add("%" + keyword.trim() + "%");
        }
        Long running = jdbc.queryForObject("SELECT COUNT(*) FROM pf_task" + taskWhere, Long.class, taskArgs.toArray());
        Long today = jdbc.queryForObject(
                "SELECT COUNT(*) FROM pf_task_run WHERE workspace_id=? AND deleted_at IS NULL AND started_at >= CURDATE()",
                Long.class, ws());
        Long success30d = jdbc.queryForObject(
                "SELECT COUNT(*) FROM pf_task_run WHERE workspace_id=? AND deleted_at IS NULL"
                        + " AND status='DISPATCHED' AND started_at >= NOW() - INTERVAL 30 DAY",
                Long.class, ws());
        Long completed30d = jdbc.queryForObject(
                "SELECT COUNT(*) FROM pf_task_run WHERE workspace_id=? AND deleted_at IS NULL"
                        + " AND ended_at IS NOT NULL AND started_at >= NOW() - INTERVAL 30 DAY",
                Long.class, ws());
        Long attention = jdbc.queryForObject(
                "SELECT COUNT(*) FROM pf_task_run WHERE workspace_id=? AND deleted_at IS NULL"
                        + " AND ((status='FAILED' AND started_at >= NOW() - INTERVAL 30 DAY)"
                        + " OR (status='RUNNING' AND started_at < NOW() - INTERVAL 1 HOUR))",
                Long.class, ws());
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("running", running == null ? 0 : running);
        out.put("today", today == null ? 0 : today);
        out.put("success30d", success30d == null ? 0 : success30d);
        out.put("completed30d", completed30d == null ? 0 : completed30d);
        out.put("attention", attention == null ? 0 : attention);
        return ApiResult.ok(out);
    }

    /**
     * cron 预览:复用调度器同款 CronExpression 解析(与 ScheduledTaskRunner.tick 完全一致),
     * 在指定时区逐分钟推进,返回未来 count 次触发时间(ISO-8601 带时区偏移)。
     * 非法 cron/时区返回 10001 业务错误而非 500。
     */
    @GetMapping("/api/scheduled-tasks/preview")
    @Operation(summary = "定时任务·cron 预览(未来 N 次触发时间)。")
    public ApiResult<List<String>> preview(
            @RequestParam String cronExpression,
            @RequestParam(defaultValue = "Asia/Shanghai") String timezone,
            @RequestParam(defaultValue = "5") int count) {
        RequestContext.requireUserId();
        ZoneId zone;
        try {
            zone = ZoneId.of(timezone);
        } catch (Exception e) {
            throw new BizException("10001", "时区不合法: " + timezone);
        }
        CronExpression cron;
        try {
            cron = CronExpression.parse(cronExpression);
        } catch (Exception e) {
            throw new BizException("10001", "cron 表达式不合法: " + e.getMessage());
        }
        int n = Math.max(1, Math.min(count, 50));
        List<String> out = new ArrayList<>();
        // 从当前分钟的下一分钟起逐分钟匹配(与调度 tick 的分钟粒度一致),上限一年防死循环。
        ZonedDateTime t = ZonedDateTime.now(zone).truncatedTo(ChronoUnit.MINUTES);
        ZonedDateTime horizon = t.plusDays(366);
        while (out.size() < n && t.isBefore(horizon)) {
            t = t.plusMinutes(1);
            if (cron.matches(t.toLocalDateTime())) {
                out.add(t.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME));
            }
        }
        return ApiResult.ok(out);
    }

    /**
     * 定时任务·派生产物:该任务历次运行(pf_task_run.result_json.ticketId)所建工单
     * 关联的 as_artifact,字段形状与前端 Artifact 类型对齐(workitemId/dispatchId/name/type/size/gmtCreate)。
     * 任务不存在或无产物均返回空数组(200)。
     */
    @GetMapping("/api/scheduled-tasks/{id:\\d+}/documents")
    @Operation(summary = "定时任务·派生产物(历次运行建单关联的产物)。")
    public ApiResult<List<Map<String, Object>>> documents(@PathVariable long id) {
        RequestContext.requireUserId();
        return ApiResult.ok(jdbc.queryForList(
                "SELECT a.id, a.ticket_id AS workitemId, a.run_id AS dispatchId, a.name, a.kind AS type,"
                        + " a.size_bytes AS size, a.gmt_create AS gmtCreate"
                        + " FROM as_artifact a WHERE a.deleted_at IS NULL AND a.workspace_id=?"
                        + " AND a.ticket_id IN (SELECT CAST(JSON_UNQUOTE(JSON_EXTRACT(r.result_json,'$.ticketId')) AS UNSIGNED)"
                        + "   FROM pf_task_run r WHERE r.task_id=? AND r.workspace_id=? AND r.deleted_at IS NULL)"
                        + " ORDER BY a.id DESC", ws(), id, ws()));
    }

    // ---------------------------------------------------------------- 任务级运维

    /** 立即触发一次(无视 cron),复用与调度器完全相同的建单派发链路。 */
    @PostMapping("/api/scheduled-tasks/{id:\\d+}/run-now")
    @Operation(summary = "立即触发一次(无视 cron),复用与调度器完全相同的建单派发链路。")
    public ApiResult<Map<String, Object>> runNow(@PathVariable long id) {
        RequestContext.requireUserId();
        require(id);
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT name, ticket_template_json FROM pf_task WHERE id=?", id);
        String name = String.valueOf(rows.get(0).get("name"));
        Object template = rows.get(0).get("ticket_template_json");
        Map<String, Object> out = runner.triggerNow(id, name,
                template == null ? "{}" : String.valueOf(template));
        return ApiResult.ok(out);
    }

    @GetMapping("/api/scheduled-tasks/{id:\\d+}/runs")
    @Operation(summary = "定时任务·运行记录。")
    public ApiResult<List<Map<String, Object>>> runs(@PathVariable long id,
                                                     @RequestParam(defaultValue = "20") int limit) {
        RequestContext.requireUserId();
        require(id);
        int n = Math.max(1, Math.min(limit, 100));
        return ApiResult.ok(jdbc.queryForList(
                "SELECT id, status, started_at AS startedAt, ended_at AS endedAt, result_json AS result"
                        + " FROM pf_task_run WHERE task_id=? AND deleted_at IS NULL ORDER BY id DESC LIMIT " + n, id));
    }

    @GetMapping("/api/scheduled-tasks/{id:\\d+}/health")
    @Operation(summary = "定时任务·健康度。")
    public ApiResult<Map<String, Object>> health(@PathVariable long id) {
        RequestContext.requireUserId();
        require(id);
        Long runs24 = jdbc.queryForObject(
                "SELECT COUNT(*) FROM pf_task_run WHERE task_id=? AND started_at >= NOW() - INTERVAL 24 HOUR",
                Long.class, id);
        Long failed24 = jdbc.queryForObject(
                "SELECT COUNT(*) FROM pf_task_run WHERE task_id=? AND status='FAILED' AND started_at >= NOW() - INTERVAL 24 HOUR",
                Long.class, id);
        Map<String, Object> latest = new LinkedHashMap<>();
        List<Map<String, Object>> last = jdbc.queryForList(
                "SELECT id, status, started_at AS startedAt, ended_at AS endedAt FROM pf_task_run"
                        + " WHERE task_id=? AND deleted_at IS NULL ORDER BY id DESC LIMIT 1", id);
        if (!last.isEmpty()) {
            latest.putAll(last.get(0));
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("taskId", id);
        out.put("runs24h", runs24 == null ? 0 : runs24);
        out.put("failed24h", failed24 == null ? 0 : failed24);
        out.put("health", failed24 != null && failed24 > 0 ? "DEGRADED" : "OK");
        out.put("latest", latest);
        return ApiResult.ok(out);
    }
}
