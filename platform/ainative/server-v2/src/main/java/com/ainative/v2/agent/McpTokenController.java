package com.ainative.v2.agent;

import com.ainative.v2.common.ApiResult;
import com.ainative.v2.common.RequestContext;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/** MCP 令牌(/api/mcp/tokens)。 */
@Tag(name = "开放平台", description = "MCP 令牌与工具清单")
@RestController
@RequestMapping("/api/mcp/tokens")
public class McpTokenController {
    public record CreateRequest(String name) {}

    private final AgentService service;

    public McpTokenController(AgentService service) {
        this.service = service;
    }

    @GetMapping("/tools")
    @Operation(summary = "MCP 令牌(/api/mcp/tokens)。")
    public ApiResult<List<Map<String, Object>>> toolsList(
            com.ainative.v2.mcp.McpToolService tools) {
        RequestContext.requireUserId();
        return ApiResult.ok(tools.listTools());
    }

    @GetMapping
    @Operation(summary = "列出MCP 令牌。")
    public ApiResult<List<Map<String, Object>>> list() {
        return ApiResult.ok(service.listMcpTokens(RequestContext.requireUserId()));
    }

    @PostMapping
    @Operation(summary = "创建MCP 令牌。")
    public ApiResult<Map<String, Object>> create(@RequestBody(required = false) CreateRequest req) {
        return ApiResult.ok(service.createMcpToken(RequestContext.requireUserId(),
                req == null ? null : req.name()));
    }

    @DeleteMapping("/{id}")
    @Operation(summary = "删除MCP 令牌。")
    public ApiResult<Void> revoke(@PathVariable long id) {
        RequestContext.requireUserId();
        service.revokeMcpToken(id);
        return ApiResult.ok(null);
    }
}
