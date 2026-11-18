package com.ainative.integration.tapd;

// 合入说明(同事 Alan Bao 的 v1 模块 → v2):
//   1) Result → v2 的 ApiResult;RequestContext → v2 的 RequestContext
//   2) v1 的 BizException/ErrorCode → v2 的 BizException(字符串错误码)
//   3) @RequireWorkspaceAccess 是 v1 访问控制注解,v2 由 PermissionInterceptor 统一拦截,故移除
//   4) InboundSyncService(v1 写 v1 workitem 表)→ v2 的 TapdInboundSyncService(写 v2 wi_ticket)
// 其余逻辑与 v1 完全一致。
import com.ainative.integration.common.ExternalProjectBindingDO;
import com.ainative.integration.common.ExternalProjectBindingDao;
import com.ainative.integration.dto.SyncNowResult;
import com.ainative.integration.provider.ExternalPlatformConfig;
import com.ainative.integration.provider.ExternalPlatformException;
import com.ainative.security.crypto.SecretCrypto;
import com.ainative.v2.common.ApiResult;
import com.ainative.v2.common.BizException;
import com.ainative.v2.common.RequestContext;
import com.ainative.v2.ex.TapdInboundSyncService;
import org.springframework.dao.DuplicateKeyException;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Date;
import java.util.List;
import java.util.Objects;

@Tag(name = "外部连接", description = "TAPD 绑定、评论回写与读回确认")
@RestController
@RequestMapping("/api/integrations/tapd")
public class TapdIntegrationController {
    private final ExternalProjectBindingDao bindings;
    private final SecretCrypto crypto;
    private final TapdWorkitemProvider provider;
    private final TapdInboundSyncService inbound;

    public TapdIntegrationController(ExternalProjectBindingDao bindings, SecretCrypto crypto,
                                     TapdWorkitemProvider provider, TapdInboundSyncService inbound) {
        this.bindings = bindings;
        this.crypto = crypto;
        this.provider = provider;
        this.inbound = inbound;
    }

    public record Connection(String projectId, String apiUser, String apiPassword, String commentAuthor,
                             String baseUrl) { }
    public record BindingView(Long id, String projectId, boolean enabled, Date lastSuccessAt, boolean syncFailed) { }
    public record EnabledRequest(Boolean enabled) { }

    @PostMapping("/bindings/test")
    @Operation(summary = "创建TAPD 绑定。")
    public ApiResult<Boolean> test(@RequestBody Connection connection) {
        testRead(config(connection));
        return ApiResult.ok(true);
    }

    @PostMapping("/bindings")
    @Operation(summary = "TAPD 绑定·绑定。")
    public ApiResult<BindingView> save(@RequestBody Connection connection) {
        ExternalPlatformConfig config = config(connection);
        long tenant = workspace();
        long actor = actor();
        testRead(config);
        ExternalProjectBindingDO binding = bindings.findByProject(tenant, provider.provider(), config.externalProjectId());
        boolean create = binding == null;
        if (create) {
            binding = new ExternalProjectBindingDO();
            binding.setTenantId(tenant);
            binding.setProvider(provider.provider());
            binding.setExternalProjectId(config.externalProjectId());
            binding.setBaseUrl(config.baseUrl());
            binding.setRegionId("1");
            binding.setEnabled(1);
            binding.setPollIntervalSeconds(60);
            binding.setCreatorId(actor);
        }
        binding.setClientKey(config.clientKey());
        // 合入补充:v1 固定官方地址,这里允许随绑定更新 baseUrl(私有化部署/联调网关)
        binding.setBaseUrl(config.baseUrl());
        binding.setCredentialRef(crypto.encrypt(config.accessSecret()));
        binding.setWritebackStaffId(connection.commentAuthor().strip());
        try {
            if (create) bindings.insert(binding);
            else if (bindings.updateConnection(binding) != 1) throw new BizException("14009", "TAPD 绑定已存在且更新失败");
        } catch (DuplicateKeyException conflict) {
            throw new BizException("14009", "TAPD 绑定冲突");
        }
        return ApiResult.ok(view(binding));
    }

