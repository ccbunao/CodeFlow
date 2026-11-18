package com.ainative.v2.squad;

import com.ainative.v2.common.ApiResult;
import com.ainative.v2.common.RequestContext;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/** 小队接口(/api/squads)。 */
@Tag(name = "执行与自动化", description = "编队成员与角色构成")
@RestController
@RequestMapping("/api/squads")
public class SquadController {
    public record SquadRequest(String name, String description) {}
    public record MemberRequest(Long agentId, String roleCode, java.util.List<Long> agentIds) {}

    private final SquadService service;

    public SquadController(SquadService service) {
        this.service = service;
    }

    @PostMapping
    @Operation(summary = "小队接口(/api/squads)。")
    public ApiResult<Map<String, Object>> create(@RequestBody SquadRequest req) {
        RequestContext.requireUserId();
        return ApiResult.ok(service.createSquad(req.name(), req.description()));
    }

    @GetMapping
    @Operation(summary = "列出编队。")
    public ApiResult<List<Map<String, Object>>> list() {
        RequestContext.requireUserId();
        return ApiResult.ok(service.listSquads());
    }

    @GetMapping("/{id}")
    @Operation(summary = "列出编队。")
    public ApiResult<Map<String, Object>> detail(@PathVariable long id) {
        RequestContext.requireUserId();
        return ApiResult.ok(service.squadDetail(id));
    }

    @PutMapping("/{id}")
    @Operation(summary = "更新编队。")
    public ApiResult<Map<String, Object>> update(@PathVariable long id, @RequestBody SquadRequest req) {
        RequestContext.requireUserId();
        return ApiResult.ok(service.updateSquad(id, req.name(), req.description()));
    }

    @DeleteMapping("/{id}")
    @Operation(summary = "删除编队。")
    public ApiResult<Void> delete(@PathVariable long id) {
        RequestContext.requireUserId();
        service.deleteSquad(id);
        return ApiResult.ok(null);
    }

    @GetMapping("/{id}/members")
    @Operation(summary = "编队·成员。")
    public ApiResult<List<Map<String, Object>>> members(@PathVariable long id) {
        RequestContext.requireUserId();
        return ApiResult.ok((List<Map<String, Object>>) service.squadDetail(id).get("members"));
    }

    @PostMapping("/{id}/members")
    @Operation(summary = "编队·成员。")
    public ApiResult<Void> addMember(@PathVariable long id, @RequestBody MemberRequest req) {
        RequestContext.requireUserId();
        // 前端批量入口传 agentIds:[..],早期接口传单个 agentId;两者都接受。
        if (req.agentIds() != null && !req.agentIds().isEmpty()) {
            for (Long agentId : req.agentIds()) {
                if (agentId != null) {
                    service.addMember(id, agentId, req.roleCode());
                }
            }
            return ApiResult.ok(null);
        }
        if (req.agentId() == null) {
            throw new com.ainative.v2.common.BizException("10001", "成员不能为空");
        }
        service.addMember(id, req.agentId(), req.roleCode());
        return ApiResult.ok(null);
    }

    @DeleteMapping("/{id}/members/{agentId}")
    @Operation(summary = "编队·成员。")
    public ApiResult<Void> removeMember(@PathVariable long id, @PathVariable long agentId) {
        RequestContext.requireUserId();
        service.removeMember(id, agentId);
        return ApiResult.ok(null);
    }

    @PostMapping("/{id}/copy")
    @Operation(summary = "编队·复制。")
    public ApiResult<Map<String, Object>> copy(@PathVariable long id,
                                               @RequestBody(required = false) Map<String, Object> body) {
        RequestContext.requireUserId();
        String requestId = body == null || body.get("requestId") == null
                ? null : String.valueOf(body.get("requestId"));
        return ApiResult.ok(service.copySquad(id, requestId));
    }
}
