# plan.md · 数字员工人设贯通与会话产物归档(设计与实施计划)

- 版本:1.0 · 日期:2026-11-20 · 分支:`feat/agent-persona-artifact-archive`
- 配套:`deliverables/spec.md`(需求规格)
- 平台基线:1.2.0(`platform/ainative/VERSION`) · 协议:`ainative.taskPackage.v1` · 运行时 0.3.33

## 1. 架构总览

```
                 ┌──────────────── 数字员工定义(平台) ────────────────┐
  前端表单        │ ag_agent: name / role_code / role_name             │
 AgentCreatePage │            soul_md(SOUL.md)  agent_md(AGENT.md)    │
 businessBackground│                                              │
 responsibilities │  ↓ 派发时快照(rt_run.dispatch_snapshot)           │
        │        └──────────────────────────────────────────────────┘
        │ 创建/编辑 API:soulMd / agentMd
        ▼
  ┌─────────────┐   identity.json{name,roleCode,soulMd,agentMd,rules}
  │ CapsuleBuilder│ ─────────────────────────────────────────────┐
  └─────────────┘   manifest.json(fileDigests)  workitem.md …    │
                                                            ▼
                                              运行时 packager.mount()
                                                            ▼
                              claude --append-system-prompt "<人设 + 步骤规则>"
                                                            ▼
  ┌──────────────── 澄清会话产物(平台) ────────────────┐
  │ CONVERSATION_TURN_ACK(replyMarkdown)                │
  │   → ArtifactStorage 落盘                            │
  │   → as_artifact(ticket_id, kind='CLARIFICATION')    │
  │   → cv_artifact(turn_id, artifact_id)               │
  │   → 需求详情产物区可见/可下载                       │
  └─────────────────────────────────────────────────────┘
```

## 2. 模块职责与改动点

| 模块 | 职责 | 改动 |
|---|---|---|
| `agent/AgentController` | 数字员工 REST 契约 | `CreateRequest`/`ConfigRequest` 增 `agentMd`;字段名以 `soulMd`/`agentMd` 为准 |
| `agent/AgentService` | 校验与编排 | 透传 `agentMd`;配置更新区分 null(保持)/空串(清空) |
| `agent/AgentDao` | SQL | `ag_agent` 读写 `agent_md` |
| `agent/AgentResourceController` | 版本快照/回滚 | 快照含 `agent_md`;回滚一并恢复 |
| `pf/ProjectBackupService` | 备份导出 | 导出列加 `agent_md` |
| `dispatch/DispatchLaunchService` | 派发编排 | 取人设 → 快照整包输入进 `rt_run.dispatch_snapshot` → 传 `CapsuleBuilder` |
| `dispatch/CapsuleBuilder` | 包体组装 | `identity.json` 增 `soulMd`/`agentMd`/`rules` |
| `cv/ConversationTurnService` | 澄清会话 | ACK 后把澄清结论归档为产物(写盘 + `as_artifact` + `cv_artifact`) |
| `artifact/ArtifactStorage` | 产物落盘 | 支持按"会话"维度目录(不复用 dispatchId 目录) |
| 前端 `features/agent/api.ts` | 前后端字段映射 | UI 字段 `businessBackground`/`responsibilities` ↔ 线上字段 `soulMd`/`agentMd` |
| 运行时 `providers/claude_code.py` | 提示词组装 | `_system_prompt` 读 `rules`,缺失时回落 `soulMd`/`agentMd` 原文 |
| 运行时 `providers/text_cli.py` | 其他 CLI 供应商 | 同样把人设前置到任务提示词 |
| 运行时 `runtime-npm/runtime/` | npm 包同源副本 | 与 `runtime/` 同步 |

## 3. 关键决策(含备选与理由)

### D-01 线上字段名取 `soulMd` / `agentMd`

- 备选 A:后端同时接受 `businessBackground`/`responsibilities` 两个别名。
- 备选 B(采用):线上契约统一 `soulMd`/`agentMd`,前端在 `api.ts` 边界做一次映射。
- 理由:后端 v2 与 MCP 工具文档(`update_agent_config` 要求 `soulMd`+`agentMd`)已经以 `soulMd` 为准;保留双别名会让两套名字长期并存、每处都要判空。别名只在 UI 层存在,`api.ts` 一处转换,契约单一。

### D-02 配置更新:null = 保持,空串 = 清空

- 备选 A:保持"全量覆盖"语义(null 即清空)。→ 这正是 P2 的成因,任何不传人设的调用方都会抹掉正文。
- 备选 B(采用):按字段判空。`roleName` 同理处理(留空即不改)。
- 理由:接口被前端表单、MCP 工具、脚本多种调用方共享,全量覆盖语义把"没传"和"要清空"混为一谈。代价是"清空"必须显式传空串,语义明确。

