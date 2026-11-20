# spec.md · 数字员工人设贯通与会话产物归档(需求规格)

- 版本:1.0 · 日期:2026-11-20 · 分支:`feat/agent-persona-artifact-archive`
- 平台基线:`platform/ainative/VERSION` = 1.2.0 · 运行时协议:`ainative.taskPackage.v1`
- 需求来源:需求 2000001「开发一个『公众号标题优化师』AI 数字员工」实机走查(截图见 `docs/case-agent/shots/`)

## 1. 问题

实机走查暴露三条断链,均属"数字员工的设定没有真正进入执行"这一类问题:

| 编号 | 现象 | 根因(已定位到源码) |
|---|---|---|
| P1 | 创建数字员工时填写的 SOUL.md / AGENT.md **保存后读不回来** | 前端提交 `businessBackground`/`responsibilities`,后端 `CreateRequest`/`ConfigRequest` 只认 `soulMd`,Jackson 静默丢弃;`responsibilities` 无对应字段,AGENT.md 从未落库(`AgentController.java:24-25` vs `frontend/src/features/agent/api.ts:96-97`) |
| P2 | 编辑数字员工配置会把 SOUL.md **清空** | `ConfigRequest.soulMd` 收到 null → `UPDATE ag_agent SET soul_md=NULL`(`AgentDao.java:67-70`),null 与"清空"不可区分 |
| P3 | 派发给数字员工的任务包里 **没有人设**,干活的是"通用模型"不是"这个员工" | `CapsuleBuilder.build` 写 `identity.json` 时只有 name/roleCode(`CapsuleBuilder.java:37-39`),`ag_agent.soul_md` 在派发链路从未被读取(`DispatchLaunchService.java:63-64`) |
| P4 | 澄清会话的产出 **不进需求产物区**,交付时拿不到澄清结论 | 会话回复只落 `ag_chat_turn.content_md`;`as_artifact` 无 `clarification:` 会话的记录,`cv_artifact` 表 0 行 |

## 2. 范围

### 做

- FR-01 **人设字段对齐**:创建/编辑数字员工时,SOUL.md 与 AGENT.md 分别落 `ag_agent.soul_md` / `ag_agent.agent_md`,并在详情页读回显示。
- FR-02 **编辑不清空**:配置更新区分"未提供"(保持原值)与"显式清空"(空串)。
- FR-03 **派发带人设**:派发任务包的 `identity.json` 携带人设(`soulMd`/`agentMd`/`rules`),运行时把它注入数字员工的系统提示词。
- FR-04 **包体可复现**:派发时把人设快照进 `rt_run`,重建包体按快照取,避免"派发后改人设 → md5 校验失败"。
- FR-05 **会话产物归档**:澄清会话每轮产出以 `kind=CLARIFICATION` 落 `as_artifact`(带 `ticket_id`),并写 `cv_artifact` 关联回合;需求详情产物区可见、可下载。
- FR-06 **澄清结论作为附件可引用**:归档产物可被澄清会话按附件引用(复用既有 `cv_clarification_attach`)。

### 不做(本次明确不做)

- 不做数字员工的"描述/标签/参数"字段(平台无对应表字段,见既往结论)。
- 不重写 server-v2 的版本/编辑页富接口(`/api/agents/{id}/versions/{no}` 前后端语义不一致)——登记缺陷,不在本次范围。
- 不做会话内 MCP 令牌下发(平台 `CONVERSATION_TURN` 帧缺 `mcpToken`,数字员工会话无 `mcp__ainative__*` 工具)——登记缺陷,不在本次范围。
- 不改 `ainative.taskPackage.v1` 协议标识与既有字段语义;只新增 `identity.json` 内的可选字段(未知字段忽略,向前兼容)。
- 不做对象存储(S3/MinIO)接入,产物仍存本地受控目录。

## 3. 用户场景

- **US-1 需求方**:我要把一个数字员工的业务背景和职责写清楚,下次派发时它就按这个身份干活,而不是通用助手。
- **US-2 需求方**:我澄清了一轮,产出了澄清纪要;它应当出现在这个需求的产物区,交付时能直接引用,而不是散在聊天记录里。
- **US-3 平台维护者**:改了数字员工的人设后,历史派发仍要能按原人设复现,不能因为包体变了导致运行器下载失败。

## 4. 功能需求(可验收)

| FR | 需求 | 验收标准(AC) |
|---|---|---|
| FR-01 | 创建数字员工(SOUL.md=`S`,AGENT.md=`A`) | `GET /api/agents/{id}` 返回 `soulMd=S`、`agentMd=A`;`ag_agent` 两列落库非空 |
| FR-01 | 编辑数字员工,提交同一份 `S`/`A` | 落库值等于提交值;编辑其他字段不再清空 SOUL.md |
| FR-02 | 编辑只提交 `roleName`,不带人设 | `soul_md`/`agent_md` 保持原值不变 |
| FR-02 | 编辑显式提交空串 | 对应列被置空(用户主动清空) |
| FR-03 | 派发到有 SOUL.md 的数字员工 | 任务包 `identity.json` 含 `soulMd` 原文与 `rules` 行数组;运行器命令的 `--append-system-prompt` 含人设文本 |
| FR-04 | 派发后修改数字员工人设,再下载该次任务包 | 包体字节与派发时一致(md5 相同),下载不失败 |
| FR-05 | 澄清会话完成一轮 | `as_artifact` 新增一行 `kind='CLARIFICATION'`、`ticket_id`=该需求 id;`cv_artifact` 有一行关联该回合;`GET /api/workitems/{id}/artifacts` 能查到 |
| FR-06 | 会话产物作为附件引用 | `POST .../clarification-conversations/{id}/attachments` 传 artifactId 成功;`GET .../attachments` 能列出 |

## 5. 非功能需求(可量化)

| NFR | 指标 | 目标 |
|---|---|---|
| NFR-01 | 派发包体大小增幅(人设注入后) | ≤ 8 KB(gzip 后 ≤ 4 KB) |
| NFR-02 | 任务包重建耗时 | 与改造前同量级(< 50 ms,p99) |
| NFR-03 | 会话产物归档写入耗时 | 单回合 ACK 处理内 < 100 ms(不含文件存储 IO) |
| NFR-04 | 人设注入后回归 | server-v2 全部单测 + 集成测试通过;runtime 全部 pytest 通过 |
| NFR-05 | 包体可复现性 | 同一次派发任意时刻重建,md5 恒等 |
| NFR-06 | 前端可见文案 | 新增文案必须同时进 `zh-CN` 与 `en-US` 两份 locale 文件(本次预期零新增) |

## 6. 影响面

- 平台服务端:`agent/`(Controller/Service/Dao)、`dispatch/`(CapsuleBuilder/DispatchLaunchService)、`cv/`(ConversationTurnService)、`artifact/`、`pf/ProjectBackupService`。
- 平台前端:`features/agent/api.ts`(请求/响应字段映射)。
- 运行时:`runtime/providers/claude_code.py`、`runtime/providers/text_cli.py`、`runtime/daemon.py`;`runtime-npm/runtime/` 同源副本需同步。(`runtime/packager.py` 已读取 `identity.json`,无需改动;`runtime/frames.py` 不涉及人设,无需改动)
- 数据库:`ag_agent` 加 `agent_md`;`rt_run` 加 `dispatch_snapshot`(人设 + 工单 + 步骤 + 名册的整包输入快照)。
