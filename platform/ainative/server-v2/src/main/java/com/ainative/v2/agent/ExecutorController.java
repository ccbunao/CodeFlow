package com.ainative.v2.agent;

import com.ainative.v2.common.ApiResult;
import com.ainative.v2.common.RequestContext;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/** 执行器接口(/api/executors + /api/agents/{id}/executors)。 */
@RestController
@RequestMapping("/api")
public class ExecutorController {
    public record CreateRequest(String name, String clientKind) {}
    public record HeartbeatRequest(String status) {}

    private final AgentService service;

    public ExecutorController(AgentService service) {
        this.service = service;
    }

    @GetMapping("/executors")
    public ApiResult<List<Map<String, Object>>> list() {
        RequestContext.requireUserId();
        return ApiResult.ok(service.listExecutors());
    }

    @GetMapping("/executors/{id}")
    public ApiResult<Map<String, Object>> detail(@PathVariable long id) {
        RequestContext.requireUserId();
        return ApiResult.ok(service.executor(id));
    }

    @PostMapping("/executors/{id}/heartbeat")
    public ApiResult<Map<String, Object>> heartbeat(@PathVariable long id, @RequestBody(required = false) HeartbeatRequest req) {
        RequestContext.requireUserId();
        return ApiResult.ok(service.heartbeat(id, req == null ? null : req.status()));
    }

    @DeleteMapping("/executors/{id}")
    public ApiResult<Void> delete(@PathVariable long id) {
        RequestContext.requireUserId();
        service.deleteExecutor(id);
        return ApiResult.ok(null);
    }

    @GetMapping("/agents/{agentId}/executors")
    public ApiResult<List<Map<String, Object>>> ofAgent(@PathVariable long agentId) {
        RequestContext.requireUserId();
        return ApiResult.ok(service.executorsOf(agentId));
    }

    @PostMapping("/executors/{id}/token/rotate")
    public ApiResult<Map<String, Object>> rotate(@PathVariable long id) {
        RequestContext.requireUserId();
        return ApiResult.ok(service.rotateToken(id));
    }

    @GetMapping("/executors/{id}/launch-command")
    public ApiResult<Map<String, Object>> launchCommand(@PathVariable long id,
            @RequestParam(required = false) String token,
            @RequestParam(defaultValue = "posix") String os,
            @RequestParam(defaultValue = "foreground") String mode) {
        RequestContext.requireUserId();
        return ApiResult.ok(service.launchCommand(id, token, os, mode));
    }

    /**
     * 生成启动命令(POST 契约:启动值一律由服务端从数据库读取,前端只传输出格式选项)。
     * body 可选字段:os(posix/windows) / mode(foreground/nohup/service) / debug / shell。
     * 生成过程会轮换接入令牌,与 GET 版本语义一致。
     */
    public record LaunchCommandRequest(String os, String mode, Boolean debug, String shell) {}

    @PostMapping("/executors/{id}/launch-command")
    public ApiResult<Map<String, Object>> buildLaunchCommand(@PathVariable long id,
            @RequestBody(required = false) LaunchCommandRequest req) {
        RequestContext.requireUserId();
        String os = req == null || req.os() == null || req.os().isBlank() ? "posix" : req.os();
        String mode = req == null || req.mode() == null || req.mode().isBlank() ? "foreground" : req.mode();
        Map<String, Object> out = service.launchCommand(id, null, os, mode);
        if (req != null) {
            out.put("os", os);
            out.put("mode", mode);
            out.put("debug", Boolean.TRUE.equals(req.debug()));
            out.put("shell", req.shell());
        }
        return ApiResult.ok(out);
    }

    /**
     * 保存「编辑完整命令」的手工覆盖:落 ag_executor_update 变更流水(审计可查)。
     * 覆盖命令在运行器下次心跳拉取 update 时透传;重新生成命令即回到服务端拼装。
     */
    public record LaunchCommandOverrideRequest(String command, Integer version) {}

    @PutMapping("/executors/{id}/launch-command")
    public ApiResult<Map<String, Object>> saveLaunchCommandOverride(@PathVariable long id,
            @RequestBody LaunchCommandOverrideRequest req) {
        RequestContext.requireUserId();
        if (req.command() == null || req.command().isBlank()) {
            throw new com.ainative.v2.common.BizException("10001", "命令不能为空");
        }
        String detail = "{\"kind\":\"LAUNCH_COMMAND_OVERRIDE\",\"command\":" + jsonEscape(req.command()) + "}";
        // ExecutorOpsController.saveLaunchConfig 同款流水表;workspace 由调用上下文决定
        service.recordLaunchCommandOverride(id, req.version(), detail);
        return ApiResult.ok(Map.of("executorId", id, "saved", true));
    }

    /**
     * 草稿预览:表单改过未保存时,前端走此端点预览命令。启动值仍取数据库已存配置,
     * 草稿仅影响前端展示;保存后走 POST 生成端点(dryRun=false 语义相同)。
     */
    @PostMapping("/executors/{id}/launch-command/preview")
    public ApiResult<Map<String, Object>> previewLaunchCommand(@PathVariable long id,
            @RequestBody(required = false) LaunchCommandRequest req) {
        RequestContext.requireUserId();
        String os = req == null || req.os() == null || req.os().isBlank() ? "posix" : req.os();
        String mode = req == null || req.mode() == null || req.mode().isBlank() ? "foreground" : req.mode();
        Map<String, Object> out = service.launchCommand(id, null, os, mode);
        out.put("dryRun", true);
        return ApiResult.ok(out);
    }

    private String jsonEscape(String s) {
        try {
            return new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(s);
        } catch (Exception e) {
            return "\"\u0000\"";
        }
    }

    @GetMapping("/executors/{id}/model-upgrade-policy")
    public ApiResult<Map<String, Object>> modelPolicy(@PathVariable long id) {
        RequestContext.requireUserId();
        return ApiResult.ok(service.modelPolicy(id));
    }

    @PutMapping("/executors/{id}/model-upgrade-policy")
    public ApiResult<Map<String, Object>> setModelPolicy(@PathVariable long id,
            @RequestBody(required = false) Map<String, String> req) {
        RequestContext.requireUserId();
        return ApiResult.ok(service.setModelPolicy(id, req == null ? null : req.get("policy")));
    }

    @PostMapping("/agents/{agentId}/executors")
    public ApiResult<Map<String, Object>> create(@PathVariable long agentId, @RequestBody CreateRequest req) {
        RequestContext.requireUserId();
        return ApiResult.ok(service.createExecutor(agentId, req.name(), req.clientKind()));
    }
}