    @GetMapping("/bindings")
    @Operation(summary = "TAPD 绑定·绑定。")
    public ApiResult<List<BindingView>> list() {
        return ApiResult.ok(bindings.list(workspace(), provider.provider(), 0, 100).stream().map(this::view).toList());
    }

    @PutMapping("/bindings/{id}/enabled")
    @Operation(summary = "更新TAPD 绑定。")
    public ApiResult<BindingView> setEnabled(@PathVariable("id") long id, @RequestBody EnabledRequest request) {
        if (request.enabled() == null) throw new BizException("10001", "enabled 不能为空");
        ExternalProjectBindingDO binding = requireBinding(id);
        int value = request.enabled() ? 1 : 0;
        if (bindings.setEnabled(id, binding.getTenantId(), provider.provider(), value) != 1) {
            throw new BizException("14009", "TAPD 绑定状态更新冲突");
        }
        binding.setEnabled(value);
        return ApiResult.ok(view(binding));
    }

    @PostMapping("/bindings/{id}/sync-now")
    @Operation(summary = "创建TAPD 绑定。")
    public ApiResult<SyncNowResult> syncNow(@PathVariable("id") long id) {
        ExternalProjectBindingDO binding = requireBinding(id);
        if (!Integer.valueOf(1).equals(binding.getEnabled())) {
            throw new BizException("14009", "TAPD 绑定已停用");
        }
        var config = new ExternalPlatformConfig(binding.getBaseUrl(), binding.getClientKey(),
                crypto.decrypt(binding.getCredentialRef()), binding.getRegionId(), binding.getExternalProjectId());
        try {
            Date last = binding.getLastSuccessAt();
            Date since = last == null ? null : new Date(last.getTime() - 60_000);
            var page = provider.searchProject(config, binding.getExternalProjectId(), since, null);
            return ApiResult.ok(inbound.syncWorkitems(binding, page.getItems(), actor()));
        } catch (ExternalPlatformException e) {
            throw apiFailure(e);
        }
    }

    private void testRead(ExternalPlatformConfig config) {
        try {
            provider.searchProjectFirstPage(config, config.externalProjectId());
        } catch (ExternalPlatformException e) {
            throw apiFailure(e);
        }
    }

    private static BizException apiFailure(ExternalPlatformException e) {
        return new BizException("10001", e.getMessage());
    }

    private ExternalProjectBindingDO requireBinding(long id) {
        long tenant = workspace();
        ExternalProjectBindingDO binding = bindings.findById(id);
        if (binding == null || !Objects.equals(tenant, binding.getTenantId())
                || !provider.provider().equals(binding.getProvider())) {
            throw new BizException("10001", "TAPD 绑定不存在");
        }
        return binding;
    }

    private ExternalPlatformConfig config(Connection req) {
        String project = trimmed(req.projectId());
        String user = trimmed(req.apiUser());
        String author = trimmed(req.commentAuthor());
        if (!TapdWorkitemProvider.isId(project) || user.isBlank() || user.length() > 128 || user.contains(":")
                || req.apiPassword() == null || req.apiPassword().isBlank() || req.apiPassword().length() > 2048
                || author.isBlank() || author.length() > 64) {
            throw new BizException("10001", "TAPD 连接参数不合法");
        }
        // baseUrl 可选:默认官方地址,私有化部署/联调环境可覆盖
        String baseUrl = req.baseUrl() == null || req.baseUrl().isBlank()
                ? TapdClient.API_URL : req.baseUrl().strip();
        return new ExternalPlatformConfig(baseUrl, user, req.apiPassword(), "1", project);
    }

    private BindingView view(ExternalProjectBindingDO binding) {
        return new BindingView(binding.getId(), binding.getExternalProjectId(),
                Integer.valueOf(1).equals(binding.getEnabled()), binding.getLastSuccessAt(),
                binding.getLastError() != null && !binding.getLastError().isBlank());
    }

    private static String trimmed(String value) {
        return value == null ? "" : value.strip();
    }

    private static long workspace() {
        return RequestContext.workspaceId();
    }

    private static long actor() {
        return RequestContext.requireUserId();
    }
}
