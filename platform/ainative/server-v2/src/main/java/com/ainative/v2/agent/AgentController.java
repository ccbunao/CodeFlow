package com.ainative.v2.agent;

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

/** 智能体接口(/api/agents)。 */
@Tag(name = "执行与自动化", description = "AI 数字员工定义与版本")
@RestController
@RequestMapping("/api/agents")
public class AgentController {
    public record CreateRequest(String name, String roleCode, String roleName, String soulMd, String agentMd) {}
    /** null = 本次不涉及该字段(保持原值),空串 = 显式清空。 */
    public record ConfigRequest(String roleName, String soulMd, String agentMd) {}

    private final AgentService service;

    public AgentController(AgentService service) {
        this.service = service;
    }

    @PostMapping
    @Operation(summary = "智能体接口(/api/agents)。")
    public ApiResult<Map<String, Object>> create(@RequestBody CreateRequest req) {
        RequestContext.requireUserId();
        return ApiResult.ok(service.createAgent(req.name(), req.roleCode(), req.roleName(),
                req.soulMd(), req.agentMd()));
    }

    @GetMapping
    @Operation(summary = "列出AI 数字员工。")
    public ApiResult<List<Map<String, Object>>> list() {
        RequestContext.requireUserId();
        return ApiResult.ok(service.listAgents());
    }

    @GetMapping("/{id}")
    @Operation(summary = "列出AI 数字员工。")
    public ApiResult<Map<String, Object>> detail(@PathVariable long id) {
        RequestContext.requireUserId();
        return ApiResult.ok(service.agentDetail(id));
    }

    @PutMapping("/{id}/config")
    @Operation(summary = "AI 数字员工·配置。")
    public ApiResult<Map<String, Object>> config(@PathVariable long id, @RequestBody ConfigRequest req) {
        RequestContext.requireUserId();
        return ApiResult.ok(service.updateConfig(id, req.roleName(), req.soulMd(), req.agentMd()));
    }

    @DeleteMapping("/{id}")
    @Operation(summary = "删除AI 数字员工。")
    public ApiResult<Void> delete(@PathVariable long id) {
        RequestContext.requireUserId();
        service.deleteAgent(id);
        return ApiResult.ok(null);
    }
}
