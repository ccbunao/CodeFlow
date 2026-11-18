package com.ainative.v2.asset;

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

/** 资产与仓库接口:/api/repos、/api/environment-variables、产物列表。 */
@Tag(name = "配置资产", description = "代码仓库、依赖关系与交付结论")
@RestController
@RequestMapping("/api")
public class AssetController {
    public record RepoRequest(String name, String url, String provider, String defaultBranch) {}
    public record ConclusionRequest(String contentMd) {}
    public record EnvVarRequest(String key, String value, Boolean secret) {}

    private final AssetService service;

    public AssetController(AssetService service) {
        this.service = service;
    }

    // 仓库
    @GetMapping("/repos")
    @Operation(summary = "列出代码仓库。")
    public ApiResult<List<Map<String, Object>>> repos() {
        RequestContext.requireUserId();
        return ApiResult.ok(service.listRepos());
    }

    @PostMapping("/repos")
    @Operation(summary = "创建代码仓库。")
    public ApiResult<Map<String, Object>> createRepo(@RequestBody RepoRequest req) {
        RequestContext.requireUserId();
        return ApiResult.ok(service.createRepo(req.name(), req.url(), req.provider(), req.defaultBranch()));
    }

    @GetMapping("/repos/{id}")
    @Operation(summary = "列出代码仓库。")
    public ApiResult<Map<String, Object>> repo(@PathVariable long id) {
        RequestContext.requireUserId();
        return ApiResult.ok(service.repo(id));
    }

    @PutMapping("/repos/{id}")
    @Operation(summary = "更新代码仓库。")
    public ApiResult<Map<String, Object>> updateRepo(@PathVariable long id, @RequestBody RepoRequest req) {
        RequestContext.requireUserId();
        return ApiResult.ok(service.updateRepo(id, req.name(), req.url(), req.provider(), req.defaultBranch()));
    }

    @DeleteMapping("/repos/{id}")
    @Operation(summary = "删除代码仓库。")
    public ApiResult<Void> deleteRepo(@PathVariable long id) {
        RequestContext.requireUserId();
        service.deleteRepo(id);
        return ApiResult.ok(null);
    }

    @GetMapping("/repos/{id}/conclusion")
    @Operation(summary = "代码仓库·交付结论。")
    public ApiResult<Map<String, Object>> conclusion(@PathVariable long id) {
        RequestContext.requireUserId();
        return ApiResult.ok(service.repo(id));
    }

    @PutMapping("/repos/{id}/conclusion")
    @Operation(summary = "代码仓库·交付结论。")
    public ApiResult<Map<String, Object>> saveConclusion(@PathVariable long id, @RequestBody ConclusionRequest req) {
        RequestContext.requireUserId();
        return ApiResult.ok(service.saveConclusion(id, req.contentMd()));
    }

    @GetMapping("/repos/relations")
    @Operation(summary = "代码仓库·依赖关系。")
    public ApiResult<List<Map<String, Object>>> relationsFrontendPath() {
        RequestContext.requireUserId();
        return ApiResult.ok(service.relations());
    }

    @GetMapping("/repo-relations")
    @Operation(summary = "列出代码仓库。")
    public ApiResult<List<Map<String, Object>>> relations() {
        RequestContext.requireUserId();
        return ApiResult.ok(service.relations());
    }

    // 环境变量
    @GetMapping("/environment-variables")
    @Operation(summary = "列出代码仓库。")
    public ApiResult<List<Map<String, Object>>> envVars() {
        RequestContext.requireUserId();
        return ApiResult.ok(service.envVars());
    }

    @PostMapping("/environment-variables")
    @Operation(summary = "创建代码仓库。")
    public ApiResult<Map<String, Object>> createEnvVar(@RequestBody EnvVarRequest req) {
        RequestContext.requireUserId();
        return ApiResult.ok(service.createEnvVar(req.key(), req.value(), Boolean.TRUE.equals(req.secret())));
    }

    @PutMapping("/environment-variables/{id}")
    @Operation(summary = "更新代码仓库。")
    public ApiResult<Map<String, Object>> updateEnvVar(@PathVariable long id, @RequestBody EnvVarRequest req) {
        RequestContext.requireUserId();
        return ApiResult.ok(service.updateEnvVar(id, req.value(), Boolean.TRUE.equals(req.secret())));
    }

    @DeleteMapping("/environment-variables/{id}")
    @Operation(summary = "删除代码仓库。")
    public ApiResult<Void> deleteEnvVar(@PathVariable long id) {
        RequestContext.requireUserId();
        service.deleteEnvVar(id);
        return ApiResult.ok(null);
    }

    // 产物
    @GetMapping("/workitems/{workitemId}/artifacts")
    @Operation(summary = "代码仓库·产物。")
    public ApiResult<List<Map<String, Object>>> artifacts(@PathVariable long workitemId) {
        RequestContext.requireUserId();
        return ApiResult.ok(service.artifactsOf(workitemId));
    }
}