### D-03 人设进任务包:`identity.json` 增字段,不新增包条目

- 备选 A:新增 `persona.md` 包条目。
- 备选 B(采用):在既有 `identity.json` 内增 `soulMd`/`agentMd`/`rules`。
- 理由:协议文档 §6 已把 `identity.json` 定义为"数字员工身份:name/roleCode/职责/操作规则",这里是补齐既有契约而非扩展协议;运行时 `packager.py` 已经读取 `identity.json`,`claude_code.py` 已经有读 `rules` 的钩子(只是平台从未写过)。新增包条目会改动 `fileDigests` 契约与下游校验。

### D-04 派发时快照人设进 `rt_run.dispatch_snapshot`

- 备选 A:重建包体时实时读 `ag_agent`。
- 备选 B(采用):派发时把**重建包体所需的全部输入**(人设 + 工单标题/正文 + SDLC 步骤 + 名册)冻结进 `rt_run.dispatch_snapshot`,重建时只读快照,老数据回退实时读。
- 理由:任务包 md5 在帧里下发、下载端按 md5 校验。人设、名册、工单正文、SDLC 步骤都是会被人改的可变数据,任何一项实时读都会让"派发后改动 → 包体变了 → 运行器下载失败"。快照同时留下审计痕迹(谁在什么时候带着哪版输入开工)。
- 列名从最初的 `agent_prompt` 改为 `dispatch_snapshot`:存的不只是人设,而是整包输入,叫 agent_prompt 名不副实。

### D-05 会话产物:平台侧归档澄清结论

- 备选 A:运行时把会话目录里的文件回传(需要新增上传协议 + 运行时改动)。
- 备选 B(采用):平台在收到 `CONVERSATION_TURN_ACK` 时,把该轮澄清结论落盘并写入 `as_artifact`(`kind='CLARIFICATION'`)+ `cv_artifact`。
- 理由:`cv_artifact` 表本来就是为"会话回合的产物"设计的(turn_id → artifact_id),当前 0 行;澄清结论是澄清会话唯一稳定、可复现的产出物,而会话目录里的临时文件不在平台掌控内(不可复现、无法命名)。先用平台侧归档把链路打通并留痕;文件回传作为后续增强登记在待办。

### D-06 产物 kind 取 `CLARIFICATION`,run_id 留空

- 理由:`as_artifact.run_id` 指向 `rt_run`(执行),澄清会话不是执行;前端 `groupArtifactsByExecution` 对 `dispatchId == null` 有"未关联执行"分组,不会错挂到某次执行下。

## 4. 接口与数据契约

### 4.1 REST(平台)

| 方法 | 路径 | 变化 |
|---|---|---|
| POST | `/api/agents` | 请求体:`name`/`roleCode`/`roleName`/**`soulMd`**/**`agentMd`**;响应增 `agentMd` |
| PUT | `/api/agents/{id}/config` | 请求体:`roleName`/**`soulMd`**/**`agentMd`**(null=保持,空串=清空) |
| GET | `/api/agents/{id}` | 响应增 `agentMd`(`soulMd` 已有) |
| GET | `/api/workitems/{id}/artifacts` | 无字段变化,新增的澄清产物以 `kind='CLARIFICATION'` 出现在列表 |

前端映射(`features/agent/api.ts`):

```
表单字段            线上字段
businessBackground → soulMd
responsibilities   → agentMd
```

### 4.2 任务包 `identity.json`

```json
{
  "name": "公众号标题优化师",
  "roleCode": "TITLE_COACH",
  "roleName": "公众号标题优化师",
  "soulMd": "……业务背景……",
  "agentMd": "……职责……",
  "rules": ["……业务背景第 1 行", "……职责第 1 行"]
}
```

`rules` = `soulMd` + `agentMd` 的非空行(去 Markdown 标题符号、限长);运行时优先读 `rules`,缺失时回落 `soulMd`/`agentMd` 原文。

### 4.3 数据库

```sql
-- ag_agent:新增 AGENT.md 正文列
ALTER TABLE ag_agent ADD COLUMN agent_md MEDIUMTEXT AFTER soul_md;
-- rt_run:新增派发时的输入快照(人设 + 工单 + 步骤 + 名册)
ALTER TABLE rt_run ADD COLUMN dispatch_snapshot MEDIUMTEXT AFTER agent_id;
```

