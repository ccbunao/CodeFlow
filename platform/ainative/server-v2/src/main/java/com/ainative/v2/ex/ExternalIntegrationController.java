package com.ainative.v2.ex;

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

/** 外部平台集成接口(/api/integrations/{provider}/...)。 */
@Tag(name = "外部连接", description = "外部系统绑定、状态映射与拉取")
@RestController
@RequestMapping("/api/integrations")
public class ExternalIntegrationController {
    public record BindingRequest(String projectId, String name, String credentialRef, String baseUrl) {}
    public record StatusMappingRequest(String externalStatus, Long stateId) {}

    private final ExService service;
    private final ExPullService pullService;

    public ExternalIntegrationController(ExService service, ExPullService pullService) {
        this.service = service;
        this.pullService = pullService;
    }

    @PostMapping("/{provider}/bindings/{id}/pull")
    @Operation(summary = "外部系统绑定·拉取单据。")
    public ApiResult<Map<String, Object>> pull(@PathVariable String provider, @PathVariable long id) {
        RequestContext.requireUserId();
        return ApiResult.ok(pullService.pull(id));
    }

    @GetMapping("/{provider}/bindings")
    @Operation(summary = "外部系统绑定·绑定。")
    public ApiResult<List<Map<String, Object>>> list(@PathVariable String provider) {
        RequestContext.requireUserId();
        return ApiResult.ok(service.bindings(provider));
    }

    @PostMapping("/{provider}/bindings")
    @Operation(summary = "外部系统绑定·绑定。")
    public ApiResult<Map<String, Object>> create(@PathVariable String provider, @RequestBody BindingRequest req) {
        RequestContext.requireUserId();
        return ApiResult.ok(service.createBinding(provider, req.projectId(), req.name(), req.credentialRef(), req.baseUrl()));
    }

    @DeleteMapping("/{provider}/bindings/{id}")
    @Operation(summary = "外部系统绑定·绑定。")
    public ApiResult<Void> delete(@PathVariable String provider, @PathVariable long id) {
        RequestContext.requireUserId();
        service.deleteBinding(id);
        return ApiResult.ok(null);
    }

    @GetMapping("/{provider}/bindings/{id}/status-mappings")
    @Operation(summary = "外部系统绑定·状态映射。")
    public ApiResult<List<Map<String, Object>>> mappings(@PathVariable String provider, @PathVariable long id) {
        RequestContext.requireUserId();
        return ApiResult.ok(service.statusMappings(id));
    }

    @PutMapping("/{provider}/bindings/{id}/status-mappings")
    @Operation(summary = "外部系统绑定·状态映射。")
    public ApiResult<List<Map<String, Object>>> upsertMapping(@PathVariable String provider, @PathVariable long id,
                                                              @RequestBody StatusMappingRequest req) {
        RequestContext.requireUserId();
        if (req.stateId() == null) {
            throw new com.ainative.v2.common.BizException("10001", "目标状态不能为空");
        }
        return ApiResult.ok(service.upsertStatusMapping(id, req.externalStatus(), req.stateId()));
    }
}
