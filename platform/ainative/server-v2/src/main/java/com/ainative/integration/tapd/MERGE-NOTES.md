# TAPD 集成合入说明

同事 **Alan Bao** 的 TAPD 集成(仓库内 v1:`src/main/java/com/ainative/integration/tapd/`,
提交 `50f2a79d feat(integration): add TAPD work item sync and delivery writeback`)
按**原样合入** v2,不是重写。

## 合入范围

| 文件 | 来源 | 处理 |
|---|---|---|
| `tapd/TapdClient.java` | Alan v1 | 原样合入,仅把固定 `API_URL` 改为读 `config.baseUrl()`(缺省仍是官方地址) |
| `tapd/TapdWorkitemProvider.java` | Alan v1 | **逐字节原样合入**(已 diff 校验) |
| `tapd/TapdCommentReadback.java` | Alan v1 | 原样合入,由 v2 的 outbox 读回任务驱动 |
| `tapd/TapdIntegrationController.java` | Alan v1 | 端点/请求体/响应体契约不变,仅替换框架类型(见下) |
| `provider/*`(13 个) | Alan v1 | 原样合入(TAPD provider 依赖的接口与 DTO) |
| `common/ExternalProjectBindingDO.java`、`dto/SyncNowResult.java`、`receipt/ExternalOperationReadbackHandler.java`、`common/IntegrationOutboxDO.java` | Alan v1 | 原样合入 |
| `json/*`、`security/crypto/*` | Alan v1 | 原样合入(TAPD readback 依赖的 JSON 门面与 AES-GCM 凭据加密) |

## v2 适配层(新增文件,均为适配,不含 TAPD 业务逻辑)

- `com.ainative.integration.common.ExternalProjectBindingDao`
  —— v1 是 MyBatis + `external_project_binding` 表;v2 全量 JdbcTemplate,
  绑定落 `ex_binding`(与禅道共用)。方法签名与 v1 一致,Alan's controller 零改动。
- `com.ainative.v2.ex.TapdInboundSyncService`
  —— 对应 v1 的 `InboundSyncService.syncWorkitems`,但 v1 写 v1 的 `workitem` 表,
  v2 工单模型不同(工作区/状态机/标签),故按 v2 表结构实现同名同签名的方法:
  落 `wi_ticket` + `ex_item_link` + `wi_journal`,工作区无状态机时按内置模板开户。
- `com.ainative.v2.ex.TapdWritebackOutboxService`
  —— v1 的 outbox 读回宿主:回写结果未知(5xx)时记 `rt_outbox`,
  周期调用 Alan 的 `TapdCommentReadback` 按 marker 读回确认(不盲目重发)。
- `com.ainative.v2.config.SecretCryptoConfig` —— 注册 v1 的 `AesGcmSecretCrypto` Bean。

## Alan 控制器里的框架替换(逻辑未动)

| v1 | v2 | 原因 |
|---|---|---|
| `com.ainative.common.result.Result` | `com.ainative.v2.common.ApiResult` | v2 统一返回包装 |
| `com.ainative.common.error.BizException/ErrorCode` | `com.ainative.v2.common.BizException`(字符串码) | v2 错误码体系 |
| `com.ainative.context.RequestContext` | `com.ainative.v2.common.RequestContext` | v2 上下文 |
| `InboundSyncService` | `TapdInboundSyncService` | v1 服务写 v1 表,见上 |
| `@RequireWorkspaceAccess` | 删除 | v1 访问控制注解;v2 由 `PermissionInterceptor` 统一拦截 |

另外两处小改动:
1. `Connection` 记录新增可选字段 `baseUrl`(前端不传即用官方地址)。
2. `save()` 更新绑定时同步刷新 `baseUrl`。

## 部署

外部平台凭据用 AES-GCM 加密后落库(`credential_ref` 形如 `enc:v1:…`),需要主密钥:

```bash
export AINATIVE_SECRET_MASTER_KEY=$(openssl rand -base64 32)   # 与 v1 同一环境变量
```

- 未配置时**平台照常启动**,只有真正要存取 TAPD 凭据(保存绑定/同步/回写)时报错,
  并直接提示该配哪个环境变量
- 主密钥一旦更换,已保存的 TAPD 绑定解不开,需要重新保存绑定
- 主密钥不要进仓库;多节点部署所有节点必须一致

## 端点契约(与前端 `TapdIntegrationPanel.tsx` 一致)

```
POST /api/integrations/tapd/bindings/test          → true
POST /api/integrations/tapd/bindings                → BindingView
GET  /api/integrations/tapd/bindings                → BindingView[]
PUT  /api/integrations/tapd/bindings/{id}/enabled   → BindingView
POST /api/integrations/tapd/bindings/{id}/sync-now  → { imported, updated }
```

## 数据形态

- 外部 ID:`{workspace}:{story|bug}:{id}`(Alan 的 `ItemRef.externalId()`)
- 工单类型:`REQ`(story)/ `BUG`(bug);优先级 high|urgent→1,medium|middle|normal→2,low→3
- 回写:`POST {baseUrl}/comments`(form:`workspace_id/entry_id/entry_type/description/author`),
  `entry_type` = `stories`(需求)| `bug`(缺陷),描述为 `<p>…<br></p>` HTML
- marker 形态:`<!-- an-op:<24位hex> -->`(注意:Alan 的正则里括号是分组语法,不是字面量)