落地方式:改 `platform/ainative/docs/seekdb-schema-v1-core.sql` 的建表语句(新库生效)+ 在 `platform/docker-compose.yml` 的 `db-init` 追加**幂等** `ALTER`(已初始化的库生效,与既有 `wi_flow` 做法一致)。生产代码内不另写 DDL(仓库约定)。

## 5. 故障处理矩阵

| 故障 | 表现 | 处理 |
|---|---|---|
| 老平台帧无 `rules`/`soulMd` | 运行时拿不到人设 | 运行时回落:无字段则不注入,不影响执行;老运行器忽略新字段 |
| 老库无 `agent_md` 列 | 写入报错 | `db-init` 幂等 ALTER 补列;平台启动前校验列存在 |
| 人设在派发后被改 | 包体 md5 不一致 | `rt_run.dispatch_snapshot` 快照兜底,重建按快照,md5 恒等 |
| 会话不是澄清会话(title 非 `clarification:`) | 无归属需求 | 跳过归档,不报错 |
| 归档写盘失败 | 会话回合失败 | 归档失败只记日志,不影响 ACK 与会话状态 |
| 重复 ACK(重投) | 产物重复 | 产物名确定性 + 同名覆盖;`cv_artifact` 先删后插 |

## 6. 实施步骤

1. **分析与分支准备**:建分支、定位根因(本文件 + `spec.md`)。
2. **改动 1**:DDL → `AgentDao/Service/Controller` → 快照/回滚/导出 → 前端映射 → 单测。
3. **改动 2**:`rt_run.dispatch_snapshot` → `CapsuleBuilder` → `DispatchLaunchService` → 运行时提示词 → `runtime-npm` 同步 → 单测。
4. **改动 3**:`ArtifactStorage` 会话目录 → `ConversationTurnService` 归档 → `as_artifact`/`cv_artifact` 写入 → 集成测试。
5. **验证**:server-v2 单测 + 集成测试、runtime pytest、前端类型检查与构建、`docker compose up -d --build` + `e2e_smoke.py`、实机 API 走查(创建员工→派发→会话→产物区)。
6. **交付**:4 个产物归档 + 缺陷登记 + 提交。

## 7. 验证口径

- 单元测试:server-v2 `src/test`、运行时 `tests/`(pytest)。
- 集成测试:`server-v2/src/test/java/com/ainative/v2/support/IntegrationTestBase.java` 起的真实库测试。
- 实机:本机容器 `platform-app-1`(7001)+ `platform-seekdb-1`(33060),用真实 REST 调用断言落库与任务包内容。
- 交付门:三个改动的 AC 全绿 + 缺陷登记完成 + 4 个产物齐备。

## 8. 已知缺陷登记(本次不做,留档待办)

| 编号 | 现象 | 根因 | 影响 | 处置 |
|---|---|---|---|---|
| D-01 | 数字员工版本详情接口 `/api/agents/{id}/versions/{id}` 只回 `snapshot` JSON,不回前端期望的富字段(`businessBackground`/`responsibilities`/`roleName`/`repoPerms`/`skills` …) | `AgentResourceController` 版本查询只 SELECT 少数字段 + `snapshot_json AS snapshot`,与前端 `AgentVersion` 契约不一致 | 详情页版本对比里「草稿」一侧的人设显示 `-`;编辑页原读不到人设(本次已改为回落员工详情兜底) | 本次范围外,留档 |
| D-02 | 数字员工在会话里没有 `mcp__ainative__*` 工具 | `cv/ConversationTurnService` 下发 CONVERSATION_TURN 帧不带 `mcpToken`(运行时 `frames.py` 能解析,平台从不填) | 会话内查不到平台数据;派发路径同样不注入 MCP | 本次范围外,留档 |
| D-03 | 数字员工头像填了不落库 | `ag_agent` 无头像列,`CreateRequest` 不含 `avatarUrl` | 创建表单第 2 项无效 | 平台无对应字段,需另开需求加列 |
| D-04 | 数字员工没有「描述 / 标签 / 参数」字段 | `ag_agent` 只有 name / role_code / role_name / soul_md / agent_md / status / version | 额外信息只能并进 SOUL.md / AGENT.md 正文 | 同上 |
| D-05 | 澄清产物只归档结论,会话目录里的中间文件不回传 | 平台在 ACK 时只拿到回复正文 `replyMarkdown` | 澄清过程的中间文件不进产物区 | 结论归档本次已打通;文件回传需新增上传协议,作后续增强 |
| D-06 | 本机无法跑 server-v2 集成测试(testcontainers) | Docker 29 只接受 API ≥1.44,docker-java 协商到低版本被拒(400) | `src/test/.../api/*` 7 个集成测试类在本机起不了容器 | 环境问题,非代码缺陷;CI 上按正常流程跑 |
